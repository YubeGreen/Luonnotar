package com.yubegreen.luonnotar.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import com.yubegreen.luonnotar.util.LogManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

data class NetworkEvidence(
    val connected: Boolean,
    val validated: Boolean,
    val transport: String,
    val wifiUnderlying: Boolean,
    val networkHandle: Long = -1L,
    val underlaySource: String = "unknown"
)

class NetworkStateMonitor(
    private val context: Context,
    private val onChanged: (NetworkEvidence) -> Unit
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callbackThread = HandlerThread("luonnotar-network").apply { start() }
    private val handler = Handler(callbackThread.looper)
    private val refreshUnderlay = Runnable { publish(current()) }
    private val active = AtomicBoolean(false)
    private val observedCapabilities =
        ConcurrentHashMap<Long, NetworkCapabilities>()
    private val observedLinkProperties =
        ConcurrentHashMap<Long, LinkProperties>()
    @Volatile
    private var last: NetworkEvidence? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (!active.get()) return
            observedCapabilities[network.networkHandle] = capabilities
            publish(evidenceFromCallback(network, capabilities))
        }

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: LinkProperties
        ) {
            if (!active.get()) return
            observedLinkProperties[network.networkHandle] = linkProperties
            observedCapabilities[network.networkHandle]?.let {
                publish(evidenceFromCallback(network, it))
            }
        }

        override fun onLost(network: Network) {
            if (!active.get()) return
            observedCapabilities.remove(network.networkHandle)
            observedLinkProperties.remove(network.networkHandle)
            if (last?.networkHandle == network.networkHandle) {
                publish(
                    NetworkEvidence(
                        false,
                        false,
                        "NONE",
                        false,
                        -1L,
                        "default_network_lost"
                    )
                )
            }
        }
    }
    private val physicalRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
    private val physicalCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (!active.get()) return
                observedCapabilities[network.networkHandle] = capabilities
                if (
                    last?.transport == "UNDERLAY_UNKNOWN" &&
                    !capabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_VPN
                    )
                ) {
                    handler.removeCallbacks(refreshUnderlay)
                    handler.postDelayed(refreshUnderlay, 250L)
                }
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties
            ) {
                if (!active.get()) return
                observedLinkProperties[network.networkHandle] = linkProperties
                handler.removeCallbacks(refreshUnderlay)
                handler.post(refreshUnderlay)
            }

            override fun onLost(network: Network) {
                if (!active.get()) return
                observedCapabilities.remove(network.networkHandle)
                observedLinkProperties.remove(network.networkHandle)
                handler.post {
                    if (active.get()) publish(current())
                }
            }
        }

    fun start() {
        if (!active.compareAndSet(false, true)) return
        runCatching {
            connectivity.registerNetworkCallback(
                physicalRequest,
                physicalCallback,
                handler
            )
            connectivity.registerDefaultNetworkCallback(callback, handler)
        }.onFailure {
            active.set(false)
            runCatching {
                connectivity.unregisterNetworkCallback(physicalCallback)
            }
            runCatching {
                connectivity.unregisterNetworkCallback(callback)
            }
            handler.removeCallbacksAndMessages(null)
            callbackThread.quitSafely()
            throw it
        }
        publish(current())
    }

    fun stop() {
        if (!active.getAndSet(false)) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        runCatching {
            connectivity.unregisterNetworkCallback(physicalCallback)
        }
        handler.removeCallbacksAndMessages(null)
        callbackThread.quitSafely()
        if (Thread.currentThread() !== callbackThread) {
            runCatching { callbackThread.join(1_000L) }
        }
    }

    fun current(): NetworkEvidence {
        val active = connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        val links = active?.let(connectivity::getLinkProperties)
        val defaultIsVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val underlay = if (caps == null) {
            Underlay("NONE", "no_active_network")
        } else if (!defaultIsVpn) {
            Underlay(
                physicalUnderlay(caps, links)?.transport ?: "OTHER",
                physicalUnderlay(caps, links)?.source
                    ?: "default_network_capabilities"
            )
        } else {
            resolveVpnUnderlay(
                caps,
                allowValidatedNetworkScan = true,
                linkProperties = links
            )
        }
        return NetworkEvidence(
            connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            transport = underlay.transport,
            wifiUnderlying = underlay.transport == "WIFI",
            networkHandle = active?.networkHandle ?: -1L,
            underlaySource = underlay.source
        )
    }

    private fun evidenceFromCallback(
        network: Network,
        caps: NetworkCapabilities
    ): NetworkEvidence {
        val underlay = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            resolveVpnUnderlay(
                caps,
                allowValidatedNetworkScan = false,
                linkProperties = null
            )
        } else {
            physicalUnderlay(caps, null)
                ?: Underlay("OTHER", "network_callback_capabilities")
        }
        if (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            underlay.transport == "UNDERLAY_UNKNOWN"
        ) {
            handler.removeCallbacks(refreshUnderlay)
            handler.postDelayed(refreshUnderlay, 750L)
        }
        return NetworkEvidence(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = underlay.transport,
            wifiUnderlying = underlay.transport == "WIFI",
            networkHandle = network.networkHandle,
            underlaySource = underlay.source
        )
    }

    private fun resolveVpnUnderlay(
        vpnCapabilities: NetworkCapabilities,
        allowValidatedNetworkScan: Boolean,
        linkProperties: LinkProperties?
    ): Underlay {
        physicalUnderlay(vpnCapabilities, linkProperties)?.let {
            return it.copy(source = "vpn_${it.source}")
        }
        underlyingNetworkTransport(vpnCapabilities)?.let {
            return Underlay(it, "vpn_underlying_networks")
        }
        if (allowValidatedNetworkScan) {
            validatedPhysicalNetworkTransport()?.let {
                return Underlay(
                    if (it == "WIFI") {
                        "POSSIBLE_UNDERLAY_WIFI"
                    } else {
                        "POSSIBLE_UNDERLAY_$it"
                    },
                    "possible_validated_non_vpn_network_scan"
                )
            }
        }
        return Underlay(
            "UNDERLAY_UNKNOWN",
            if (allowValidatedNetworkScan) {
                "all_underlay_sources_unknown"
            } else {
                "network_callback_underlay_unknown"
            }
        )
    }

    private fun underlyingNetworkTransport(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT < 31) return null
        return runCatching {
            val accessor = caps.javaClass.methods.firstOrNull {
                it.name == "getUnderlyingNetworks" &&
                    it.parameterTypes.isEmpty()
            } ?: return null
            val networks = accessor.invoke(caps) as? List<*> ?: return null
            UnderlayTransportPolicy.preferred(
                networks.asSequence()
                .filterIsInstance<Network>()
                .mapNotNull { network ->
                    observedCapabilities[network.networkHandle]?.let { caps ->
                        physicalUnderlay(
                            caps,
                            observedLinkProperties[network.networkHandle]
                        )?.transport
                    }
                }
                .toSet()
            )
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun validatedPhysicalNetworkTransport(): String? {
        val transports = connectivity.allNetworks.mapNotNull { network ->
            val candidate = connectivity.getNetworkCapabilities(network)
            if (
                candidate == null ||
                candidate.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !candidate.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !candidate.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                null
            } else {
                physicalUnderlay(
                    candidate,
                    connectivity.getLinkProperties(network)
                )?.transport
            }
        }.distinct()
        return UnderlayTransportPolicy.preferred(transports.toSet())
    }

    private fun physicalUnderlay(
        caps: NetworkCapabilities,
        linkProperties: LinkProperties?
    ): Underlay? = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            Underlay("WIFI", "wifi_transport")
        VendorWifiTransportPolicy.isWifi(
            capabilitiesSummary = caps.toString(),
            interfaceName = linkProperties?.interfaceName
        ) ->
            Underlay(
                "WIFI",
                VendorWifiTransportPolicy.source(
                    capabilitiesSummary = caps.toString(),
                    interfaceName = linkProperties?.interfaceName
                )
            )
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
            Underlay("CELLULAR", "cellular_transport")
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
            Underlay("ETHERNET", "ethernet_transport")
        else -> null
    }

    @Synchronized
    private fun publish(now: NetworkEvidence) {
        if (!active.get()) return
        if (now == last) return
        val previous = last
        last = now
        LogManager.event(
            context,
            "network_evidence_changed",
            mapOf(
                "from" to previous?.toString(),
                "to" to now.toString(),
                "underlaySource" to now.underlaySource
            )
        )
        onChanged(now)
    }

    private data class Underlay(
        val transport: String,
        val source: String
    )
}

object UnderlayTransportPolicy {
    fun preferred(transports: Set<String>): String? = when {
        "WIFI" in transports -> "WIFI"
        "ETHERNET" in transports -> "ETHERNET"
        "CELLULAR" in transports -> "CELLULAR"
        else -> null
    }
}

object VendorWifiTransportPolicy {
    fun isWifi(
        capabilitiesSummary: String,
        interfaceName: String?
    ): Boolean =
        capabilitiesSummary.contains("EXTWIFI", ignoreCase = true) ||
            interfaceName.orEmpty().matches(
                Regex("^(wlan|wifi|ap)\\d+(?:[:.].*)?$", RegexOption.IGNORE_CASE)
            )

    fun source(
        capabilitiesSummary: String,
        interfaceName: String?
    ): String = when {
        capabilitiesSummary.contains("EXTWIFI", ignoreCase = true) ->
            "vivo_extwifi_capabilities"
        isWifi(capabilitiesSummary, interfaceName) ->
            "wifi_interface_${interfaceName.orEmpty()}"
        else -> "vendor_wifi_unknown"
    }
}
