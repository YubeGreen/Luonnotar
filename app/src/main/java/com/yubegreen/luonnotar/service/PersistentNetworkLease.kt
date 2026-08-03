package com.yubegreen.luonnotar.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences

/** Holds a process-lifetime VPN NetworkRequest as an OriginOS experiment. */
class PersistentNetworkLease(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var activeNetworkHandle = -1L

    @Synchronized
    fun reconcile(enabled: Boolean) {
        if (!enabled) {
            release("disabled")
            return
        }
        if (callback != null) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val newCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                activeNetworkHandle = network.networkHandle
                persist("AVAILABLE", network.networkHandle)
                LogManager.timeline(
                    appContext,
                    "persistent_network_lease_available",
                    mapOf("networkHandle" to network.networkHandle)
                )
            }

            override fun onLost(network: Network) {
                if (activeNetworkHandle != network.networkHandle) {
                    LogManager.timeline(
                        appContext,
                        "persistent_network_lease_nonactive_lost",
                        mapOf(
                            "lostNetworkHandle" to network.networkHandle,
                            "activeNetworkHandle" to activeNetworkHandle
                        )
                    )
                    return
                }
                activeNetworkHandle = -1L
                persist("LOST", -1L)
                LogManager.timeline(
                    appContext,
                    "persistent_network_lease_lost",
                    mapOf("networkHandle" to network.networkHandle)
                )
            }

            override fun onUnavailable() {
                activeNetworkHandle = -1L
                persist("UNAVAILABLE", -1L)
                LogManager.timeline(appContext, "persistent_network_lease_unavailable")
            }
        }

        callback = newCallback
        persist("REQUESTED", -1L)
        val registered = runCatching {
            connectivity.requestNetwork(request, newCallback, mainHandler)
            true
        }.getOrElse { error ->
            callback = null
            activeNetworkHandle = -1L
            persist("FAILED:${error.javaClass.simpleName}", -1L)
            LogManager.event(
                appContext,
                "persistent_network_lease_request_failed",
                mapOf("error" to error.javaClass.simpleName)
            )
            false
        }
        if (!registered) return
    }

    @Synchronized
    fun release(reason: String) {
        val current = callback
        if (current == null) {
            val prefs = LuonnotarPreferences.deviceProtected(appContext)
            if (
                prefs.getString(
                    LuonnotarPreferences.KEY_PERSISTENT_NETWORK_LEASE_STATE,
                    "STOPPED"
                ) != "RELEASED"
            ) {
                persist("RELEASED", -1L)
            }
            return
        }
        callback = null
        runCatching { connectivity.unregisterNetworkCallback(current) }
        activeNetworkHandle = -1L
        persist("RELEASED", -1L)
        LogManager.timeline(
            appContext,
            "persistent_network_lease_released",
            mapOf("reason" to reason)
        )
    }

    private fun persist(state: String, handle: Long) {
        LuonnotarPreferences.deviceProtected(appContext).edit()
            .putString(LuonnotarPreferences.KEY_PERSISTENT_NETWORK_LEASE_STATE, state)
            .putLong(LuonnotarPreferences.KEY_PERSISTENT_NETWORK_LEASE_HANDLE, handle)
            .putLong(
                LuonnotarPreferences.KEY_PERSISTENT_NETWORK_LEASE_LAST_EVENT_ELAPSED,
                SystemClock.elapsedRealtime()
            )
            .apply()
    }
}
