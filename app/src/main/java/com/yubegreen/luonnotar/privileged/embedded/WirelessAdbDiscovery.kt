package com.yubegreen.luonnotar.privileged.embedded

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.concurrent.atomic.AtomicBoolean

internal class WirelessAdbDiscovery(
    context: Context,
    private val onPairingPort: (Int) -> Unit,
    private val onConnectPort: (Int) -> Unit,
    private val onError: (String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val started = AtomicBoolean(false)
    private val sessions = mutableListOf<DiscoverySession>()
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        multicastLock = appContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("luonnotar-wireless-adb")
            ?.apply { setReferenceCounted(false); acquire() }
        sessions += DiscoverySession(PAIRING_TYPE, onPairingPort)
        sessions += DiscoverySession(CONNECT_TYPE, onConnectPort)
        sessions.forEach(DiscoverySession::start)
    }

    override fun close() {
        if (!started.compareAndSet(true, false)) return
        sessions.forEach(DiscoverySession::stop)
        sessions.clear()
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private inner class DiscoverySession(
        private val type: String,
        private val onPort: (Int) -> Unit
    ) : NsdManager.DiscoveryListener {
        private val active = AtomicBoolean(false)

        fun start() {
            if (!active.compareAndSet(false, true)) return
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, this) }
                .onFailure { active.set(false); onError("mDNS $type: ${it.message}") }
        }

        fun stop() {
            if (!active.compareAndSet(true, false)) return
            runCatching { nsd.stopServiceDiscovery(this) }
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            active.set(false)
            onError("mDNS start failed: $serviceType/$errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            onError("mDNS stop failed: $serviceType/$errorCode")
        }
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceType.startsWith(type.removeSuffix("."))) return
            @Suppress("DEPRECATION")
            runCatching {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        onError("mDNS resolve failed: ${serviceInfo.serviceName}/$errorCode")
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        serviceInfo.port.takeIf { it in 1..65535 }?.let(onPort)
                    }
                })
            }.onFailure { onError("mDNS resolve: ${it.message}") }
        }
    }

    companion object {
        private const val PAIRING_TYPE = "_adb-tls-pairing._tcp."
        private const val CONNECT_TYPE = "_adb-tls-connect._tcp."
    }
}
