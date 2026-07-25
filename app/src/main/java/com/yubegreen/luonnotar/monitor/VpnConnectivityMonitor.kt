package com.yubegreen.luonnotar.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import com.yubegreen.luonnotar.util.LogManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.concurrent.atomic.AtomicBoolean

data class VpnEvidence(
    val present: Boolean,
    val validated: Boolean,
    val bypassable: Boolean?,
    val networkHandle: Long,
    val internetRouted: Boolean = false,
    val ipv4DefaultRoute: Boolean = false,
    val ipv6DefaultRoute: Boolean = false,
    val providerPackage: String? = null
)

class VpnConnectivityMonitor(
    private val context: Context,
    private val onChanged: (VpnEvidence) -> Unit
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val active = AtomicBoolean(false)
    @Volatile
    private var last: VpnEvidence? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (!active.get()) return
            publish(
                evidence(
                    network,
                    capabilities,
                    connectivity.getLinkProperties(network)
                )
            )
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties
        ) {
            if (!active.get()) return
            publish(
                evidence(
                    network,
                    connectivity.getNetworkCapabilities(network),
                    linkProperties
                )
            )
        }

        override fun onLost(network: Network) {
            if (!active.get()) return
            if (last?.networkHandle == network.networkHandle) {
                publish(VpnEvidence(false, false, null, -1L))
            }
        }
    }

    fun start() {
        if (!active.compareAndSet(false, true)) return
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
        }.onFailure {
            active.set(false)
            throw it
        }
        publish(current())
    }

    fun stop() {
        if (!active.getAndSet(false)) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    fun current(): VpnEvidence {
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val linkProperties = network?.let(connectivity::getLinkProperties)
        return evidence(network, caps, linkProperties)
    }

    private fun evidence(
        network: Network?,
        caps: NetworkCapabilities?,
        linkProperties: LinkProperties?
    ): VpnEvidence {
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val bypassable = if (vpn && Build.VERSION.SDK_INT >= 29) {
            readBypassableCompat(caps)
        } else null
        val routeEvidence = if (vpn) {
            VpnDefaultRoutePolicy.evaluate(
                linkProperties?.routes.orEmpty().mapNotNull { route ->
                    when (route.destination.address) {
                        is Inet4Address -> VpnRouteDescriptor(
                            VpnRouteFamily.IPV4,
                            route.destination.prefixLength,
                            route.destination.address.hostAddress.orEmpty(),
                            routeIsUsable(route)
                        )
                        is Inet6Address -> VpnRouteDescriptor(
                            VpnRouteFamily.IPV6,
                            route.destination.prefixLength,
                            route.destination.address.hostAddress.orEmpty(),
                            routeIsUsable(route)
                        )
                        else -> null
                    }
                }
            )
        } else {
            VpnDefaultRouteEvidence()
        }
        return VpnEvidence(
            present = vpn,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            bypassable = bypassable,
            networkHandle = network?.networkHandle ?: -1,
            internetRouted = routeEvidence.internetRouted,
            ipv4DefaultRoute = routeEvidence.ipv4DefaultRoute,
            ipv6DefaultRoute = routeEvidence.ipv6DefaultRoute,
            providerPackage = if (vpn) providerPackage(caps) else null
        )
    }

    private fun providerPackage(caps: NetworkCapabilities?): String? {
        if (caps == null || Build.VERSION.SDK_INT < 30) return null
        val ownerUid = readOwnerUidCompat(caps)
        if (ownerUid < 0) return null
        return context.packageManager.getPackagesForUid(ownerUid)
            ?.firstOrNull(SupportedVpnProvider::isSupported)
    }

    private fun routeIsUsable(route: android.net.RouteInfo): Boolean =
        runCatching {
            val method = route.javaClass.methods.firstOrNull {
                it.name == "getType" && it.parameterTypes.isEmpty()
            } ?: return true
            (method.invoke(route) as? Int) == 1
        }.getOrDefault(false)

    @RequiresApi(30)
    private fun readOwnerUidCompat(caps: NetworkCapabilities): Int =
        runCatching { caps.ownerUid }.getOrDefault(-1)

    @RequiresApi(29)
    private fun readBypassableCompat(caps: NetworkCapabilities?): Boolean? {
        val info = caps?.transportInfo ?: return null
        if (!info.javaClass.name.endsWith("VpnTransportInfo")) return null
        return runCatching {
            val method = info.javaClass.methods.firstOrNull {
                it.name == "isBypassable" && it.parameterTypes.isEmpty()
            } ?: return null
            method.invoke(info) as? Boolean
        }.getOrNull()
    }

    @Synchronized
    private fun publish(now: VpnEvidence) {
        if (!active.get()) return
        if (now == last) return
        val previous = last
        last = now
        LogManager.event(
            context,
            "vpn_evidence_changed",
            mapOf("from" to previous?.toString(), "to" to now.toString())
        )
        onChanged(now)
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
                usable.any { it.family == VpnRouteFamily.IPV4 && it.prefixLength == 0 } ||
                    hasSplitDefault(
                        usable,
                        VpnRouteFamily.IPV4,
                        "0.0.0.0",
                        "128.0.0.0"
                    ),
            ipv6DefaultRoute =
                usable.any { it.family == VpnRouteFamily.IPV6 && it.prefixLength == 0 } ||
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
