package com.yubegreen.luonnotar.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import com.yubegreen.luonnotar.util.LogManager

data class NetworkEvidence(
    val connected: Boolean,
    val validated: Boolean,
    val transport: String,
    val wifiUnderlying: Boolean,
    val networkHandle: Long = -1L
)

class NetworkStateMonitor(
    private val context: Context,
    private val onChanged: (NetworkEvidence) -> Unit
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val callbackThread = HandlerThread("luonnotar-network").apply { start() }
    private val handler = Handler(callbackThread.looper)
    private val refreshUnderlay = Runnable { publish(current()) }
    @Volatile
    private var last: NetworkEvidence? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            publish(evidenceFromCallback(network, capabilities))
        }

        override fun onLost(network: Network) {
            if (last?.networkHandle == network.networkHandle) {
                publish(NetworkEvidence(false, false, "NONE", false, -1L))
            }
        }
    }

    fun start() {
        connectivity.registerDefaultNetworkCallback(callback, handler)
        publish(current())
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        handler.removeCallbacksAndMessages(null)
        callbackThread.quitSafely()
    }

    fun current(): NetworkEvidence {
        val active = connectivity.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        val defaultTransport = when {
            caps == null -> "NONE"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
        val defaultIsVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val physicalTransports = connectivity.allNetworks.mapNotNull { network ->
            val candidate = connectivity.getNetworkCapabilities(network)
            if (
                candidate == null ||
                candidate.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !candidate.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !candidate.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                null
            } else {
                when {
                    candidate.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    candidate.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    candidate.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    else -> null
                }
            }
        }.distinct()
        val underlay = if (!defaultIsVpn) {
            defaultTransport
        } else {
            when {
                "WIFI" in physicalTransports -> "WIFI"
                "ETHERNET" in physicalTransports -> "ETHERNET"
                "CELLULAR" in physicalTransports -> "CELLULAR"
                else -> "UNDERLAY_UNKNOWN"
            }
        }
        return NetworkEvidence(
            connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            transport = underlay,
            wifiUnderlying = underlay == "WIFI",
            networkHandle = active?.networkHandle ?: -1L
        )
    }

    private fun evidenceFromCallback(
        network: Network,
        caps: NetworkCapabilities
    ): NetworkEvidence {
        val defaultTransport = physicalTransport(caps) ?: when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
        val underlay = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            physicalTransport(caps)
                ?: last?.transport?.takeIf {
                    it == "WIFI" || it == "CELLULAR" || it == "ETHERNET"
                }
                ?: "UNDERLAY_UNKNOWN"
        } else {
            defaultTransport
        }
        if (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            physicalTransport(caps) == null
        ) {
            handler.removeCallbacks(refreshUnderlay)
            handler.postDelayed(refreshUnderlay, 750L)
        }
        return NetworkEvidence(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = underlay,
            wifiUnderlying = underlay == "WIFI",
            networkHandle = network.networkHandle
        )
    }

    private fun physicalTransport(caps: NetworkCapabilities): String? = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        else -> null
    }

    @Synchronized
    private fun publish(now: NetworkEvidence) {
        if (now == last) return
        val previous = last
        last = now
        LogManager.event(
            context,
            "network_evidence_changed",
            mapOf("from" to previous?.toString(), "to" to now.toString())
        )
        onChanged(now)
    }
}
