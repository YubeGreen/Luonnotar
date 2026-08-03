package com.yubegreen.luonnotar.notification

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-local coordinator.  In production it is called only from the
 * :keeper foreground-service process, so the GMS client binding originates
 * from the process that owns the guardian foreground service.
 */
object GmsBinderAnchorCoordinator {
    private const val HEALTH_CHECK_INTERVAL_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "luonnotar-gms-anchor-state").apply { isDaemon = true }
    }
    private var applicationContext: Context? = null
    private var guardianActive = false
    private var anchor: GmsBinderAnchor? = null
    private var lifecycleGeneration = 0L
    private var healthScheduled = false

    private val healthRunnable = object : Runnable {
        override fun run() {
            healthScheduled = false
            if (!guardianActive) return
            anchor?.healthCheck()
            scheduleHealthCheck()
        }
    }

    fun reconcile(context: Context, active: Boolean) = runOnMain {
        val app = context.applicationContext
        applicationContext = app
        guardianActive = active
        lifecycleGeneration++
        refreshFromProvider(app, lifecycleGeneration, active)
    }

    fun stop(context: Context, reason: String) = runOnMain {
        applicationContext = context.applicationContext
        guardianActive = false
        lifecycleGeneration++
        cancelHealthCheck()
        val oldAnchor = anchor
        anchor = null
        oldAnchor?.stop(reason)
        val app = applicationContext ?: return@runOnMain
        val generation = lifecycleGeneration
        executor.execute {
            val status = GuardianStatusClient.status(app)
            val enabled = status?.getBoolean(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_ENABLED,
                false
            ) == true
            mainHandler.post {
                if (generation != lifecycleGeneration) return@post
                persistState(app, waitingSnapshot(enabled))
            }
        }
    }

    fun manualRetry(context: Context, active: Boolean) = runOnMain {
        val app = context.applicationContext
        applicationContext = app
        guardianActive = active
        lifecycleGeneration++
        val generation = lifecycleGeneration
        executor.execute {
            val status = GuardianStatusClient.status(app)
            val enabled = status?.getBoolean(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_ENABLED,
                false
            ) == true
            mainHandler.post {
                if (generation != lifecycleGeneration) return@post
                refreshOnMain(app, enabled, active)
                if (enabled && active) anchor?.manualRetry()
            }
        }
    }

    private fun refreshFromProvider(
        context: Context,
        generation: Long,
        active: Boolean
    ) {
        executor.execute {
            val status = GuardianStatusClient.status(context)
            val enabled = status?.getBoolean(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_ENABLED,
                false
            ) == true
            mainHandler.post {
                if (generation != lifecycleGeneration) return@post
                refreshOnMain(context, enabled, active)
            }
        }
    }

    private fun refreshOnMain(
        context: Context,
        enabled: Boolean,
        active: Boolean
    ) {
        guardianActive = active
        if (!GmsBinderAnchorStatePolicy.shouldConnect(enabled, active)) {
            cancelHealthCheck()
            val oldAnchor = anchor
            anchor = null
            oldAnchor?.stop("coordinator_conditions_not_met")
            persistState(context, waitingSnapshot(enabled))
            return
        }
        if (anchor == null) {
            lateinit var created: GmsBinderAnchor
            created = GmsBinderAnchor(context.applicationContext) { snapshot ->
                if (anchor === created) persistState(context, snapshot)
            }
            anchor = created
        }
        anchor?.start()
        scheduleHealthCheck()
    }

    private fun scheduleHealthCheck() {
        if (!guardianActive || healthScheduled) return
        healthScheduled = true
        mainHandler.postDelayed(healthRunnable, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun cancelHealthCheck() {
        mainHandler.removeCallbacks(healthRunnable)
        healthScheduled = false
    }

    private fun waitingSnapshot(enabled: Boolean): GmsBinderAnchorSnapshot =
        GmsBinderAnchorSnapshot(
            enabled = enabled,
            state = if (enabled) {
                GmsBinderAnchorState.WAITING_FOR_GUARDIAN
            } else {
                GmsBinderAnchorState.DISABLED
            },
            connectedSinceElapsed = 0L,
            lastEventElapsed = SystemClock.elapsedRealtime(),
            reconnectAttempt = 0,
            suspensionCause = 0,
            failureCode = 0,
            gmsVersionCode = 0L,
            sessionGeneration = 0L,
            hostPid = 0,
            bootId = currentBootId()
        )

    private fun persistState(
        context: Context,
        snapshot: GmsBinderAnchorSnapshot
    ) {
        executor.execute {
            GuardianStatusClient.setGmsBinderAnchorSnapshot(context, snapshot)
        }
    }

    private fun currentBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unavailable")

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
