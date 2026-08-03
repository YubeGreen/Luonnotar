package com.yubegreen.luonnotar.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.yubegreen.luonnotar.util.LogManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

data class TailscaleNetworkEvidence(
    val present: Boolean = false,
    val network: Network? = null,
    val networkHandle: Long = -1L,
    val complete: Boolean = false,
    val validated: Boolean = false,
    val blocked: Boolean = false,
    val blockedKnown: Boolean = false,
    val suspended: Boolean = false,
    val routeState: VpnRouteState = VpnRouteState.UNKNOWN,
    val ipv4DefaultRoute: Boolean = false,
    val ipv6DefaultRoute: Boolean = false,
    val dnsServers: List<InetAddress> = emptyList(),
    val underlyingNetworkHandles: Set<Long> = emptySet(),
    val lastUpdatedElapsed: Long = 0L
) {
    val usable: Boolean
        get() =
            present &&
                complete &&
                validated &&
                !blocked &&
                !suspended &&
                routeState == VpnRouteState.ROUTED
}

class TailscaleNetworkMonitor(
    private val context: Context,
    private val onChanged: (TailscaleNetworkEvidence) -> Unit
) {
    private val connectivity =
        context.getSystemService(ConnectivityManager::class.java)
    private val callbackThread =
        HandlerThread("luonnotar-tailscale-network").apply { start() }
    private val handler = Handler(callbackThread.looper)
    private val active = AtomicBoolean(false)
    private val records = LinkedHashMap<Long, Record>()
    @Volatile
    private var last = TailscaleNetworkEvidence()
    @Volatile
    private var expectedTailscaleHandle = -1L

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!active.get()) return
            record(network).lastUpdatedElapsed = SystemClock.elapsedRealtime()
            publishSelected()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (!active.get()) return
            record(network).apply {
                this.capabilities = capabilities
                providerPackage = providerPackage(capabilities)
                lastUpdatedElapsed = SystemClock.elapsedRealtime()
            }
            publishSelected()
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
            publishSelected()
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (!active.get()) return
            record(network).apply {
                this.blocked = blocked
                blockedKnown = true
                lastUpdatedElapsed = SystemClock.elapsedRealtime()
            }
            publishSelected()
        }

        override fun onLost(network: Network) {
            if (!active.get()) return
            records.remove(network.networkHandle)
            publishSelected()
        }
    }

    fun start() {
        if (!active.compareAndSet(false, true)) return
        runCatching {
            connectivity.registerNetworkCallback(request, callback, handler)
        }.onFailure {
            active.set(false)
            handler.removeCallbacksAndMessages(null)
            callbackThread.quitSafely()
            throw it
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

    fun current(): TailscaleNetworkEvidence = last

    fun setExpectedTailscaleHandle(networkHandle: Long?) {
        expectedTailscaleHandle = networkHandle ?: -1L
        if (active.get()) {
            handler.post(::publishSelected)
        }
    }

    private fun record(network: Network): Record =
        records.getOrPut(network.networkHandle) { Record(network) }

    private fun publishSelected() {
        if (!active.get()) return
        val selected = records.values
            .filter {
                it.providerPackage ==
                    SupportedVpnProvider.TAILSCALE.packageName ||
                    it.network.networkHandle == expectedTailscaleHandle ||
                    TailscaleNetworkPolicy.hasQuad100Dns(
                        it.linkProperties?.dnsServers.orEmpty()
                    )
            }
            .maxWithOrNull(
                compareBy<Record> {
                    when {
                        it.network.networkHandle ==
                            expectedTailscaleHandle -> 4
                        it.providerPackage ==
                            SupportedVpnProvider.TAILSCALE.packageName -> 3
                        TailscaleNetworkPolicy.hasQuad100Dns(
                            it.linkProperties?.dnsServers.orEmpty()
                        ) -> 2
                        else -> 0
                    }
                }.thenBy(Record::lastUpdatedElapsed)
            )
        val evidence = selected?.toEvidence() ?: TailscaleNetworkEvidence()
        if (evidence == last) return
        val previous = last
        last = evidence
        LogManager.timeline(
            context,
            "tailscale_network_changed",
            mapOf(
                "previousHandle" to previous.networkHandle,
                "networkHandle" to evidence.networkHandle,
                "complete" to evidence.complete,
                "validated" to evidence.validated,
                "blocked" to evidence.blocked,
                "blockedKnown" to evidence.blockedKnown,
                "suspended" to evidence.suspended,
                "routeState" to evidence.routeState.name,
                "dnsServers" to evidence.dnsServers.map(InetAddress::getHostAddress),
                "underlyingHandles" to evidence.underlyingNetworkHandles
            )
        )
        onChanged(evidence)
    }

    private fun providerPackage(capabilities: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val ownerUid = runCatching { capabilities.ownerUid }.getOrDefault(-1)
        if (ownerUid < 0) return null
        return context.packageManager.getPackagesForUid(ownerUid)
            ?.firstOrNull(SupportedVpnProvider::isSupported)
    }

    private fun routeEvidence(
        linkProperties: LinkProperties
    ): VpnDefaultRouteEvidence? {
        var unknownRouteType = false
        val descriptors =
            linkProperties.routes.mapNotNull { route ->
                val family = when (route.destination.address) {
                    is Inet4Address -> VpnRouteFamily.IPV4
                    is Inet6Address -> VpnRouteFamily.IPV6
                    else -> return@mapNotNull null
                }
                val usable = routeIsUsable(route)
                if (usable == null) unknownRouteType = true
                VpnRouteDescriptor(
                    family = family,
                    prefixLength = route.destination.prefixLength,
                    networkAddress =
                        route.destination.address.hostAddress.orEmpty(),
                    usable = usable ?: true
                )
            }
        return if (unknownRouteType) {
            null
        } else {
            VpnDefaultRoutePolicy.evaluate(descriptors)
        }
    }

    private fun routeIsUsable(route: android.net.RouteInfo): Boolean? =
        runCatching<Boolean?> {
            val method = route.javaClass.methods.firstOrNull {
                it.name == "getType" && it.parameterTypes.isEmpty()
            } ?: return@runCatching null
            (method.invoke(route) as? Int) == 1
        }.getOrNull()

    private inner class Record(val network: Network) {
        var capabilities: NetworkCapabilities? = null
        var linkProperties: LinkProperties? = null
        var providerPackage: String? = null
        var blocked = false
        var blockedKnown = false
        var lastUpdatedElapsed = 0L

        fun toEvidence(): TailscaleNetworkEvidence {
            val caps = capabilities
            val links = linkProperties
            val routes = links?.let(::routeEvidence)
            val routeState = when {
                links == null -> VpnRouteState.UNKNOWN
                routes?.internetRouted == true -> VpnRouteState.ROUTED
                routes == null -> VpnRouteState.UNKNOWN
                else -> VpnRouteState.NOT_ROUTED
            }
            val underlying = underlyingNetworkHandles(caps)
            return TailscaleNetworkEvidence(
                present = true,
                network = network,
                networkHandle = network.networkHandle,
                complete = caps != null && links != null,
                validated =
                    caps?.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    ) == true,
                blocked = blocked,
                blockedKnown = blockedKnown,
                suspended =
                    Build.VERSION.SDK_INT >= 28 &&
                        caps != null &&
                        !caps.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
                        ),
                routeState = routeState,
                ipv4DefaultRoute = routes?.ipv4DefaultRoute == true,
                ipv6DefaultRoute = routes?.ipv6DefaultRoute == true,
                dnsServers = links?.dnsServers?.toList().orEmpty(),
                underlyingNetworkHandles = underlying,
                lastUpdatedElapsed = lastUpdatedElapsed
            )
        }
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
}

internal object TailscaleNetworkPolicy {
    private val quad100Addresses = setOf(
        InetAddress.getByName("100.100.100.100"),
        InetAddress.getByName("fd7a:115c:a1e0::53")
    )

    fun hasQuad100Dns(dnsServers: Collection<InetAddress>): Boolean =
        dnsServers.any(quad100Addresses::contains)
}
