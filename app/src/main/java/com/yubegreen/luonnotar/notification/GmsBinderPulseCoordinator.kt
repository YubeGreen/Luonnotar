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
import com.google.android.gms.location.LocationSettingsRequest
import com.yubegreen.luonnotar.util.LogManager
import kotlin.math.max

/**
 * Repeatedly creates a public GMS location Binder connection, performs a
 * read-only location-settings query, and disconnects.
 *
 * The short LAB_TEST mode remains a manual diagnostic. The bounded
 * STABILIZATION_LEASE mode is also started before MCS exists and is refreshed
 * across GMS PID replacements. This keeps public Binder traffic flowing during
 * both connection bootstrap and the post-recovery refreeze window.
 */
@Suppress("DEPRECATION")
object GmsBinderPulseCoordinator {
    const val TEST_DURATION_MS = 15_000L
    const val STABILIZATION_DURATION_MS = 120_000L
    const val STABILIZATION_MAX_TOTAL_MS = 4 * 60_000L
    const val PULSE_INTERVAL_MS = 2_000L
    const val CONNECTED_HOLD_MS = 750L
    const val STABILIZATION_CONNECTED_HOLD_MS = 1_500L
    private const val CONNECT_TIMEOUT_MS = 1_600L

    private enum class PulseMode {
        LAB_TEST,
        STABILIZATION_LEASE
    }

    private data class PulseSpec(
        val mode: PulseMode,
        val reason: String,
        val durationMs: Long,
        val intervalMs: Long,
        val connectedHoldMs: Long
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var nextRequestId = 0L
    private var pendingRequestId = 0L
    private var activeRun: PulseRun? = null

    fun start(context: Context): Boolean =
        requestStart(
            context.applicationContext,
            PulseSpec(
                mode = PulseMode.LAB_TEST,
                reason = "manual_test",
                durationMs = TEST_DURATION_MS,
                intervalMs = PULSE_INTERVAL_MS,
                connectedHoldMs = CONNECTED_HOLD_MS
            )
        )

    fun startStabilization(context: Context, reason: String): Boolean =
        requestStart(
            context.applicationContext,
            PulseSpec(
                mode = PulseMode.STABILIZATION_LEASE,
                reason = reason,
                durationMs = STABILIZATION_DURATION_MS,
                intervalMs = PULSE_INTERVAL_MS,
                connectedHoldMs = STABILIZATION_CONNECTED_HOLD_MS
            )
        )

    fun stop(context: Context, reason: String) = runOnMain {
        val run = synchronized(stateLock) {
            pendingRequestId = 0L
            activeRun.also { activeRun = null }
        }
        run?.stop(reason)
        if (run != null) {
            LogManager.event(
                context.applicationContext,
                "gms_binder_pulse_coordinator_stopped",
                mapOf(
                    "reason" to reason,
                    "mode" to run.modeName
                )
            )
        }
    }

    private fun requestStart(context: Context, spec: PulseSpec): Boolean {
        var runToReplace: PulseRun? = null
        var runToExtend: PulseRun? = null
        var rejected = false
        var requestId = 0L

        synchronized(stateLock) {
            val active = activeRun
            if (spec.mode == PulseMode.STABILIZATION_LEASE) {
                if (active?.modeName == PulseMode.STABILIZATION_LEASE.name) {
                    runToExtend = active
                } else {
                    // A short diagnostic pulse must never block the recovery
                    // lease. Replace it before claiming the long-lived run.
                    pendingRequestId = 0L
                    if (active != null) {
                        runToReplace = active
                        activeRun = null
                    }
                    nextRequestId += 1L
                    pendingRequestId = nextRequestId
                    requestId = nextRequestId
                }
            } else if (pendingRequestId != 0L || active != null) {
                rejected = true
            } else {
                nextRequestId += 1L
                pendingRequestId = nextRequestId
                requestId = nextRequestId
            }
        }

        if (rejected) {
            LogManager.event(
                context,
                "gms_binder_pulse_rejected",
                mapOf(
                    "reason" to "already_running_or_queued",
                    "requestedMode" to spec.mode.name,
                    "requestedReason" to spec.reason
                )
            )
            return false
        }

        runToExtend?.let { existing ->
            runOnMain {
                existing.extend(spec.reason, spec.durationMs)
            }
            return true
        }

        val startRunnable = Runnable {
            runToReplace?.stop("upgraded_to_stabilization")
            startClaimed(context, spec, requestId)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            startRunnable.run()
        } else {
            mainHandler.post(startRunnable)
        }
        return true
    }

    private fun startClaimed(
        context: Context,
        spec: PulseSpec,
        requestId: Long
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(stateLock) {
            if (pendingRequestId != requestId) return
            pendingRequestId = 0L
            if (activeRun != null) return
        }

        lateinit var created: PulseRun
        created = PulseRun(context, spec) {
            synchronized(stateLock) {
                if (activeRun === created) activeRun = null
            }
        }
        synchronized(stateLock) {
            if (activeRun != null) return
            activeRun = created
        }
        if (!created.start()) {
            synchronized(stateLock) {
                if (activeRun === created) activeRun = null
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private class PulseRun(
        private val context: Context,
        private val spec: PulseSpec,
        private val onFinished: () -> Unit
    ) {
        val modeName: String
            get() = spec.mode.name

        private val handler = Handler(Looper.getMainLooper())
        private var running = false
        private var generation = 0L
        private var startedElapsed = 0L
        private var deadlineElapsed = 0L
        private var pulseCount = 0
        private var connectedCount = 0
        private var queryResultCount = 0
        private var activeClient: GoogleApiClient? = null
        private var nextPulseRunnable: Runnable? = null
        private var cycleRunnable: Runnable? = null

        fun start(): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (running) return false

            val availability = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context)
            if (availability != ConnectionResult.SUCCESS) {
                LogManager.event(
                    context,
                    "gms_binder_pulse_unavailable",
                    mapOf(
                        "failureCode" to availability,
                        "mode" to spec.mode.name,
                        "requestReason" to spec.reason
                    )
                )
                return false
            }

            running = true
            generation++
            startedElapsed = SystemClock.elapsedRealtime()
            deadlineElapsed = startedElapsed + spec.durationMs
            pulseCount = 0
            connectedCount = 0
            queryResultCount = 0
            LogManager.event(
                context,
                "gms_binder_pulse_started",
                mapOf(
                    "mode" to spec.mode.name,
                    "requestReason" to spec.reason,
                    "durationMs" to spec.durationMs,
                    "intervalMs" to spec.intervalMs,
                    "connectedHoldMs" to spec.connectedHoldMs,
                    "hostPid" to Process.myPid()
                )
            )
            runPulse(generation)
            return true
        }

        fun stop(reason: String) {
            if (!running) return
            finish(reason, completed = false)
        }

        fun extend(reason: String, durationMs: Long) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!running || spec.mode != PulseMode.STABILIZATION_LEASE) return
            val now = SystemClock.elapsedRealtime()
            val previousDeadline = deadlineElapsed
            val hardDeadline = startedElapsed + STABILIZATION_MAX_TOTAL_MS
            deadlineElapsed = minOf(
                hardDeadline,
                maxOf(previousDeadline, now + durationMs.coerceAtLeast(0L))
            )
            LogManager.event(
                context,
                "gms_binder_stabilization_lease_extended",
                mapOf(
                    "requestReason" to reason,
                    "previousDeadlineElapsed" to previousDeadline,
                    "deadlineElapsed" to deadlineElapsed,
                    "remainingMs" to (deadlineElapsed - now).coerceAtLeast(0L),
                    "hardDeadlineElapsed" to hardDeadline,
                    "hostPid" to Process.myPid()
                )
            )
        }

        private fun runPulse(runGeneration: Long) {
            if (!isCurrent(runGeneration)) return
            val pulseStartedElapsed = SystemClock.elapsedRealtime()
            if (pulseStartedElapsed >= deadlineElapsed) {
                finish("duration_complete", completed = true)
                return
            }

            clearCycleRunnable()
            disconnectActiveClient("next_pulse")
            pulseCount++
            val pulseNumber = pulseCount

            lateinit var builtClient: GoogleApiClient
            val callbacks = object : GoogleApiClient.ConnectionCallbacks,
                GoogleApiClient.OnConnectionFailedListener {
                override fun onConnected(bundle: android.os.Bundle?) {
                    if (!isActiveClient(runGeneration, builtClient)) return
                    connectedCount++
                    LogManager.event(
                        context,
                        "gms_binder_pulse_connected",
                        mapOf(
                            "mode" to spec.mode.name,
                            "requestReason" to spec.reason,
                            "pulse" to pulseNumber,
                            "connectLatencyMs" to
                                (SystemClock.elapsedRealtime() - pulseStartedElapsed)
                        )
                    )

                    runCatching {
                        val request = LocationSettingsRequest.Builder().build()
                        LocationServices.SettingsApi
                            .checkLocationSettings(builtClient, request)
                            .setResultCallback { result ->
                                if (!isCurrent(runGeneration)) {
                                    return@setResultCallback
                                }
                                queryResultCount++
                                LogManager.event(
                                    context,
                                    "gms_binder_pulse_query_result",
                                    mapOf(
                                        "mode" to spec.mode.name,
                                        "requestReason" to spec.reason,
                                        "pulse" to pulseNumber,
                                        "statusCode" to result.status.statusCode
                                    )
                                )
                            }
                    }.onFailure {
                        LogManager.event(
                            context,
                            "gms_binder_pulse_query_failed",
                            mapOf(
                                "mode" to spec.mode.name,
                                "requestReason" to spec.reason,
                                "pulse" to pulseNumber,
                                "error" to it.javaClass.simpleName
                            )
                        )
                    }

                    scheduleCycleEnd(
                        runGeneration,
                        builtClient,
                        pulseStartedElapsed,
                        pulseNumber,
                        spec.connectedHoldMs,
                        "connected_hold_complete"
                    )
                }

                override fun onConnectionSuspended(cause: Int) {
                    if (!isActiveClient(runGeneration, builtClient)) return
                    LogManager.event(
                        context,
                        "gms_binder_pulse_suspended",
                        mapOf(
                            "mode" to spec.mode.name,
                            "requestReason" to spec.reason,
                            "pulse" to pulseNumber,
                            "cause" to cause
                        )
                    )
                    scheduleCycleEnd(
                        runGeneration,
                        builtClient,
                        pulseStartedElapsed,
                        pulseNumber,
                        0L,
                        "connection_suspended"
                    )
                }

                override fun onConnectionFailed(result: ConnectionResult) {
                    if (!isActiveClient(runGeneration, builtClient)) return
                    LogManager.event(
                        context,
                        "gms_binder_pulse_connection_failed",
                        mapOf(
                            "mode" to spec.mode.name,
                            "requestReason" to spec.reason,
                            "pulse" to pulseNumber,
                            "failureCode" to result.errorCode
                        )
                    )
                    scheduleCycleEnd(
                        runGeneration,
                        builtClient,
                        pulseStartedElapsed,
                        pulseNumber,
                        0L,
                        "connection_failed"
                    )
                }
            }

            builtClient = GoogleApiClient.Builder(context)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(callbacks)
                .addOnConnectionFailedListener(callbacks)
                .build()
            activeClient = builtClient

            LogManager.event(
                context,
                "gms_binder_pulse_attempt",
                mapOf(
                    "mode" to spec.mode.name,
                    "requestReason" to spec.reason,
                    "pulse" to pulseNumber
                )
            )

            runCatching { builtClient.connect() }.onFailure {
                LogManager.event(
                    context,
                    "gms_binder_pulse_connect_threw",
                    mapOf(
                        "mode" to spec.mode.name,
                        "requestReason" to spec.reason,
                        "pulse" to pulseNumber,
                        "error" to it.javaClass.simpleName
                    )
                )
                scheduleCycleEnd(
                    runGeneration,
                    builtClient,
                    pulseStartedElapsed,
                    pulseNumber,
                    0L,
                    "connect_threw"
                )
            }

            if (isActiveClient(runGeneration, builtClient)) {
                scheduleCycleEnd(
                    runGeneration,
                    builtClient,
                    pulseStartedElapsed,
                    pulseNumber,
                    CONNECT_TIMEOUT_MS,
                    "connect_timeout"
                )
            }
        }

        private fun scheduleCycleEnd(
            runGeneration: Long,
            expectedClient: GoogleApiClient,
            pulseStartedElapsed: Long,
            pulseNumber: Int,
            delayMs: Long,
            reason: String
        ) {
            if (!isActiveClient(runGeneration, expectedClient)) return
            clearCycleRunnable()
            cycleRunnable = Runnable {
                cycleRunnable = null
                if (!isActiveClient(runGeneration, expectedClient)) {
                    return@Runnable
                }
                disconnectActiveClient(reason)
                LogManager.event(
                    context,
                    "gms_binder_pulse_disconnected",
                    mapOf(
                        "mode" to spec.mode.name,
                        "requestReason" to spec.reason,
                        "pulse" to pulseNumber,
                        "reason" to reason
                    )
                )
                scheduleNextPulse(runGeneration, pulseStartedElapsed)
            }.also { handler.postDelayed(it, delayMs) }
        }

        private fun scheduleNextPulse(
            runGeneration: Long,
            pulseStartedElapsed: Long
        ) {
            if (!isCurrent(runGeneration)) return
            val now = SystemClock.elapsedRealtime()
            if (now >= deadlineElapsed) {
                finish("duration_complete", completed = true)
                return
            }
            val targetElapsed = pulseStartedElapsed + spec.intervalMs
            val delay = max(0L, targetElapsed - now)
            nextPulseRunnable = Runnable {
                nextPulseRunnable = null
                runPulse(runGeneration)
            }.also { handler.postDelayed(it, delay) }
        }

        private fun finish(reason: String, completed: Boolean) {
            if (!running) return
            running = false
            generation++
            nextPulseRunnable?.let(handler::removeCallbacks)
            nextPulseRunnable = null
            clearCycleRunnable()
            disconnectActiveClient("run_finished")
            val elapsed = SystemClock.elapsedRealtime() - startedElapsed
            LogManager.event(
                context,
                "gms_binder_pulse_finished",
                mapOf(
                    "mode" to spec.mode.name,
                    "requestReason" to spec.reason,
                    "reason" to reason,
                    "completed" to completed,
                    "elapsedMs" to elapsed,
                    "pulseCount" to pulseCount,
                    "connectedCount" to connectedCount,
                    "queryResultCount" to queryResultCount,
                    "hostPid" to Process.myPid()
                )
            )
            onFinished()
        }

        private fun disconnectActiveClient(reason: String) {
            val oldClient = activeClient
            activeClient = null
            runCatching { oldClient?.disconnect() }.onFailure {
                LogManager.event(
                    context,
                    "gms_binder_pulse_disconnect_failed",
                    mapOf(
                        "mode" to spec.mode.name,
                        "requestReason" to spec.reason,
                        "reason" to reason,
                        "error" to it.javaClass.simpleName
                    )
                )
            }
        }

        private fun clearCycleRunnable() {
            cycleRunnable?.let(handler::removeCallbacks)
            cycleRunnable = null
        }

        private fun isCurrent(runGeneration: Long): Boolean =
            running && generation == runGeneration

        private fun isActiveClient(
            runGeneration: Long,
            expectedClient: GoogleApiClient
        ): Boolean =
            isCurrent(runGeneration) && activeClient === expectedClient
    }
}
