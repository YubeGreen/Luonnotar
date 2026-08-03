package com.yubegreen.luonnotar.notification

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationServices
import java.io.File

enum class GmsBinderAnchorState {
    DISABLED,
    WAITING_FOR_GUARDIAN,
    WAITING_FOR_NOTIFICATION_LISTENER,
    GMS_UNAVAILABLE,
    CONNECTING,
    CONNECTED,
    SUSPENDED,
    FAILED,
    RETRY_EXHAUSTED
}

data class GmsBinderAnchorSnapshot(
    val enabled: Boolean,
    val state: GmsBinderAnchorState,
    val connectedSinceElapsed: Long,
    val lastEventElapsed: Long,
    val reconnectAttempt: Int,
    val suspensionCause: Int,
    val failureCode: Int,
    val gmsVersionCode: Long,
    val sessionGeneration: Long,
    val hostPid: Int,
    val bootId: String
)

object GmsBinderAnchorBackoffPolicy {
    /**
     * Keep retrying for the whole guardian lifetime.  The first failures recover
     * quickly; repeated failures are capped at one attempt every 15 minutes.
     */
    fun delayMs(attempt: Int): Long = when {
        attempt <= 1 -> 30_000L
        attempt == 2 -> 60_000L
        attempt == 3 -> 5 * 60_000L
        else -> 15 * 60_000L
    }
}

object GmsBinderAnchorSessionPolicy {
    fun acceptsCallback(
        started: Boolean,
        currentGeneration: Long,
        callbackGeneration: Long,
        sameSession: Boolean,
        sameClient: Boolean
    ): Boolean =
        started &&
            currentGeneration == callbackGeneration &&
            sameSession &&
            sameClient

    fun nextReconnectAttempt(
        currentAttempt: Int,
        reconnectRunnableExists: Boolean
    ): Int = if (reconnectRunnableExists) currentAttempt else currentAttempt + 1

    data class ConnectedReset(
        val reconnectAttempt: Int,
        val failureCode: Int,
        val suspensionCause: Int
    )

    fun connectedReset() = ConnectedReset(0, 0, 0)
}

object GmsBinderAnchorStatePolicy {
    fun shouldConnect(enabled: Boolean, guardianActive: Boolean): Boolean =
        enabled && guardianActive
}

@Suppress("DEPRECATION")
class GmsBinderAnchor(
    private val applicationContext: Context,
    private val onSnapshotChanged: (GmsBinderAnchorSnapshot) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var client: GoogleApiClient? = null
    private var activeSession: ClientSession? = null
    private var sessionGeneration = 0L
    private var reconnectAttempt = 0
    private var connectedSinceElapsed = 0L
    private var reconnectRunnable: Runnable? = null
    private var started = false
    private var state = GmsBinderAnchorState.DISABLED
    private var lastEventElapsed = 0L
    private var suspensionCause = 0
    private var failureCode = 0

    private inner class ClientSession(
        val generation: Long
    ) : GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {
        lateinit var client: GoogleApiClient

        override fun onConnected(bundle: android.os.Bundle?) {
            if (!isCurrentSession(this)) return
            removeReconnect()
            val reset = GmsBinderAnchorSessionPolicy.connectedReset()
            reconnectAttempt = reset.reconnectAttempt
            failureCode = reset.failureCode
            suspensionCause = reset.suspensionCause
            connectedSinceElapsed = SystemClock.elapsedRealtime()
            state = GmsBinderAnchorState.CONNECTED
            lastEventElapsed = connectedSinceElapsed
            emit()
        }

        override fun onConnectionSuspended(cause: Int) {
            if (!isCurrentSession(this)) return
            suspensionCause = cause
            failureCode = 0
            connectedSinceElapsed = 0L
            state = GmsBinderAnchorState.SUSPENDED
            lastEventElapsed = SystemClock.elapsedRealtime()
            emit()
            scheduleReconnect()
        }

        override fun onConnectionFailed(result: ConnectionResult) {
            if (!isCurrentSession(this)) return
            failureCode = result.errorCode
            suspensionCause = 0
            connectedSinceElapsed = 0L
            state = GmsBinderAnchorState.FAILED
            lastEventElapsed = SystemClock.elapsedRealtime()
            emit()
            scheduleReconnect()
        }
    }

    private fun isCurrentSession(session: ClientSession): Boolean =
        GmsBinderAnchorSessionPolicy.acceptsCallback(
            started = started,
            currentGeneration = sessionGeneration,
            callbackGeneration = session.generation,
            sameSession = activeSession === session,
            sameClient = client === session.client
        )

    fun start() = runOnMain {
        if (started) {
            healthCheckOnMain()
            return@runOnMain
        }
        started = true
        sessionGeneration++
        reconnectAttempt = 0
        removeReconnect()
        connectCurrentGeneration()
    }

    fun stop(reason: String = "stop") = runOnMain {
        started = false
        removeReconnect()
        invalidateCurrentClient()
        reconnectAttempt = 0
        connectedSinceElapsed = 0L
        suspensionCause = 0
        failureCode = 0
        state = GmsBinderAnchorState.DISABLED
        lastEventElapsed = SystemClock.elapsedRealtime()
        emit()
    }

    fun manualRetry() = runOnMain {
        if (!started) return@runOnMain
        removeReconnect()
        invalidateCurrentClient()
        reconnectAttempt = 0
        connectCurrentGeneration()
    }

    fun healthCheck() = runOnMain {
        healthCheckOnMain()
    }

    private fun healthCheckOnMain() {
        if (!started || reconnectRunnable != null) return
        val current = client
        if (current?.isConnected == true || current?.isConnecting == true) return
        invalidateCurrentClient()
        connectCurrentGeneration()
    }

    private fun connectCurrentGeneration() {
        if (!started || client?.isConnected == true || client?.isConnecting == true) return
        val availability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(applicationContext)
        if (availability != ConnectionResult.SUCCESS) {
            connectedSinceElapsed = 0L
            suspensionCause = 0
            failureCode = availability
            state = GmsBinderAnchorState.GMS_UNAVAILABLE
            lastEventElapsed = SystemClock.elapsedRealtime()
            emit()
            scheduleReconnect()
            return
        }
        sessionGeneration++
        connectedSinceElapsed = 0L
        failureCode = 0
        suspensionCause = 0
        state = GmsBinderAnchorState.CONNECTING
        lastEventElapsed = SystemClock.elapsedRealtime()
        emit()
        val session = ClientSession(sessionGeneration)
        val builtClient = GoogleApiClient.Builder(applicationContext)
            .addApi(LocationServices.API)
            .addConnectionCallbacks(session)
            .addOnConnectionFailedListener(session)
            .build()
        session.client = builtClient
        activeSession = session
        client = builtClient
        runCatching { builtClient.connect() }.onFailure {
            if (!isCurrentSession(session)) return@onFailure
            invalidateCurrentClient()
            failureCode = ConnectionResult.INTERNAL_ERROR
            state = GmsBinderAnchorState.FAILED
            lastEventElapsed = SystemClock.elapsedRealtime()
            emit()
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!started || reconnectRunnable != null) return
        val nextAttempt = GmsBinderAnchorSessionPolicy.nextReconnectAttempt(
            reconnectAttempt,
            reconnectRunnableExists = false
        )
        val delay = GmsBinderAnchorBackoffPolicy.delayMs(nextAttempt)
        reconnectAttempt = nextAttempt
        val generation = sessionGeneration
        reconnectRunnable = Runnable {
            if (!started || generation != sessionGeneration) return@Runnable
            reconnectRunnable = null
            invalidateCurrentClient()
            connectCurrentGeneration()
        }.also { handler.postDelayed(it, delay) }
        emit()
    }

    private fun invalidateCurrentClient() {
        val oldClient = client
        activeSession = null
        client = null
        sessionGeneration++
        runCatching { oldClient?.disconnect() }
    }

    private fun removeReconnect() {
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun emit() {
        val currentState = state
        onSnapshotChanged(
            GmsBinderAnchorSnapshot(
                enabled = started,
                state = currentState,
                connectedSinceElapsed = connectedSinceElapsed,
                lastEventElapsed = lastEventElapsed,
                reconnectAttempt = reconnectAttempt,
                suspensionCause = suspensionCause,
                failureCode = failureCode,
                gmsVersionCode = gmsVersionCode(),
                sessionGeneration = sessionGeneration,
                hostPid = if (
                    currentState == GmsBinderAnchorState.WAITING_FOR_GUARDIAN ||
                    currentState == GmsBinderAnchorState.WAITING_FOR_NOTIFICATION_LISTENER ||
                    currentState == GmsBinderAnchorState.DISABLED
                ) 0 else Process.myPid(),
                bootId = currentBootId()
            )
        )
    }

    private fun gmsVersionCode(): Long = runCatching {
        val info = applicationContext.packageManager
            .getPackageInfo("com.google.android.gms", 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(0L)

    private fun currentBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unavailable")

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
}
