package com.yubegreen.luonnotar.notification

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.yubegreen.luonnotar.util.LogManager

/**
 * Bounded, recovery-only importance fence for GMS.
 *
 * The existing GoogleApiClient anchor keeps a public GMS Binder active, but the
 * framework chooses the normal binding flags internally.  This coordinator
 * adds explicit best-effort bindings from Luonnotar's foreground :keeper
 * process using BIND_IMPORTANT and BIND_ABOVE_CLIENT.  The experiment is
 * intentionally bounded and fully observable: failure to bind never blocks
 * the normal recovery campaign and all candidate/component decisions are
 * logged.
 *
 * The action strings are public client entry points used by Google Play
 * services.  They are resolved package-locally; no private Binder transaction
 * or hidden API is used.
 */
object GmsImportanceFencePolicy {
    const val DEFAULT_DURATION_MS = 120_000L
    const val MAX_TOTAL_DURATION_MS = 4 * 60_000L
    const val HEALTH_INTERVAL_MS = 2_000L
    const val REBIND_DELAY_MS = 250L
    const val BIND_CONNECT_TIMEOUT_MS = 5_000L

    const val MAIN_LOCATION_ACTION =
        "com.google.android.location.internal.GoogleLocationManagerService.START"
    const val PERSISTENT_COMMON_INTERNAL_ACTION =
        "com.google.android.gms.common.internal.service.START"
    const val PERSISTENT_COMMON_ACTION =
        "com.google.android.gms.common.service.START"

    fun bindingFlags(sdkInt: Int): Int {
        var flags =
            Context.BIND_AUTO_CREATE or
                Context.BIND_IMPORTANT or
                Context.BIND_ABOVE_CLIENT
        if (sdkInt >= Build.VERSION_CODES.Q) {
            flags = flags or Context.BIND_INCLUDE_CAPABILITIES
        }
        return flags
    }

    fun extendedDeadline(
        startedElapsed: Long,
        currentDeadlineElapsed: Long,
        nowElapsed: Long,
        requestedDurationMs: Long
    ): Long {
        if (startedElapsed <= 0L || nowElapsed < startedElapsed) return nowElapsed
        val hardDeadline = startedElapsed + MAX_TOTAL_DURATION_MS
        val requested = nowElapsed + requestedDurationMs.coerceAtLeast(0L)
        return minOf(hardDeadline, maxOf(currentDeadlineElapsed, requested))
    }

    fun shouldTryNextCandidate(currentIndex: Int, candidateCount: Int): Boolean =
        currentIndex >= 0 && currentIndex + 1 < candidateCount
}

enum class GmsImportanceFenceBindingState {
    IDLE,
    BINDING,
    CONNECTED,
    DISCONNECTED,
    NULL_BINDING,
    FAILED,
    EXHAUSTED
}

data class GmsImportanceFenceBindingSnapshot(
    val slot: String,
    val state: GmsImportanceFenceBindingState,
    val action: String,
    val component: String,
    val connectedSinceElapsed: Long,
    val bindAttemptCount: Int,
    val reconnectCount: Int,
    val failureCount: Int,
    val lastError: String
)

data class GmsImportanceFenceSnapshot(
    val active: Boolean,
    val generation: Long,
    val startedElapsed: Long,
    val deadlineElapsed: Long,
    val requestCount: Int,
    val flags: Int,
    val main: GmsImportanceFenceBindingSnapshot,
    val persistent: GmsImportanceFenceBindingSnapshot
) {
    val anyConnected: Boolean
        get() = main.state == GmsImportanceFenceBindingState.CONNECTED ||
            persistent.state == GmsImportanceFenceBindingState.CONNECTED

    val bothConnected: Boolean
        get() = main.state == GmsImportanceFenceBindingState.CONNECTED &&
            persistent.state == GmsImportanceFenceBindingState.CONNECTED

    fun compact(): String = listOf(
        "active=$active",
        "generation=$generation",
        "startedElapsed=$startedElapsed",
        "deadlineElapsed=$deadlineElapsed",
        "requestCount=$requestCount",
        "flags=$flags",
        "anyConnected=$anyConnected",
        "bothConnected=$bothConnected",
        "mainState=${main.state.name}",
        "mainAction=${main.action.sanitizeCompact()}",
        "mainComponent=${main.component.sanitizeCompact()}",
        "mainConnectedSince=${main.connectedSinceElapsed}",
        "mainBindAttempts=${main.bindAttemptCount}",
        "mainReconnects=${main.reconnectCount}",
        "mainFailures=${main.failureCount}",
        "persistentState=${persistent.state.name}",
        "persistentAction=${persistent.action.sanitizeCompact()}",
        "persistentComponent=${persistent.component.sanitizeCompact()}",
        "persistentConnectedSince=${persistent.connectedSinceElapsed}",
        "persistentBindAttempts=${persistent.bindAttemptCount}",
        "persistentReconnects=${persistent.reconnectCount}",
        "persistentFailures=${persistent.failureCount}"
    ).joinToString(";")
}

private fun String.sanitizeCompact(): String =
    replace(';', '_').replace('\n', '_').replace('\r', '_')

object GmsImportanceFenceCoordinator {
    private const val GMS_PACKAGE = "com.google.android.gms"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var activeRun: FenceRun? = null
    private var nextGeneration = 0L

    @Volatile
    private var latestSnapshot = idleSnapshot()

    fun startOrExtend(
        context: Context,
        reason: String,
        durationMs: Long = GmsImportanceFencePolicy.DEFAULT_DURATION_MS
    ): Boolean {
        val app = context.applicationContext
        runOnMain {
            val existing = synchronized(stateLock) { activeRun }
            if (existing != null) {
                existing.extend(reason, durationMs)
            } else {
                nextGeneration += 1L
                lateinit var created: FenceRun
                created = FenceRun(
                    context = app,
                    generation = nextGeneration,
                    initialReason = reason,
                    initialDurationMs = durationMs
                ) {
                    synchronized(stateLock) {
                        if (activeRun === created) activeRun = null
                    }
                }
                synchronized(stateLock) { activeRun = created }
                created.start()
            }
        }
        return true
    }

    fun stop(context: Context, reason: String) = runOnMain {
        val run = synchronized(stateLock) {
            activeRun.also { activeRun = null }
        }
        run?.stop(reason)
        if (run == null) {
            latestSnapshot = idleSnapshot()
        }
        LogManager.event(
            context.applicationContext,
            "gms_importance_fence_coordinator_stopped",
            mapOf("reason" to reason, "hadActiveRun" to (run != null))
        )
    }

    fun snapshot(): GmsImportanceFenceSnapshot = latestSnapshot

    private fun publish(snapshot: GmsImportanceFenceSnapshot) {
        latestSnapshot = snapshot
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun idleBinding(slot: String) = GmsImportanceFenceBindingSnapshot(
        slot = slot,
        state = GmsImportanceFenceBindingState.IDLE,
        action = "",
        component = "",
        connectedSinceElapsed = 0L,
        bindAttemptCount = 0,
        reconnectCount = 0,
        failureCount = 0,
        lastError = ""
    )

    private fun idleSnapshot() = GmsImportanceFenceSnapshot(
        active = false,
        generation = 0L,
        startedElapsed = 0L,
        deadlineElapsed = 0L,
        requestCount = 0,
        flags = GmsImportanceFencePolicy.bindingFlags(Build.VERSION.SDK_INT),
        main = idleBinding("main"),
        persistent = idleBinding("persistent")
    )

    private class FenceRun(
        private val context: Context,
        private val generation: Long,
        private val initialReason: String,
        private val initialDurationMs: Long,
        private val onFinished: () -> Unit
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val flags = GmsImportanceFencePolicy.bindingFlags(Build.VERSION.SDK_INT)
        private val mainSlot = BindingSlot(
            slotName = "main",
            candidates = listOf(GmsImportanceFencePolicy.MAIN_LOCATION_ACTION)
        )
        private val persistentSlot = BindingSlot(
            slotName = "persistent",
            candidates = listOf(
                GmsImportanceFencePolicy.PERSISTENT_COMMON_INTERNAL_ACTION,
                GmsImportanceFencePolicy.PERSISTENT_COMMON_ACTION
            )
        )

        private var active = false
        private var startedElapsed = 0L
        private var deadlineElapsed = 0L
        private var requestCount = 0
        private var deadlineRunnable: Runnable? = null
        private var healthRunnable: Runnable? = null

        fun start() {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (active) return
            active = true
            startedElapsed = SystemClock.elapsedRealtime()
            deadlineElapsed = GmsImportanceFencePolicy.extendedDeadline(
                startedElapsed = startedElapsed,
                currentDeadlineElapsed = startedElapsed,
                nowElapsed = startedElapsed,
                requestedDurationMs = initialDurationMs
            )
            requestCount = 1
            publishAndLog(
                "gms_importance_fence_started",
                mapOf(
                    "reason" to initialReason,
                    "durationMs" to initialDurationMs,
                    "flags" to flags,
                    "mainAction" to GmsImportanceFencePolicy.MAIN_LOCATION_ACTION,
                    "persistentCandidates" to persistentSlot.candidates.joinToString(",")
                )
            )
            mainSlot.bindCurrent("initial")
            persistentSlot.bindCurrent("initial")
            scheduleDeadline()
            scheduleHealth()
        }

        fun extend(reason: String, durationMs: Long) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!active) return
            requestCount += 1
            deadlineElapsed = GmsImportanceFencePolicy.extendedDeadline(
                startedElapsed = startedElapsed,
                currentDeadlineElapsed = deadlineElapsed,
                nowElapsed = SystemClock.elapsedRealtime(),
                requestedDurationMs = durationMs
            )
            if (!mainSlot.isBoundOrConnecting()) mainSlot.bindCurrent("extend")
            if (!persistentSlot.isBoundOrConnecting()) persistentSlot.bindCurrent("extend")
            scheduleDeadline()
            publishAndLog(
                "gms_importance_fence_extended",
                mapOf(
                    "reason" to reason,
                    "durationMs" to durationMs,
                    "deadlineElapsed" to deadlineElapsed,
                    "requestCount" to requestCount
                )
            )
        }

        fun stop(reason: String) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!active) return
            active = false
            deadlineRunnable?.let(handler::removeCallbacks)
            healthRunnable?.let(handler::removeCallbacks)
            deadlineRunnable = null
            healthRunnable = null
            mainSlot.unbind("stop")
            persistentSlot.unbind("stop")
            publishAndLog(
                "gms_importance_fence_stopped",
                mapOf(
                    "reason" to reason,
                    "activeDurationMs" to
                        (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0L)
                )
            )
            onFinished()
        }

        private fun scheduleDeadline() {
            deadlineRunnable?.let(handler::removeCallbacks)
            val delay = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            deadlineRunnable = Runnable {
                deadlineRunnable = null
                if (active) stop("deadline")
            }.also { handler.postDelayed(it, delay) }
        }

        private fun scheduleHealth() {
            healthRunnable?.let(handler::removeCallbacks)
            healthRunnable = Runnable {
                healthRunnable = null
                if (!active) return@Runnable
                mainSlot.healthCheck()
                persistentSlot.healthCheck()
                publishAndLog("gms_importance_fence_health", emptyMap())
                scheduleHealth()
            }.also {
                handler.postDelayed(it, GmsImportanceFencePolicy.HEALTH_INTERVAL_MS)
            }
        }

        private fun snapshot() = GmsImportanceFenceSnapshot(
            active = active,
            generation = generation,
            startedElapsed = startedElapsed,
            deadlineElapsed = deadlineElapsed,
            requestCount = requestCount,
            flags = flags,
            main = mainSlot.snapshot(),
            persistent = persistentSlot.snapshot()
        )

        private fun publishAndLog(event: String, extra: Map<String, Any?>) {
            val snapshot = snapshot()
            publish(snapshot)
            LogManager.event(
                context,
                event,
                linkedMapOf<String, Any?>(
                    "generation" to generation,
                    "active" to snapshot.active,
                    "anyConnected" to snapshot.anyConnected,
                    "bothConnected" to snapshot.bothConnected,
                    "mainState" to snapshot.main.state.name,
                    "mainAction" to snapshot.main.action,
                    "mainComponent" to snapshot.main.component,
                    "persistentState" to snapshot.persistent.state.name,
                    "persistentAction" to snapshot.persistent.action,
                    "persistentComponent" to snapshot.persistent.component,
                    "flags" to flags
                ).apply { putAll(extra) }
            )
        }

        private inner class BindingSlot(
            val slotName: String,
            val candidates: List<String>
        ) {
            private var candidateIndex = 0
            private var connection: ServiceConnection? = null
            private var binder: IBinder? = null
            private var bound = false
            private var state = GmsImportanceFenceBindingState.IDLE
            private var component = ""
            private var bindStartedElapsed = 0L
            private var connectedSinceElapsed = 0L
            private var bindAttemptCount = 0
            private var reconnectCount = 0
            private var failureCount = 0
            private var lastError = ""
            private var rebindRunnable: Runnable? = null

            private val action: String
                get() = candidates.getOrElse(candidateIndex) { "" }

            fun isBoundOrConnecting(): Boolean =
                bound || state == GmsImportanceFenceBindingState.BINDING ||
                    state == GmsImportanceFenceBindingState.CONNECTED

            fun bindCurrent(trigger: String) {
                if (!active || action.isBlank()) return
                rebindRunnable?.let(handler::removeCallbacks)
                rebindRunnable = null
                unbind("replace_before_bind")
                state = GmsImportanceFenceBindingState.BINDING
                component = ""
                bindStartedElapsed = SystemClock.elapsedRealtime()
                connectedSinceElapsed = 0L
                binder = null
                bindAttemptCount += 1
                val expectedGeneration = generation
                lateinit var createdConnection: ServiceConnection
                createdConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        if (!isCurrent(expectedGeneration, createdConnection)) return
                        binder = service
                        component = name.flattenToShortString()
                        connectedSinceElapsed = SystemClock.elapsedRealtime()
                        bindStartedElapsed = 0L
                        state = GmsImportanceFenceBindingState.CONNECTED
                        lastError = ""
                        publishAndLog(
                            "gms_importance_fence_connected",
                            mapOf(
                                "slot" to slotName,
                                "trigger" to trigger,
                                "action" to action,
                                "component" to component,
                                "binderAlive" to service.isBinderAlive
                            )
                        )
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        if (!isCurrent(expectedGeneration, createdConnection)) return
                        binder = null
                        component = name.flattenToShortString()
                        connectedSinceElapsed = 0L
                        state = GmsImportanceFenceBindingState.DISCONNECTED
                        reconnectCount += 1
                        publishAndLog(
                            "gms_importance_fence_disconnected",
                            mapOf("slot" to slotName, "component" to component)
                        )
                        scheduleRebind("service_disconnected")
                    }

                    override fun onBindingDied(name: ComponentName) {
                        if (!isCurrent(expectedGeneration, createdConnection)) return
                        binder = null
                        component = name.flattenToShortString()
                        connectedSinceElapsed = 0L
                        state = GmsImportanceFenceBindingState.DISCONNECTED
                        reconnectCount += 1
                        publishAndLog(
                            "gms_importance_fence_binding_died",
                            mapOf("slot" to slotName, "component" to component)
                        )
                        scheduleRebind("binding_died")
                    }

                    override fun onNullBinding(name: ComponentName) {
                        if (!isCurrent(expectedGeneration, createdConnection)) return
                        binder = null
                        component = name.flattenToShortString()
                        connectedSinceElapsed = 0L
                        state = GmsImportanceFenceBindingState.NULL_BINDING
                        failureCount += 1
                        lastError = "null_binding"
                        publishAndLog(
                            "gms_importance_fence_null_binding",
                            mapOf("slot" to slotName, "component" to component)
                        )
                        tryNextCandidateOrExhaust("null_binding")
                    }
                }
                connection = createdConnection
                val intent = Intent(action).setPackage(GMS_PACKAGE)
                val accepted = runCatching {
                    context.bindService(intent, createdConnection, flags)
                }.getOrElse { error ->
                    failureCount += 1
                    lastError = "${error.javaClass.simpleName}:${error.message.orEmpty()}"
                    false
                }
                bound = accepted
                if (!accepted) {
                    state = GmsImportanceFenceBindingState.FAILED
                    failureCount += 1
                    if (lastError.isBlank()) lastError = "bind_returned_false"
                    publishAndLog(
                        "gms_importance_fence_bind_failed",
                        mapOf(
                            "slot" to slotName,
                            "trigger" to trigger,
                            "action" to action,
                            "error" to lastError
                        )
                    )
                    tryNextCandidateOrExhaust("bind_failed")
                } else {
                    publishAndLog(
                        "gms_importance_fence_bind_requested",
                        mapOf(
                            "slot" to slotName,
                            "trigger" to trigger,
                            "action" to action,
                            "flags" to flags
                        )
                    )
                }
            }

            fun healthCheck() {
                if (!active) return
                val currentBinder = binder
                if (state == GmsImportanceFenceBindingState.CONNECTED) {
                    val healthy = currentBinder?.isBinderAlive == true &&
                        runCatching { currentBinder.pingBinder() }.getOrDefault(false)
                    if (!healthy) {
                        state = GmsImportanceFenceBindingState.DISCONNECTED
                        connectedSinceElapsed = 0L
                        reconnectCount += 1
                        lastError = "binder_not_alive"
                        publishAndLog(
                            "gms_importance_fence_binder_unhealthy",
                            mapOf("slot" to slotName, "action" to action)
                        )
                        scheduleRebind("binder_unhealthy")
                    }
                } else if (
                    state == GmsImportanceFenceBindingState.BINDING &&
                    bindStartedElapsed > 0L &&
                    SystemClock.elapsedRealtime() - bindStartedElapsed >=
                        GmsImportanceFencePolicy.BIND_CONNECT_TIMEOUT_MS
                ) {
                    failureCount += 1
                    lastError = "bind_connect_timeout"
                    state = GmsImportanceFenceBindingState.FAILED
                    publishAndLog(
                        "gms_importance_fence_bind_timeout",
                        mapOf("slot" to slotName, "action" to action)
                    )
                    tryNextCandidateOrExhaust("connect_timeout")
                } else if (
                    state != GmsImportanceFenceBindingState.EXHAUSTED &&
                    !bound &&
                    rebindRunnable == null
                ) {
                    scheduleRebind("health_not_bound")
                }
            }

            fun unbind(reason: String) {
                rebindRunnable?.let(handler::removeCallbacks)
                rebindRunnable = null
                val oldConnection = connection
                connection = null
                binder = null
                bindStartedElapsed = 0L
                connectedSinceElapsed = 0L
                if (bound && oldConnection != null) {
                    runCatching { context.unbindService(oldConnection) }
                }
                bound = false
                if (reason == "stop") {
                    state = GmsImportanceFenceBindingState.IDLE
                    component = ""
                }
            }

            private fun isCurrent(
                expectedGeneration: Long,
                expectedConnection: ServiceConnection
            ): Boolean =
                active && generation == expectedGeneration && connection === expectedConnection

            private fun scheduleRebind(reason: String) {
                if (!active || rebindRunnable != null) return
                rebindRunnable = Runnable {
                    rebindRunnable = null
                    if (!active) return@Runnable
                    bindCurrent(reason)
                }.also {
                    handler.postDelayed(it, GmsImportanceFencePolicy.REBIND_DELAY_MS)
                }
            }

            private fun tryNextCandidateOrExhaust(reason: String) {
                unbind("candidate_failed")
                if (
                    GmsImportanceFencePolicy.shouldTryNextCandidate(
                        candidateIndex,
                        candidates.size
                    )
                ) {
                    candidateIndex += 1
                    handler.post { if (active) bindCurrent("fallback_$reason") }
                } else {
                    state = GmsImportanceFenceBindingState.EXHAUSTED
                    publishAndLog(
                        "gms_importance_fence_exhausted",
                        mapOf(
                            "slot" to slotName,
                            "reason" to reason,
                            "candidates" to candidates.joinToString(","),
                            "lastError" to lastError
                        )
                    )
                }
            }

            fun snapshot() = GmsImportanceFenceBindingSnapshot(
                slot = slotName,
                state = state,
                action = action,
                component = component,
                connectedSinceElapsed = connectedSinceElapsed,
                bindAttemptCount = bindAttemptCount,
                reconnectCount = reconnectCount,
                failureCount = failureCount,
                lastError = lastError
            )
        }
    }
}
