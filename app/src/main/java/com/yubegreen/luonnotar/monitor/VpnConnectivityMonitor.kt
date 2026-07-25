package com.yubegreen.luonnotar.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.RequiresApi
import com.yubegreen.luonnotar.util.LogManager

data class VpnEvidence(
    val present: Boolean,
    val validated: Boolean,
    val bypassable: Boolean?,
    val networkHandle: Long
)

class VpnConnectivityMonitor(
    private val context: Context,
    private val onChanged: (VpnEvidence) -> Unit
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile
    private var last: VpnEvidence? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            publish(evidence(network, capabilities))
        }

        override fun onLost(network: Network) {
            if (last?.networkHandle == network.networkHandle) {
                publish(VpnEvidence(false, false, null, -1L))
            }
        }
    }

    fun start() {
        connectivity.registerDefaultNetworkCallback(callback)
        publish(current())
    }

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    fun current(): VpnEvidence {
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        return evidence(network, caps)
    }

    private fun evidence(
        network: Network?,
        caps: NetworkCapabilities?
    ): VpnEvidence {
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val bypassable = if (vpn && Build.VERSION.SDK_INT >= 29) {
            readBypassableCompat(caps)
        } else null
        return VpnEvidence(
            present = vpn,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            bypassable = bypassable,
            networkHandle = network?.networkHandle ?: -1
        )
    }

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
