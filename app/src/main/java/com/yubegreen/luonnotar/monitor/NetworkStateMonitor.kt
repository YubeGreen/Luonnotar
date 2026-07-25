package com.yubegreen.luonnotar.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import com.yubegreen.luonnotar.util.LogManager
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile
    private var last: NetworkEvidence? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (!active.get()) return
            publish(evidenceFromCallback(network, capabilities))
        }

        override fun onLost(network: Network) {
            if (!active.get()) return
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
        publish(current())
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

    fun current(): NetworkEvidence {
        val active = connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        val defaultIsVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val underlay = if (caps == null) {
            Underlay("NONE", "no_active_network")
        } else if (!defaultIsVpn) {
            Underlay(
                physicalTransport(caps) ?: "OTHER",
                "default_network_capabilities"
            )
        } else {
            resolveVpnUnderlay(caps, allowValidatedNetworkScan = true)
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
            resolveVpnUnderlay(caps, allowValidatedNetworkScan = false)
        } else {
            Underlay(
                physicalTransport(caps) ?: "OTHER",
                "network_callback_capabilities"
            )
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
        allowValidatedNetworkScan: Boolean
    ): Underlay {
        physicalTransport(vpnCapabilities)?.let {
            return Underlay(it, "vpn_capabilities_transport")
        }
        underlyingNetworkTransport(vpnCapabilities)?.let {
            return Underlay(it, "vpn_underlying_networks")
        }
        if (allowValidatedNetworkScan) {
            validatedPhysicalNetworkTransport()?.let {
                return Underlay(it, "validated_non_vpn_network_scan")
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
                .mapNotNull { connectivity.getNetworkCapabilities(it) }
                .mapNotNull(::physicalTransport)
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
                physicalTransport(candidate)
            }
        }.distinct()
        return UnderlayTransportPolicy.preferred(transports.toSet())
    }

    private fun physicalTransport(caps: NetworkCapabilities): String? = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
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
