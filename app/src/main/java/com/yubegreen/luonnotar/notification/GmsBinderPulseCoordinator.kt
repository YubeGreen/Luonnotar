package com.yubegreen.luonnotar.notification

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
 * Public-GMS Binder activity used by the manual laboratory probe and bounded
 * recovery leases.
 *
 * LAB_TEST intentionally keeps the historical connect/query/disconnect pulse.
 * STABILIZATION_LEASE is different: it owns one GoogleApiClient for the whole
 * lease, continuously keeps that Binder connected, issues a read-only query on
 * the same client, and reconnects rapidly only after an actual suspension or
 * failure. Repeated lease requests extend the deadline without replacing or
 * disconnecting a healthy client.
 */
@Suppress("DEPRECATION")
object GmsBinderPulseCoordinator {
    const val TEST_DURATION_MS = 15_000L
    const val STABILIZATION_DURATION_MS = 120_000L
    const val STABILIZATION_MAX_TOTAL_MS = 4 * 60_000L
    const val PULSE_INTERVAL_MS = 2_000L
    const val CONNECTED_HOLD_MS = 750L
    const val STABILIZATION_QUERY_INTERVAL_MS = 750L
    const val STABILIZATION_RECONNECT_DELAY_MS = 250L
    private const val LAB_CONNECT_TIMEOUT_MS = 1_600L
    private const val STABILIZATION_CONNECT_TIMEOUT_MS = 3_000L
    private const val STABILIZATION_HEALTH_LOG_INTERVAL_MS = 10_000L
    private const val WAKE_LOCK_TAG = "Luonnotar:GmsBinderStabilization"

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
                intervalMs = STABILIZATION_QUERY_INTERVAL_MS,
                connectedHoldMs = Long.MAX_VALUE
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
            runOnMain { existing.extend(spec.reason, spec.durationMs) }
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
        private var queryAttemptCount = 0
        private var reconnectCount = 0
        private var activeClient: GoogleApiClient? = null
        private var nextPulseRunnable: Runnable? = null
        private var cycleRunnable: Runnable? = null
        private var queryRunnable: Runnable? = null
        private var reconnectRunnable: Runnable? = null
        private var connectTimeoutRunnable: Runnable? = null
        private var deadlineRunnable: Runnable? = null
        private var connectedSinceElapsed = 0L
        private var totalConnectedMs = 0L
        private var longestConnectedMs = 0L
        private var lastHealthLogElapsed = 0L
        private var wakeLock: PowerManager.WakeLock? = null

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
            queryAttemptCount = 0
            reconnectCount = 0
            connectedSinceElapsed = 0L
            totalConnectedMs = 0L
            longestConnectedMs = 0L
            lastHealthLogElapsed = 0L

            if (spec.mode == PulseMode.STABILIZATION_LEASE) {
                acquireWakeLock()
                LogManager.event(
                    context,
                    "gms_binder_stabilization_anchor_started",
                    stabilizationMetrics(
                        extra = mapOf(
                            "requestReason" to spec.reason,
                            "durationMs" to spec.durationMs,
                            "queryIntervalMs" to spec.intervalMs,
                            "reconnectDelayMs" to STABILIZATION_RECONNECT_DELAY_MS
                        )
                    )
                )
                scheduleDeadline(generation)
                connectStabilizationClient(generation, "initial")
            } else {
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
                runLabPulse(generation)
            }
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
            scheduleDeadline(generation)
            val currentClient = activeClient
            if (currentClient?.isConnected == true) {
                // A recovery-side lease refresh after a verified thaw doubles as
                // an immediate read-only Binder poke. Cancel the pending timer
                // and query now, then resume the normal 750 ms cadence.
                clearQueryRunnable()
                runStabilizationQuery(generation, currentClient)
            } else if (
                currentClient?.isConnecting != true &&
                reconnectRunnable == null
            ) {
                scheduleStabilizationReconnect(generation, "lease_extended_disconnected", 0L)
            }
            LogManager.event(
                context,
                "gms_binder_stabilization_lease_extended",
                stabilizationMetrics(
                    extra = mapOf(
                        "requestReason" to reason,
                        "previousDeadlineElapsed" to previousDeadline,
                        "deadlineElapsed" to deadlineElapsed,
                        "remainingMs" to (deadlineElapsed - now).coerceAtLeast(0L),
                        "hardDeadlineElapsed" to hardDeadline
                    )
                )
            )
        }

        private fun runLabPulse(runGeneration: Long) {
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
                    performReadOnlyQuery(runGeneration, builtClient, pulseNumber, labMode = true)
                    scheduleLabCycleEnd(
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
                    scheduleLabCycleEnd(
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
                    scheduleLabCycleEnd(
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
                scheduleLabCycleEnd(
                    runGeneration,
                    builtClient,
                    pulseStartedElapsed,
                    pulseNumber,
                    0L,
                    "connect_threw"
                )
            }

            if (isActiveClient(runGeneration, builtClient)) {
                scheduleLabCycleEnd(
                    runGeneration,
                    builtClient,
                    pulseStartedElapsed,
                    pulseNumber,
                    LAB_CONNECT_TIMEOUT_MS,
                    "connect_timeout"
                )
            }
        }

        private fun connectStabilizationClient(runGeneration: Long, reason: String) {
            if (!isCurrent(runGeneration)) return
            val now = SystemClock.elapsedRealtime()
            if (now >= deadlineElapsed) {
                finish("duration_complete", completed = true)
                return
            }

            clearReconnectRunnable()
            clearConnectTimeoutRunnable()
            clearQueryRunnable()
            disconnectActiveClient("stabilization_reconnect_$reason")
            pulseCount++
            val attempt = pulseCount
            val attemptStartedElapsed = now

            lateinit var builtClient: GoogleApiClient
            val callbacks = object : GoogleApiClient.ConnectionCallbacks,
                GoogleApiClient.OnConnectionFailedListener {
                override fun onConnected(bundle: android.os.Bundle?) {
                    if (!isActiveClient(runGeneration, builtClient)) return
                    clearConnectTimeoutRunnable()
                    connectedCount++
                    connectedSinceElapsed = SystemClock.elapsedRealtime()
                    lastHealthLogElapsed = 0L
                    LogManager.event(
                        context,
                        "gms_binder_stabilization_anchor_connected",
                        stabilizationMetrics(
                            extra = mapOf(
                                "attempt" to attempt,
                                "connectLatencyMs" to
                                    (connectedSinceElapsed - attemptStartedElapsed).coerceAtLeast(0L),
                                "trigger" to reason
                            )
                        )
                    )
                    runStabilizationQuery(runGeneration, builtClient)
                }

                override fun onConnectionSuspended(cause: Int) {
                    if (!isActiveClient(runGeneration, builtClient)) return
                    recordConnectedSegment()
                    LogManager.event(
                        context,
                        "gms_binder_stabilization_anchor_suspended",
                        stabilizationMetrics(
                            extra = mapOf(
                                "cause" to cause,
                                "attempt" to attempt
                            )
                        )
                    )
                    scheduleStabilizationReconnect(
                        runGeneration,
                        "connection_suspended_$cause",
                        STABILIZATION_RECONNECT_DELAY_MS
                    )
                }

                override fun onConnectionFailed(result: ConnectionResult) {
                    if (!isActiveClient(runGeneration, builtClient)) return
                    recordConnectedSegment()
                    LogManager.event(
                        context,
                        "gms_binder_stabilization_anchor_failed",
                        stabilizationMetrics(
                            extra = mapOf(
                                "failureCode" to result.errorCode,
                                "attempt" to attempt
                            )
                        )
                    )
                    scheduleStabilizationReconnect(
                        runGeneration,
                        "connection_failed_${result.errorCode}",
                        STABILIZATION_RECONNECT_DELAY_MS
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
                "gms_binder_stabilization_anchor_connecting",
                stabilizationMetrics(
                    extra = mapOf(
                        "attempt" to attempt,
                        "trigger" to reason
                    )
                )
            )

            runCatching { builtClient.connect() }.onFailure { error ->
                if (!isActiveClient(runGeneration, builtClient)) return@onFailure
                LogManager.event(
                    context,
                    "gms_binder_stabilization_anchor_connect_threw",
                    stabilizationMetrics(
                        extra = mapOf(
                            "attempt" to attempt,
                            "error" to error.javaClass.simpleName
                        )
                    )
                )
                scheduleStabilizationReconnect(
                    runGeneration,
                    "connect_threw",
                    STABILIZATION_RECONNECT_DELAY_MS
                )
            }

            if (isActiveClient(runGeneration, builtClient)) {
                connectTimeoutRunnable = Runnable {
                    connectTimeoutRunnable = null
                    if (!isActiveClient(runGeneration, builtClient)) return@Runnable
                    if (builtClient.isConnected) return@Runnable
                    LogManager.event(
                        context,
                        "gms_binder_stabilization_anchor_connect_timeout",
                        stabilizationMetrics(extra = mapOf("attempt" to attempt))
                    )
                    scheduleStabilizationReconnect(
                        runGeneration,
                        "connect_timeout",
                        STABILIZATION_RECONNECT_DELAY_MS
                    )
                }.also {
                    handler.postDelayed(it, STABILIZATION_CONNECT_TIMEOUT_MS)
                }
            }
        }

        private fun runStabilizationQuery(
            runGeneration: Long,
            expectedClient: GoogleApiClient
        ) {
            if (!isActiveClient(runGeneration, expectedClient)) return
            val now = SystemClock.elapsedRealtime()
            if (now >= deadlineElapsed) {
                finish("duration_complete", completed = true)
                return
            }
            if (!expectedClient.isConnected) {
                scheduleStabilizationReconnect(
                    runGeneration,
                    "query_client_not_connected",
                    STABILIZATION_RECONNECT_DELAY_MS
                )
                return
            }

            queryAttemptCount++
            performReadOnlyQuery(
                runGeneration,
                expectedClient,
                queryAttemptCount,
                labMode = false
            )
            maybeLogStabilizationHealth(now)
            clearQueryRunnable()
            queryRunnable = Runnable {
                queryRunnable = null
                runStabilizationQuery(runGeneration, expectedClient)
            }.also { handler.postDelayed(it, spec.intervalMs) }
        }

        private fun performReadOnlyQuery(
            runGeneration: Long,
            expectedClient: GoogleApiClient,
            sequence: Int,
            labMode: Boolean
        ) {
            runCatching {
                val request = LocationSettingsRequest.Builder().build()
                LocationServices.SettingsApi
                    .checkLocationSettings(expectedClient, request)
                    .setResultCallback { result ->
                        if (!isActiveClient(runGeneration, expectedClient)) {
                            return@setResultCallback
                        }
                        queryResultCount++
                        if (labMode) {
                            LogManager.event(
                                context,
                                "gms_binder_pulse_query_result",
                                mapOf(
                                    "mode" to spec.mode.name,
                                    "requestReason" to spec.reason,
                                    "pulse" to sequence,
                                    "statusCode" to result.status.statusCode
                                )
                            )
                        }
                    }
            }.onFailure { error ->
                LogManager.event(
                    context,
                    if (labMode) {
                        "gms_binder_pulse_query_failed"
                    } else {
                        "gms_binder_stabilization_anchor_query_failed"
                    },
                    if (labMode) {
                        mapOf(
                            "mode" to spec.mode.name,
                            "requestReason" to spec.reason,
                            "pulse" to sequence,
                            "error" to error.javaClass.simpleName
                        )
                    } else {
                        stabilizationMetrics(
                            extra = mapOf(
                                "sequence" to sequence,
                                "error" to error.javaClass.simpleName
                            )
                        )
                    }
                )
            }
        }

        private fun scheduleStabilizationReconnect(
            runGeneration: Long,
            reason: String,
            delayMs: Long
        ) {
            if (!isCurrent(runGeneration)) return
            clearConnectTimeoutRunnable()
            clearQueryRunnable()
            recordConnectedSegment()
            disconnectActiveClient("stabilization_$reason")
            if (reconnectRunnable != null) return
            reconnectCount++
            LogManager.event(
                context,
                "gms_binder_stabilization_anchor_reconnecting",
                stabilizationMetrics(
                    extra = mapOf(
                        "reason" to reason,
                        "delayMs" to delayMs
                    )
                )
            )
            reconnectRunnable = Runnable {
                reconnectRunnable = null
                connectStabilizationClient(runGeneration, reason)
            }.also { handler.postDelayed(it, delayMs) }
        }

        private fun scheduleLabCycleEnd(
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
                if (!isActiveClient(runGeneration, expectedClient)) return@Runnable
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
                scheduleNextLabPulse(runGeneration, pulseStartedElapsed)
            }.also { handler.postDelayed(it, delayMs) }
        }

        private fun scheduleNextLabPulse(
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
                runLabPulse(runGeneration)
            }.also { handler.postDelayed(it, delay) }
        }

        private fun scheduleDeadline(runGeneration: Long) {
            deadlineRunnable?.let(handler::removeCallbacks)
            deadlineRunnable = null
            if (!isCurrent(runGeneration)) return
            val delay = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            deadlineRunnable = Runnable {
                deadlineRunnable = null
                if (isCurrent(runGeneration)) {
                    finish("duration_complete", completed = true)
                }
            }.also { handler.postDelayed(it, delay) }
        }

        private fun maybeLogStabilizationHealth(now: Long) {
            if (
                lastHealthLogElapsed > 0L &&
                now - lastHealthLogElapsed < STABILIZATION_HEALTH_LOG_INTERVAL_MS
            ) {
                return
            }
            lastHealthLogElapsed = now
            LogManager.event(
                context,
                "gms_binder_stabilization_anchor_health",
                stabilizationMetrics()
            )
        }

        private fun recordConnectedSegment() {
            val since = connectedSinceElapsed
            if (since <= 0L) return
            val duration = (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L)
            totalConnectedMs += duration
            longestConnectedMs = maxOf(longestConnectedMs, duration)
            connectedSinceElapsed = 0L
        }

        private fun currentConnectedDurationMs(): Long =
            if (connectedSinceElapsed > 0L) {
                (SystemClock.elapsedRealtime() - connectedSinceElapsed).coerceAtLeast(0L)
            } else {
                0L
            }

        private fun stabilizationMetrics(
            extra: Map<String, Any> = emptyMap()
        ): Map<String, Any> = linkedMapOf<String, Any>(
            "mode" to spec.mode.name,
            "hostPid" to Process.myPid(),
            "binderAnchorConnected" to (activeClient?.isConnected == true),
            "binderAnchorConnectedDurationMs" to currentConnectedDurationMs(),
            "binderAnchorTotalConnectedMs" to
                (totalConnectedMs + currentConnectedDurationMs()),
            "binderAnchorLongestConnectedMs" to
                maxOf(longestConnectedMs, currentConnectedDurationMs()),
            "binderAnchorReconnectCount" to reconnectCount,
            "connectionAttemptCount" to pulseCount,
            "queryAttemptCount" to queryAttemptCount,
            "queryResultCount" to queryResultCount,
            "wakeLockHeld" to (wakeLock?.isHeld == true),
            "deadlineElapsed" to deadlineElapsed,
            "remainingMs" to
                (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        ).apply { putAll(extra) }

        private fun acquireWakeLock() {
            if (spec.mode != PulseMode.STABILIZATION_LEASE || wakeLock?.isHeld == true) return
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val created = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                ?: return
            created.setReferenceCounted(false)
            runCatching { created.acquire() }
            wakeLock = created
        }

        private fun releaseWakeLock() {
            val old = wakeLock
            wakeLock = null
            if (old?.isHeld == true) {
                runCatching { old.release() }
            }
        }

        private fun finish(reason: String, completed: Boolean) {
            if (!running) return
            val metricsBeforeStop = if (spec.mode == PulseMode.STABILIZATION_LEASE) {
                stabilizationMetrics(
                    extra = mapOf(
                        "requestReason" to spec.reason,
                        "reason" to reason,
                        "completed" to completed,
                        "elapsedMs" to
                            (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
                    )
                )
            } else {
                emptyMap()
            }
            running = false
            generation++
            nextPulseRunnable?.let(handler::removeCallbacks)
            nextPulseRunnable = null
            clearCycleRunnable()
            clearQueryRunnable()
            clearReconnectRunnable()
            clearConnectTimeoutRunnable()
            deadlineRunnable?.let(handler::removeCallbacks)
            deadlineRunnable = null
            recordConnectedSegment()
            disconnectActiveClient("run_finished")
            releaseWakeLock()
            val elapsed = SystemClock.elapsedRealtime() - startedElapsed

            if (spec.mode == PulseMode.STABILIZATION_LEASE) {
                LogManager.event(
                    context,
                    "gms_binder_stabilization_anchor_finished",
                    metricsBeforeStop + mapOf(
                        "binderAnchorConnected" to false,
                        "binderAnchorConnectedDurationMs" to 0L,
                        "binderAnchorTotalConnectedMs" to totalConnectedMs,
                        "binderAnchorLongestConnectedMs" to longestConnectedMs,
                        "wakeLockHeld" to false
                    )
                )
            } else {
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
            }
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

        private fun clearQueryRunnable() {
            queryRunnable?.let(handler::removeCallbacks)
            queryRunnable = null
        }

        private fun clearReconnectRunnable() {
            reconnectRunnable?.let(handler::removeCallbacks)
            reconnectRunnable = null
        }

        private fun clearConnectTimeoutRunnable() {
            connectTimeoutRunnable?.let(handler::removeCallbacks)
            connectTimeoutRunnable = null
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
