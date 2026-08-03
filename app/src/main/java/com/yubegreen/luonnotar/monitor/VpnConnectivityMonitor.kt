package com.yubegreen.luonnotar.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.yubegreen.luonnotar.util.LogManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class VpnEvidence(
    val present: Boolean,
    val validated: Boolean,
    val bypassable: Boolean?,
    val networkHandle: Long,
    val routeState: VpnRouteState = VpnRouteState.UNKNOWN,
    val ipv4DefaultRoute: Boolean = false,
    val ipv6DefaultRoute: Boolean = false,
    val providerPackage: String? = null,
    val complete: Boolean = false,
    val blocked: Boolean = false,
    val blockedKnown: Boolean = false,
    val notSuspended: Boolean = true,
    val sessionFingerprint: String = "",
    val sessionGeneration: Long = 0L,
    val interfaceName: String = "",
    val linkAddresses: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    val mtu: Int = 0,
    val underlyingNetworkHandles: Set<Long> = emptySet(),
    val network: Network? = null
) {
    val internetRouted: Boolean
        get() = routeState == VpnRouteState.ROUTED

    val usable: Boolean
        get() =
            present &&
                complete &&
                validated &&
                (!blockedKnown || !blocked) &&
                notSuspended &&
                routeState == VpnRouteState.ROUTED
}

enum class VpnRouteState {
    UNKNOWN,
    ROUTED,
    NOT_ROUTED
}

class VpnConnectivityMonitor(
    private val context: Context,
    private val onChanged: (VpnEvidence) -> Unit
) {
    private val connectivity =
        context.getSystemService(ConnectivityManager::class.java)
    private val callbackThread =
        HandlerThread("luonnotar-vpn-session").apply { start() }
    private val handler = Handler(callbackThread.looper)
    private val active = AtomicBoolean(false)
    private val records = LinkedHashMap<Long, Record>()
    private val routeRefresh = Runnable {
        val handle = last.networkHandle
        records[handle]?.let(::publishRecord)
    }
    @Volatile
    private var last = VpnEvidence(false, false, null, -1L)
    private var routeLossCandidateElapsed = 0L
    private var routeLossCandidateHandle = -1L
    private var currentDefaultHandle = -1L
    private var lastFingerprint = ""
    private var sessionGeneration = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!active.get()) return
            currentDefaultHandle = network.networkHandle
            record(network).lastUpdatedElapsed = SystemClock.elapsedRealtime()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (!active.get()) return
            record(network).apply {
                this.capabilities = capabilities
                lastUpdatedElapsed = SystemClock.elapsedRealtime()
            }
            publishRecord(record(network))
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties
        ) {
            if (!active.get()) return
            record(network).apply {
                this.linkProperties = linkProperties
                lastUpdatedElapsed = SystemClock.elapsedRealtime()
            }
            if (record(network).capabilities != null) {
                publishRecord(record(network))
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (!active.get()) return
            record(network).apply {
                this.blocked = blocked
                blockedKnown = true
                lastUpdatedElapsed = SystemClock.elapsedRealtime()
            }
            if (record(network).capabilities != null) {
                publishRecord(record(network))
            }
        }

        override fun onLost(network: Network) {
            if (!active.get()) return
            records.remove(network.networkHandle)
            if (currentDefaultHandle == network.networkHandle) {
                currentDefaultHandle = -1L
                publish(VpnEvidence(false, false, null, -1L))
            }
        }
    }

    fun start() {
        if (!active.compareAndSet(false, true)) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback, handler)
        }.onFailure {
            active.set(false)
            handler.removeCallbacksAndMessages(null)
            callbackThread.quitSafely()
            throw it
        }
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val links = network?.let(connectivity::getLinkProperties)
        handler.post {
            if (!active.get()) return@post
            if (network == null || caps == null) {
                if (records.isEmpty()) {
                    publish(VpnEvidence(false, false, null, -1L))
                }
            } else {
                val record = record(network)
                currentDefaultHandle = network.networkHandle
                if (record.capabilities == null) record.capabilities = caps
                if (record.linkProperties == null) record.linkProperties = links
                record.lastUpdatedElapsed = SystemClock.elapsedRealtime()
                publishRecord(record)
            }
        }
    }

    fun stop() {
        if (!active.getAndSet(false)) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        handler.removeCallbacksAndMessages(null)
        callbackThread.quitSafely()
        if (Thread.currentThread() !== callbackThread) {
            runCatching { callbackThread.join(1_000L) }
        }
    }

    fun current(): VpnEvidence = last

    private fun record(network: Network): Record =
        records.getOrPut(network.networkHandle) { Record(network) }

    private fun publishRecord(record: Record) {
        if (
            currentDefaultHandle >= 0L &&
            record.network.networkHandle != currentDefaultHandle
        ) return
        val raw = evidence(record)
        publish(stabilize(raw))
    }

    private fun evidence(record: Record): VpnEvidence {
        val caps = record.capabilities
        val links = record.linkProperties
        val vpn =
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val bypassable = if (vpn && Build.VERSION.SDK_INT >= 29) {
            readBypassableCompat(caps)
        } else {
            null
        }
        val routes =
            if (vpn && links != null) routeEvidence(links)
            else VpnDefaultRouteEvidence()
        val complete = vpn && caps != null && links != null
        val routeState = when {
            !complete -> VpnRouteState.UNKNOWN
            routes.internetRouted -> VpnRouteState.ROUTED
            else -> VpnRouteState.NOT_ROUTED
        }
        val provider = if (vpn) providerPackage(caps) else null
        val underlying = underlyingNetworkHandles(caps)
        val linkAddresses = links?.linkAddresses
            ?.map { "${it.address.hostAddress}/${it.prefixLength}" }
            ?.sorted()
            .orEmpty()
        val dnsServers = links?.dnsServers
            ?.map { it.hostAddress.orEmpty() }
            ?.sorted()
            .orEmpty()
        val notSuspended =
            Build.VERSION.SDK_INT < 28 ||
                caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
                ) == true
        val fingerprint =
            if (complete) {
                VpnSessionFingerprint.build(
                    providerPackage = provider,
                    networkHandle = record.network.networkHandle,
                    interfaceName = links?.interfaceName.orEmpty(),
                    linkAddresses = linkAddresses,
                    dnsServers = dnsServers,
                    routeSet = links?.routes
                        ?.map { route ->
                            listOf(
                                route.destination.toString(),
                                route.gateway?.hostAddress.orEmpty(),
                                route.`interface`.orEmpty(),
                                routeIsUsable(route).toString()
                            ).joinToString("|")
                        }
                        ?.sorted()
                        .orEmpty(),
                    mtu = linkMtu(links),
                    underlyingNetworkHandles = underlying,
                    bypassable = bypassable
                )
            } else {
                ""
            }
        return VpnEvidence(
            present = vpn,
            validated =
                caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                ) == true,
            bypassable = bypassable,
            networkHandle = record.network.networkHandle,
            routeState = routeState,
            ipv4DefaultRoute = routes.ipv4DefaultRoute,
            ipv6DefaultRoute = routes.ipv6DefaultRoute,
            providerPackage = provider,
            complete = complete,
            blocked = record.blocked,
            blockedKnown = record.blockedKnown,
            notSuspended = notSuspended,
            sessionFingerprint = fingerprint,
            sessionGeneration = sessionGeneration,
            interfaceName = links?.interfaceName.orEmpty(),
            linkAddresses = linkAddresses,
            dnsServers = dnsServers,
            mtu = linkMtu(links),
            underlyingNetworkHandles = underlying,
            network = record.network
        )
    }

    private fun linkMtu(linkProperties: LinkProperties?): Int =
        if (Build.VERSION.SDK_INT >= 29) {
            linkProperties?.mtu ?: 0
        } else {
            0
        }

    private fun stabilize(raw: VpnEvidence): VpnEvidence {
        val previous = last
        val sameVpn =
            raw.present &&
                previous.present &&
                raw.networkHandle == previous.networkHandle
        val observed = VpnRouteObservation(
            state = raw.routeState,
            ipv4DefaultRoute = raw.ipv4DefaultRoute,
            ipv6DefaultRoute = raw.ipv6DefaultRoute
        )
        val prior = VpnRouteObservation(
            state = previous.routeState,
            ipv4DefaultRoute = previous.ipv4DefaultRoute,
            ipv6DefaultRoute = previous.ipv6DefaultRoute
        ).takeIf { previous.present }
        if (!sameVpn || routeLossCandidateHandle != raw.networkHandle) {
            routeLossCandidateElapsed = 0L
            routeLossCandidateHandle = raw.networkHandle
        }
        val decision = VpnRouteStabilityPolicy.decide(
            previous = prior,
            observed = observed,
            sameVpnHandle = sameVpn,
            nowElapsed = SystemClock.elapsedRealtime(),
            routeLossCandidateElapsed = routeLossCandidateElapsed
        )
        routeLossCandidateElapsed = decision.routeLossCandidateElapsed
        if (decision.scheduleRefresh) {
            handler.removeCallbacks(routeRefresh)
            handler.postDelayed(
                routeRefresh,
                VpnRouteStabilityPolicy.ROUTE_LOSS_DEBOUNCE_MS
            )
        } else if (decision.routeLossCandidateElapsed == 0L) {
            handler.removeCallbacks(routeRefresh)
        }
        val routeObservationAccepted = decision.evidence == observed
        val acceptedFingerprint =
            if (routeObservationAccepted) {
                raw.sessionFingerprint
            } else {
                previous.sessionFingerprint.takeIf { sameVpn }.orEmpty()
            }
        if (
            acceptedFingerprint.isNotBlank() &&
            acceptedFingerprint != lastFingerprint
        ) {
            lastFingerprint = acceptedFingerprint
            sessionGeneration += 1L
        }
        return raw.copy(
            routeState = decision.evidence.state,
            ipv4DefaultRoute = decision.evidence.ipv4DefaultRoute,
            ipv6DefaultRoute = decision.evidence.ipv6DefaultRoute,
            providerPackage =
                raw.providerPackage
                    ?: previous.providerPackage.takeIf { sameVpn },
            sessionFingerprint = acceptedFingerprint,
            sessionGeneration = sessionGeneration
        )
    }

    @Synchronized
    private fun publish(now: VpnEvidence) {
        if (!active.get()) return
        if (now == last) return
        val previous = last
        last = now
        if (!now.present) {
            lastFingerprint = ""
        }
        LogManager.timeline(
            context,
            "vpn_session_evidence_changed",
            mapOf(
                "previousHandle" to previous.networkHandle,
                "networkHandle" to now.networkHandle,
                "complete" to now.complete,
                "validated" to now.validated,
                "blocked" to now.blocked,
                "blockedKnown" to now.blockedKnown,
                "notSuspended" to now.notSuspended,
                "routeState" to now.routeState.name,
                "provider" to now.providerPackage,
                "sessionFingerprint" to now.sessionFingerprint,
                "sessionGeneration" to now.sessionGeneration
            )
        )
        onChanged(now)
    }

    private fun routeEvidence(
        linkProperties: LinkProperties
    ): VpnDefaultRouteEvidence =
        VpnDefaultRoutePolicy.evaluate(
            linkProperties.routes.mapNotNull { route ->
                val family = when (route.destination.address) {
                    is Inet4Address -> VpnRouteFamily.IPV4
                    is Inet6Address -> VpnRouteFamily.IPV6
                    else -> return@mapNotNull null
                }
                VpnRouteDescriptor(
                    family = family,
                    prefixLength = route.destination.prefixLength,
                    networkAddress =
                        route.destination.address.hostAddress.orEmpty(),
                    usable = routeIsUsable(route)
                )
            }
        )

    private fun providerPackage(caps: NetworkCapabilities?): String? {
        if (caps == null || Build.VERSION.SDK_INT < 30) return null
        val ownerUid = readOwnerUidCompat(caps)
        if (ownerUid < 0) return null
        return context.packageManager.getPackagesForUid(ownerUid)
            ?.firstOrNull(SupportedVpnProvider::isSupported)
    }

    private fun underlyingNetworkHandles(
        capabilities: NetworkCapabilities?
    ): Set<Long> {
        if (capabilities == null || Build.VERSION.SDK_INT < 31) {
            return emptySet()
        }
        return runCatching {
            val accessor = capabilities.javaClass.methods.firstOrNull {
                it.name == "getUnderlyingNetworks" &&
                    it.parameterTypes.isEmpty()
            } ?: return emptySet()
            val networks = accessor.invoke(capabilities) as? List<*>
                ?: return emptySet()
            networks.filterIsInstance<Network>()
                .map { it.networkHandle }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun routeIsUsable(route: android.net.RouteInfo): Boolean =
        runCatching {
            val method = route.javaClass.methods.firstOrNull {
                it.name == "getType" && it.parameterTypes.isEmpty()
            } ?: return true
            (method.invoke(route) as? Int) == 1
        }.getOrDefault(true)

    @RequiresApi(30)
    private fun readOwnerUidCompat(caps: NetworkCapabilities): Int =
        runCatching { caps.ownerUid }.getOrDefault(-1)

    @RequiresApi(29)
    private fun readBypassableCompat(
        caps: NetworkCapabilities?
    ): Boolean? {
        val info = caps?.transportInfo ?: return null
        if (!info.javaClass.name.endsWith("VpnTransportInfo")) return null
        return runCatching {
            val method = info.javaClass.methods.firstOrNull {
                it.name == "isBypassable" &&
                    it.parameterTypes.isEmpty()
            } ?: return null
            method.invoke(info) as? Boolean
        }.getOrNull()
    }

    private data class Record(val network: Network) {
        var capabilities: NetworkCapabilities? = null
        var linkProperties: LinkProperties? = null
        var blocked = false
        var blockedKnown = false
        var lastUpdatedElapsed = 0L
    }
}

object VpnSessionFingerprint {
    fun build(
        providerPackage: String?,
        networkHandle: Long,
        interfaceName: String,
        linkAddresses: List<String>,
        dnsServers: List<String>,
        routeSet: List<String>,
        mtu: Int,
        underlyingNetworkHandles: Set<Long>,
        bypassable: Boolean?
    ): String {
        val canonical = listOf(
            providerPackage.orEmpty(),
            networkHandle.toString(),
            interfaceName,
            linkAddresses.sorted().joinToString(","),
            dnsServers.sorted().joinToString(","),
            routeSet.sorted().joinToString(","),
            mtu.toString(),
            underlyingNetworkHandles.sorted().joinToString(","),
            bypassable?.toString().orEmpty()
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class VpnRouteObservation(
    val state: VpnRouteState,
    val ipv4DefaultRoute: Boolean,
    val ipv6DefaultRoute: Boolean
)

data class VpnRouteStabilityDecision(
    val evidence: VpnRouteObservation,
    val routeLossCandidateElapsed: Long,
    val scheduleRefresh: Boolean
)

object VpnRouteStabilityPolicy {
    const val ROUTE_LOSS_DEBOUNCE_MS = 2_500L

    fun decide(
        previous: VpnRouteObservation?,
        observed: VpnRouteObservation,
        sameVpnHandle: Boolean,
        nowElapsed: Long,
        routeLossCandidateElapsed: Long
    ): VpnRouteStabilityDecision {
        if (
            sameVpnHandle &&
            previous != null &&
            observed.state == VpnRouteState.UNKNOWN
        ) {
            return VpnRouteStabilityDecision(
                previous,
                routeLossCandidateElapsed,
                false
            )
        }
        if (
            sameVpnHandle &&
            previous?.state == VpnRouteState.ROUTED &&
            observed.state == VpnRouteState.NOT_ROUTED
        ) {
            val candidate =
                routeLossCandidateElapsed.takeIf { it > 0L } ?: nowElapsed
            if (
                nowElapsed - candidate <
                VpnRouteStabilityPolicy.ROUTE_LOSS_DEBOUNCE_MS
            ) {
                return VpnRouteStabilityDecision(
                    previous,
                    candidate,
                    true
                )
            }
        }
        return VpnRouteStabilityDecision(observed, 0L, false)
    }
}

enum class VpnRouteFamily {
    IPV4,
    IPV6
}

data class VpnRouteDescriptor(
    val family: VpnRouteFamily,
    val prefixLength: Int,
    val networkAddress: String = "",
    val usable: Boolean = true
)

data class VpnDefaultRouteEvidence(
    val ipv4DefaultRoute: Boolean = false,
    val ipv6DefaultRoute: Boolean = false
) {
    val internetRouted: Boolean
        get() = ipv4DefaultRoute || ipv6DefaultRoute
}

object VpnDefaultRoutePolicy {
    fun evaluate(routes: List<VpnRouteDescriptor>): VpnDefaultRouteEvidence {
        val usable = routes.filter(VpnRouteDescriptor::usable)
        return VpnDefaultRouteEvidence(
            ipv4DefaultRoute =
                usable.any {
                    it.family == VpnRouteFamily.IPV4 &&
                        it.prefixLength == 0
                } ||
                    hasSplitDefault(
                        usable,
                        VpnRouteFamily.IPV4,
                        "0.0.0.0",
                        "128.0.0.0"
                    ),
            ipv6DefaultRoute =
                usable.any {
                    it.family == VpnRouteFamily.IPV6 &&
                        it.prefixLength == 0
                } ||
                    hasSplitDefault(
                        usable,
                        VpnRouteFamily.IPV6,
                        "0:0:0:0:0:0:0:0",
                        "8000:0:0:0:0:0:0:0"
                    )
        )
    }

    private fun hasSplitDefault(
        routes: List<VpnRouteDescriptor>,
        family: VpnRouteFamily,
        lowerAddress: String,
        upperAddress: String
    ): Boolean {
        val halves = routes.asSequence()
            .filter { it.family == family && it.prefixLength == 1 }
            .map { normalizeAddress(it.networkAddress) }
            .toSet()
        return lowerAddress in halves && upperAddress in halves
    }

    private fun normalizeAddress(address: String): String = when {
        address == "::" -> "0:0:0:0:0:0:0:0"
        address.equals("8000::", ignoreCase = true) ->
            "8000:0:0:0:0:0:0:0"
        else -> address.substringBefore('%').lowercase()
    }
}
