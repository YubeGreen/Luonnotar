package com.yubegreen.luonnotar.privileged

import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs outside Luonnotar's ordinary app UID: either in the built-in app_process
 * engine or a Shizuku/Sui UserService (shell UID 2000 or root UID 0).
 * It deliberately does not depend on Luonnotar's normal app process, :keeper,
 * alarms, WorkManager or wake locks.
 */
class PrivilegedGuardianUserService() : IPrivilegedGuardian.Stub() {
    @Suppress("unused")
    constructor(context: Context) : this() {
        // Shizuku's UserService Context is intentionally not used: it is not a
        // normal application Context and several Android APIs do not work there.
    }

    private val lock = Any()
    private val runner = GuardianCommandRunner()
    private val backgroundPolicyEngine = BackgroundPolicyEngine(runner)
    private val diagnosticStore = GuardianDiagnosticStore()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "luonnotar-privileged-guardian").apply { isDaemon = true }
    }
    private val longOperationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-privileged-long-operation").apply { isDaemon = true }
    }
    private val recentEvents = ArrayDeque<GuardianEvent>()
    private var scheduled: ScheduledFuture<*>? = null
    private var initialCycleFuture: ScheduledFuture<*>? = null
    private var eventWatcherProcess: java.lang.Process? = null
    private var eventWatcherThread: Thread? = null
    private var eventWatcherAlive = false
    private var eventTriggerCount = 0L
    private var config = GuardianEngineConfig().normalized()
    private var running = false
    private var startedElapsed = 0L
    private var lastCycleElapsed = 0L
    private var lastTuneElapsed = 0L
    private var cycleCount = 0L
    private var actionCount = 0L
    private var errorCount = 0L
    private var commandFailureCount = 0L
    private var verificationFailureCount = 0L
    private var effectiveThawCount = 0L
    private var noSafeCommandPathCount = 0L
    private var recoveryEscalationCount = 0L
    private var directCgroupAttemptCount = 0L
    private var directCgroupSuccessCount = 0L
    private var capabilityChecked = false
    private var supportsStickyUnfreeze = false
    private var supportsSecondaryProcessUnfreeze = false
    private var supportsStopApp = false
    private var supportsPackageUnstop = false
    private val secondaryUnfreezeProbeAttempted = linkedSetOf<String>()
    private var supportsAppHibernation = false
    private var identity = "uid=${Process.myUid()}"
    private val previousPidByName = linkedMapOf<String, Int>()
    private val lastActionByName = linkedMapOf<String, Long>()
    private val lastSignalTriggerByPackage = linkedMapOf<String, Long>()
    private val lastVendorSignalByPackage = linkedMapOf<String, Long>()
    private val lastVendorActionByPackage = linkedMapOf<String, Long>()
    private val vendorHoldUntilByPackage = linkedMapOf<String, Long>()
    private val vendorSignalCountByPackage = linkedMapOf<String, Long>()
    private val vendorRelapseCountByPackage = linkedMapOf<String, Long>()
    private val vendorRecoveryGenerationByPackage = linkedMapOf<String, Long>()
    private val vendorRecoveryFuturesByPackage =
        linkedMapOf<String, MutableList<ScheduledFuture<*>>>()
    private val vendorRecoveryCriticalByPackage = linkedMapOf<String, Boolean>()
    private val vendorRecoverySignalKindByPackage = linkedMapOf<String, VendorFreezeSignalKind>()
    private val vendorRecoveryExhaustedUntilByPackage = linkedMapOf<String, Long>()
    private var vendorSignalCount = 0L
    private var vendorDeliveryFailureCount = 0L
    private var vendorRecoveryPassCount = 0L
    private val deliveryFailureEpisodesByPackage = linkedMapOf<String, ArrayDeque<Long>>()
    private val lastDeliveryEpisodeByPackage = linkedMapOf<String, Long>()
    private val packageRebuildHistoryByPackage = linkedMapOf<String, ArrayDeque<Long>>()
    private val lastPackageRebuildByPackage = linkedMapOf<String, Long>()
    private val packageRebuildInProgress = linkedSetOf<String>()
    private var packageRebuildAttemptCount = 0L
    private var packageRebuildSuccessCount = 0L
    private var lastPackageRebuildOutcome = PackageRebuildOutcome()
    private val packageSuccessorGuardByPackage = linkedMapOf<String, PackageSuccessorGuard>()
    private val packageSuccessorGuardFutureByPackage = linkedMapOf<String, ScheduledFuture<*>>()
    private val packageSuccessorCircuitUntilByPackage = linkedMapOf<String, Long>()
    private val lastPackageCircuitDeliveryRescueByPackage = linkedMapOf<String, Long>()
    private var packageCircuitDeliveryRescueCount = 0L
    private val deliveryProtectionByPackage = linkedMapOf<String, DeliveryProtectionLease>()
    private val deliveryProtectionFutureByPackage = linkedMapOf<String, ScheduledFuture<*>>()
    private var deliveryProtectionStartCount = 0L
    private var deliveryProtectionCompletionCount = 0L
    private var deliveryProtectionKillCount = 0L
    private val lastManagedPackageWakeByPackage = linkedMapOf<String, Long>()
    private val managedPackageFrozenSinceByPackage = linkedMapOf<String, Long>()
    private val lastManagedPackageFrozenWakeByPackage = linkedMapOf<String, Long>()
    private var packageSuccessorGuardStartCount = 0L
    private var packageSuccessorGuardRefreezeCount = 0L
    private var packageSuccessorGuardVerifiedCount = 0L
    private var latestProcesses = emptyList<GuardianProcessState>()
    private val gmsFreezeEvents = ArrayDeque<Long>()
    private val gmsRecoveryHistory = ArrayDeque<Long>()
    private val gmsForceStopHistory = ArrayDeque<Long>()
    private var gmsRecoveryInProgress = false
    private var gmsManualRecoveryQueued = false
    private var gmsManualRecoveryRequestId = 0L
    private var gmsManualRecoveryState = "idle"
    private var lastGmsRecoveryElapsed = 0L
    private var lastGmsRecoveryCompletedElapsed = 0L
    private var gmsConsecutiveCampaignFailures = 0
    private var gmsRecoveryAttemptCount = 0L
    private var gmsRecoverySuccessCount = 0L
    private var gmsRecoveryGeneration = 0L
    private var gmsRecoveryCampaign: GmsRecoveryCampaign? = null
    private var gmsRecoveryCampaignFuture: ScheduledFuture<*>? = null
    private var gmsRecoveryResetCount = 0L
    private var gmsRecoveryStopAppCount = 0L
    private var gmsRecoveryForceStopCount = 0L
    private var gmsRecoverySuccessorRefreezeCount = 0L
    private var gmsPidRestartCount = 0L
    private var gmsTransportVerifiedRecoveryCount = 0L
    private var lastGmsRecoveryOutcome = GmsRecoveryOutcome()
    private var lastGmsTransportProbeElapsed = 0L
    private var lastGmsTransportHealthyElapsed = 0L
    private var gmsTransportMissingSinceElapsed = 0L
    private var gmsTransportConsecutiveMissing = 0
    private var gmsTransportProbeCount = 0L
    private var gmsTransportHealthyCount = 0L
    private var gmsTransportUnobservableCount = 0L
    private var lastGmsTransportProbe = GmsTransportProbe(false, emptySet(), "never")
    private var lastGmsBadAuthenticationElapsed = 0L
    private var gmsBadAuthenticationCount = 0L
    private var lastGmsMcsConnectAttemptElapsed = 0L
    private var adbTcp5555ObservedHealthy = false
    private var adbTcp5555Configured = false
    private var adbTcp5555LastProbeElapsed = 0L
    private var adbTcp5555LastHealthyElapsed = 0L
    private var adbTcp5555MissingSinceElapsed = 0L
    private var adbTcp5555LastRecoveryElapsed = 0L
    private var adbTcp5555ProbeCount = 0L
    private var adbTcp5555RecoveryCount = 0L
    private var adbTcp5555ListenerPresent = false
    private var diagnosticWriteErrorCount = 0L
    private var lastDiagnosticStatusWriteElapsed = 0L
    @Volatile private var cachedStatusJson = "{}"
    private var lastBackgroundPolicyReport = BackgroundPolicyReport.empty()

    override fun configureAndStart(configJson: String): String = synchronized(lock) {
        val firstStart = !running
        config = GuardianEngineConfig.fromJson(configJson)
        running = true
        if (startedElapsed <= 0L) startedElapsed = SystemClock.elapsedRealtime()
        ensureCapabilitiesLocked()
        startEventWatcherLocked()
        restartScheduleLocked()
        eventLocked("engine_started", "poll=${config.pollIntervalMs}ms uid=${Process.myUid()}")
        persistStatusLocked(force = true)
        if (firstStart) scheduleInitialCycleLocked()
        statusJsonLocked()
    }

    /**
     * Status reads must never wait behind a bounded but slow recovery. The
     * mutating paths publish an immutable JSON snapshot while holding [lock].
     */
    override fun getStatusJson(): String = cachedStatusJson

    override fun runCycle(): String = synchronized(lock) {
        if (!running) {
            eventLocked("manual_cycle_rejected", "engine_disabled")
            return@synchronized statusJsonLocked()
        }
        runCycleLocked(force = true)
    }

    override fun recoverGms(): String = synchronized(lock) {
        if (!running) {
            eventLocked("gms_recovery_rejected", "engine_disabled")
            return@synchronized JSONObject()
                .put("accepted", false)
                .put("reason", "engine_disabled")
                .put("requestId", gmsManualRecoveryRequestId)
                .toString()
        }
        if (gmsManualRecoveryQueued || gmsRecoveryInProgress) {
            eventLocked("gms_recovery_request_coalesced", "already_queued_or_running")
            return@synchronized JSONObject()
                .put("accepted", true)
                .put("coalesced", true)
                .put("state", if (gmsRecoveryInProgress) "running" else "queued")
                .put("requestId", gmsManualRecoveryRequestId)
                .toString()
        }

        val requestId = gmsManualRecoveryRequestId + 1L
        gmsManualRecoveryRequestId = requestId
        gmsManualRecoveryQueued = true
        gmsManualRecoveryState = "queued"
        eventLocked("gms_recovery_request_accepted", "requestId=$requestId")
        persistStatusLocked(force = true)

        try {
            longOperationExecutor.execute {
                synchronized(lock) recoveryBlock@ {
                    if (!running) {
                        gmsManualRecoveryQueued = false
                        gmsManualRecoveryState = "cancelled_engine_disabled"
                        eventLocked("gms_recovery_request_cancelled", "requestId=$requestId engine_disabled")
                        persistStatusLocked(force = true)
                        return@recoveryBlock
                    }
                    gmsManualRecoveryQueued = false
                    gmsManualRecoveryState = "running"
                    eventLocked("gms_recovery_request_started", "requestId=$requestId")
                    persistStatusLocked(force = true)
                    try {
                        recoverGmsLocked(trigger = "manual_request_$requestId", manual = true)
                        if (gmsRecoveryInProgress) {
                            gmsManualRecoveryState = "campaign_running"
                            eventLocked(
                                "gms_recovery_request_campaign_started",
                                "requestId=$requestId generation=${gmsRecoveryCampaign?.generation ?: 0L}"
                            )
                        } else {
                            gmsManualRecoveryState = "completed_without_campaign"
                            eventLocked("gms_recovery_request_completed", "requestId=$requestId")
                        }
                    } catch (error: Throwable) {
                        gmsRecoveryInProgress = false
                        gmsManualRecoveryState = "failed:${error.javaClass.simpleName}"
                        errorCount += 1
                        eventLocked(
                            "gms_recovery_request_failed",
                            "requestId=$requestId ${error.javaClass.simpleName}:${error.message.orEmpty()}"
                        )
                    } finally {
                        persistStatusLocked(force = true)
                    }
                }
            }
        } catch (error: Throwable) {
            gmsManualRecoveryQueued = false
            gmsManualRecoveryState = "dispatch_failed:${error.javaClass.simpleName}"
            eventLocked("gms_recovery_request_dispatch_failed", "requestId=$requestId ${error.message.orEmpty()}")
            persistStatusLocked(force = true)
            throw error
        }

        JSONObject()
            .put("accepted", true)
            .put("coalesced", false)
            .put("state", "queued")
            .put("requestId", requestId)
            .toString()
    }

    fun applyBackgroundPolicy(requestJson: String): String = synchronized(lock) {
        if (!running) {
            eventLocked("background_policy_rejected", "engine_disabled")
            return@synchronized lastBackgroundPolicyReport.toJson()
        }
        applyBackgroundPolicyLocked(requestJson).toJson()
    }

    override fun stop(): String = synchronized(lock) {
        running = false
        gmsManualRecoveryQueued = false
        if (gmsManualRecoveryState == "queued") gmsManualRecoveryState = "cancelled_engine_stopped"
        scheduled?.cancel(false)
        scheduled = null
        initialCycleFuture?.cancel(false)
        initialCycleFuture = null
        cancelVendorRecoveryBurstsLocked()
        cancelPackageSuccessorGuardsLocked("engine_stopped")
        cancelDeliveryProtectionLeasesLocked("engine_stopped")
        cancelGmsRecoveryCampaignLocked("engine_stopped")
        stopEventWatcherLocked()
        eventLocked("engine_stopped", "requested_by_client")
        persistStatusLocked(force = true)
        statusJsonLocked()
    }

    override fun destroy() {
        synchronized(lock) {
            running = false
            scheduled?.cancel(true)
            scheduled = null
            initialCycleFuture?.cancel(true)
            initialCycleFuture = null
            cancelVendorRecoveryBurstsLocked()
            cancelPackageSuccessorGuardsLocked("engine_destroyed")
            cancelDeliveryProtectionLeasesLocked("engine_destroyed")
            cancelGmsRecoveryCampaignLocked("engine_destroyed")
            stopEventWatcherLocked()
            executor.shutdownNow()
            longOperationExecutor.shutdownNow()
            gmsManualRecoveryQueued = false
            gmsManualRecoveryState = "destroyed"
            eventLocked("engine_destroyed", "user_service_replaced_or_removed")
            persistStatusLocked(force = true)
        }
        thread(name = "luonnotar-user-service-exit", isDaemon = true) {
            Thread.sleep(120L)
            exitProcess(0)
        }
    }

    private fun startEventWatcherLocked() {
        if (eventWatcherProcess?.isAlive == true && eventWatcherThread?.isAlive == true) {
            eventWatcherAlive = true
            return
        }
        stopEventWatcherLocked()
        val process = runCatching {
            ProcessBuilder(
                "logcat",
                "-b", "events",
                "-b", "system",
                "-b", "main",
                "-v", "brief",
                "-T", "1",
                "am_app_frozen:I",
                "BroadcastQueue:W",
                "BroadcastQueueModernImpl:W",
                "BroadcastQueueInjector:W",
                "PowerManagerServiceImpl:I",
                "PowerManagerService:I",
                "GCM:W",
                "AuthPII:E",
                "Linux:D",
                "*:S"
            )
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            eventWatcherAlive = false
            errorCount += 1
            eventLocked("event_watcher_start_failed", "${error.javaClass.simpleName}:${error.message}")
            return
        }
        eventWatcherProcess = process
        eventWatcherAlive = true
        eventWatcherThread = thread(
            name = "luonnotar-freezer-event-watcher",
            isDaemon = true
        ) {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val now = SystemClock.elapsedRealtime()
                        GmsTransportLogSignalParser.parse(line)?.let { healthSignal ->
                            synchronized(lock) {
                                recordGmsTransportLogSignalLocked(healthSignal, now)
                            }
                        }

                        val signal = synchronized(lock) {
                            VendorFreezeSignalParser.parse(
                                line = line,
                                processTargets = config.processTargets,
                                packageTargets = config.packageTargets
                            )
                        } ?: return@forEach
                        val accepted = synchronized(lock) {
                            recordVendorSignalLocked(signal, now)
                            if (!running) {
                                false
                            } else {
                                val lastTrigger = lastSignalTriggerByPackage[signal.packageName] ?: 0L
                                if (
                                    now >= lastTrigger &&
                                    now - lastTrigger < VendorFreezeRecoveryPolicy.SIGNAL_DEBOUNCE_MS
                                ) {
                                    false
                                } else {
                                    lastSignalTriggerByPackage[signal.packageName] = now
                                    eventTriggerCount += 1
                                    true
                                }
                            }
                        }
                        if (accepted) {
                            runCatching {
                                executor.execute {
                                    synchronized(lock) {
                                        if (running) startVendorRecoveryBurstLocked(signal)
                                    }
                                }
                            }.onFailure { error ->
                                synchronized(lock) {
                                    errorCount += 1
                                    eventLocked(
                                        "vendor_recovery_schedule_failed",
                                        "${signal.packageName} ${error.javaClass.simpleName}:${error.message}"
                                    )
                                }
                            }
                        }
                    }
                }
            }.onFailure { error ->
                synchronized(lock) {
                    if (running) {
                        errorCount += 1
                        eventLocked(
                            "event_watcher_failed",
                            "${error.javaClass.simpleName}:${error.message}"
                        )
                    }
                }
            }
            synchronized(lock) { eventWatcherAlive = false }
        }
        eventLocked(
            "event_watcher_started",
            "logcat events+system+main / aosp+vendor freezer evidence"
        )
    }

    private fun recordVendorSignalLocked(signal: VendorFreezeSignal, now: Long) {
        val packageName = signal.packageName
        vendorSignalCount += 1
        vendorSignalCountByPackage[packageName] =
            (vendorSignalCountByPackage[packageName] ?: 0L) + 1L
        if (signal.deliveryCritical) vendorDeliveryFailureCount += 1

        val lastAction = lastVendorActionByPackage[packageName]
        if (
            lastAction != null &&
            now >= lastAction &&
            now - lastAction <= VendorFreezeRecoveryPolicy.RELAPSE_WINDOW_MS
        ) {
            vendorRelapseCountByPackage[packageName] =
                (vendorRelapseCountByPackage[packageName] ?: 0L) + 1L
            eventLocked(
                "vendor_freeze_relapse",
                "$packageName kind=${signal.kind} afterActionMs=${now - lastAction}"
            )
        }

        lastVendorSignalByPackage[packageName] = now
        val holdUntil = now + VendorFreezeRecoveryPolicy.holdDurationMs(signal)
        vendorHoldUntilByPackage[packageName] = maxOf(
            vendorHoldUntilByPackage[packageName] ?: 0L,
            holdUntil
        )

        eventLocked(
            "vendor_freeze_signal",
            "$packageName kind=${signal.kind} critical=${signal.deliveryCritical} ${signal.rawLine.take(300)}"
        )
        if (
            packageName == GMS_PACKAGE &&
            signal.kind == VendorFreezeSignalKind.AOSP_APP_FROZEN
        ) {
            recordGmsFreezeEventLocked(now, signal.rawLine)
        }
        if (
            signal.deliveryCritical &&
            signal.kind != VendorFreezeSignalKind.AUTOSTART_LAUNCH_DENIED &&
            DeliveryFailureEscalationPolicy.isRebuildTarget(packageName)
        ) {
            val newEpisode = recordDeliveryFailureEpisodeLocked(packageName, now)
            val verifiedFrozen = packageHasVerifiedFrozenProcessLocked(packageName)
            if (packageName in DELIVERY_PACKAGE_TARGETS) {
                startOrExtendDeliveryProtectionLocked(
                    packageName = packageName,
                    now = now,
                    signalKind = signal.kind,
                    newDeliveryEpisode = newEpisode,
                    verifiedFrozen = verifiedFrozen
                )
            }
            val activeGuard = packageSuccessorGuardByPackage[packageName]
            if (activeGuard != null) {
                if (newEpisode) {
                    eventLocked(
                        "package_process_rebuild_deferred",
                        "$packageName reason=successor_guard_active critical=true frozen=$verifiedFrozen " +
                            "generation=${activeGuard.generation} reset=${activeGuard.resetCount}"
                    )
                }
            } else {
                val criticalDecision = RecoveryCampaignPolicy.decideCriticalPackageRebuild(
                    nowElapsed = now,
                    lastRebuildElapsed = lastPackageRebuildByPackage[packageName] ?: 0L,
                    rebuildHistory = packageRebuildHistoryByPackage[packageName]?.toList().orEmpty(),
                    verifiedFrozen = verifiedFrozen,
                    newDeliveryEpisode = newEpisode
                )
                if (criticalDecision.allowed) {
                    maybeSchedulePackageRebuildLocked(
                        packageName = packageName,
                        now = now,
                        verifiedFrozenAfterBurst = true,
                        forcedReason = criticalDecision.reason
                    )
                } else if (newEpisode) {
                    eventLocked(
                        "package_process_rebuild_deferred",
                        "$packageName reason=${criticalDecision.reason} critical=true frozen=$verifiedFrozen"
                    )
                }
            }
        }
    }

    private fun recordGmsTransportLogSignalLocked(signal: GmsTransportLogSignal, now: Long) {
        when (signal.kind) {
            GmsTransportLogSignalKind.BAD_AUTHENTICATION -> {
                val previous = lastGmsBadAuthenticationElapsed
                if (
                    previous <= 0L || previous > now ||
                    now - previous >= GMS_LOG_SIGNAL_DEBOUNCE_MS
                ) {
                    lastGmsBadAuthenticationElapsed = now
                    gmsBadAuthenticationCount += 1
                    eventLocked(
                        "gms_bad_authentication",
                        "count=$gmsBadAuthenticationCount source=logcat_redacted"
                    )
                }
            }
            GmsTransportLogSignalKind.MCS_CONNECT_ATTEMPT -> {
                lastGmsMcsConnectAttemptElapsed = now
                eventLocked("gms_mcs_connect_attempt", "source=logcat_redacted")
            }
        }
    }

    private fun recordDeliveryFailureEpisodeLocked(packageName: String, now: Long): Boolean {
        val previous = lastDeliveryEpisodeByPackage[packageName]
        if (!DeliveryFailureEscalationPolicy.shouldRecordEpisode(previous, now)) return false
        lastDeliveryEpisodeByPackage[packageName] = now
        val episodes = deliveryFailureEpisodesByPackage.getOrPut(packageName) { ArrayDeque() }
        episodes.addLast(now)
        pruneDeliveryFailureStateLocked(packageName, now)
        eventLocked(
            "delivery_failure_episode",
            "$packageName count=${episodes.size} windowMs=${DeliveryFailureEscalationPolicy.EVIDENCE_WINDOW_MS}"
        )
        return true
    }

    private fun packageHasVerifiedFrozenProcessLocked(packageName: String): Boolean {
        val processes = GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage(packageName)
        )
        return processes.any { process -> readFreezeState(process.pid).frozen == true }
    }

    private fun maybeSchedulePackageRebuildLocked(
        packageName: String,
        now: Long,
        verifiedFrozenAfterBurst: Boolean = false,
        forcedReason: String? = null
    ) {
        if (packageRebuildInProgress.contains(packageName)) return
        val activeProtection = deliveryProtectionByPackage[packageName]
        if (activeProtection != null && activeProtection.deadlineElapsed > now) {
            eventLocked(
                "package_process_rebuild_deferred",
                "$packageName reason=delivery_protection_active until=${activeProtection.deadlineElapsed} " +
                    "episodes=${activeProtection.deliveryEpisodeCount} kills=${activeProtection.killCount}"
            )
            return
        }
        val circuitUntil = packageSuccessorCircuitUntilByPackage[packageName] ?: 0L
        if (circuitUntil > now) {
            val rescueAllowed =
                packageName in DELIVERY_PACKAGE_TARGETS &&
                    RecoveryCampaignPolicy.shouldAttemptCircuitDeliveryRescue(
                        nowElapsed = now,
                        circuitUntilElapsed = circuitUntil,
                        lastRescueElapsed =
                            lastPackageCircuitDeliveryRescueByPackage[packageName] ?: 0L,
                        verifiedFrozen = verifiedFrozenAfterBurst
                    )
            if (rescueAllowed) {
                lastPackageCircuitDeliveryRescueByPackage[packageName] = now
                attemptCircuitDeliveryRescueLocked(
                    packageName = packageName,
                    circuitUntil = circuitUntil
                )
            }
            eventLocked(
                "package_process_rebuild_deferred",
                "$packageName reason=oem_refreeze_circuit_breaker until=$circuitUntil " +
                    "deliveryRescue=$rescueAllowed"
            )
            return
        }
        if (circuitUntil > 0L) {
            packageSuccessorCircuitUntilByPackage.remove(packageName)
            eventLocked(
                "package_successor_circuit_breaker_closed",
                "$packageName previousUntil=$circuitUntil"
            )
        }
        pruneDeliveryFailureStateLocked(packageName, now)
        val decision = if (forcedReason.isNullOrBlank()) {
            DeliveryFailureEscalationPolicy.decide(
                packageName = packageName,
                nowElapsed = now,
                deliveryEpisodes = deliveryFailureEpisodesByPackage[packageName]?.toList().orEmpty(),
                lastRebuildElapsed = lastPackageRebuildByPackage[packageName] ?: 0L,
                rebuildHistory = packageRebuildHistoryByPackage[packageName]?.toList().orEmpty(),
                verifiedFrozenAfterBurst = verifiedFrozenAfterBurst
            )
        } else {
            DeliveryFailureEscalationPolicy.Decision(true, forcedReason)
        }
        if (!decision.allowed) {
            eventLocked("package_process_rebuild_deferred", "$packageName reason=${decision.reason}")
            return
        }
        packageRebuildInProgress += packageName
        runCatching {
            executor.execute {
                synchronized(lock) {
                    if (!running) {
                        packageRebuildInProgress.remove(packageName)
                        return@synchronized
                    }
                    rebuildPackageProcessLocked(packageName, decision.reason)
                }
            }
        }.onFailure { error ->
            packageRebuildInProgress.remove(packageName)
            errorCount += 1
            eventLocked(
                "package_process_rebuild_schedule_failed",
                "$packageName ${error.javaClass.simpleName}:${error.message}"
            )
        }
    }

    private fun startOrExtendDeliveryProtectionLocked(
        packageName: String,
        now: Long,
        signalKind: VendorFreezeSignalKind,
        newDeliveryEpisode: Boolean,
        verifiedFrozen: Boolean
    ) {
        if (packageName !in DELIVERY_PACKAGE_TARGETS) return
        val interactive = readScreenInteractiveLocked()
        if (interactive != false) {
            val processes = GuardianProcessParser.matching(
                listProcessesLocked(),
                processTargetsForPackage(packageName)
            )
            processes.filter { readFreezeState(it.pid).frozen == true }.forEach { process ->
                val result = unfreezeLocked(process)
                if (!result.stdout.contains("not_applicable_secondary_process")) {
                    actionCount += 1
                    if (!isUnfreezeAccepted(result)) commandFailureCount += 1
                }
            }
            tunePackageLocked(packageName)
            eventLocked(
                "package_delivery_protection_deferred",
                "$packageName signal=$signalKind screenInteractive=${interactive ?: "unknown"}"
            )
            return
        }

        val existing = deliveryProtectionByPackage[packageName]
        if (existing == null || existing.deadlineElapsed <= now) {
            existing?.let { finishDeliveryProtectionLocked(packageName, it.generation, "expired_replaced") }
            val generation = deliveryProtectionStartCount + 1L
            val deadline = RecoveryCampaignPolicy.deliveryProtectionDeadline(
                nowElapsed = now,
                startedElapsed = now,
                currentDeadlineElapsed = 0L,
                newDeliveryEpisode = true
            )
            val lease = DeliveryProtectionLease(
                packageName = packageName,
                generation = generation,
                startedElapsed = now,
                deadlineElapsed = deadline,
                lastSignalElapsed = now,
                deliveryEpisodeCount = 1
            )
            deliveryProtectionByPackage[packageName] = lease
            deliveryProtectionStartCount = generation
            eventLocked(
                "package_delivery_protection_started",
                "$packageName generation=$generation signal=$signalKind deadline=$deadline " +
                    "verifiedFrozen=$verifiedFrozen"
            )
            performDeliveryProtectionPassLocked(lease, initial = true)
            deliveryProtectionFutureByPackage.remove(packageName)?.cancel(false)
            deliveryProtectionFutureByPackage[packageName] = executor.scheduleWithFixedDelay(
                {
                    synchronized(lock) {
                        runCatching {
                            runDeliveryProtectionTickLocked(packageName, generation)
                        }.onFailure { error ->
                            errorCount += 1
                            eventLocked(
                                "package_delivery_protection_failed",
                                "$packageName generation=$generation " +
                                    "${error.javaClass.simpleName}:${error.message}"
                            )
                            finishDeliveryProtectionLocked(
                                packageName,
                                generation,
                                "exception:${error.javaClass.simpleName}"
                            )
                        }
                    }
                },
                RecoveryCampaignPolicy.PACKAGE_DELIVERY_PROTECTION_TICK_MS,
                RecoveryCampaignPolicy.PACKAGE_DELIVERY_PROTECTION_TICK_MS,
                TimeUnit.MILLISECONDS
            )
            return
        }

        existing.lastSignalElapsed = now
        if (newDeliveryEpisode) existing.deliveryEpisodeCount += 1
        existing.deadlineElapsed = RecoveryCampaignPolicy.deliveryProtectionDeadline(
            nowElapsed = now,
            startedElapsed = existing.startedElapsed,
            currentDeadlineElapsed = existing.deadlineElapsed,
            newDeliveryEpisode = newDeliveryEpisode
        )
        eventLocked(
            "package_delivery_protection_extended",
            "$packageName generation=${existing.generation} signal=$signalKind " +
                "episode=$newDeliveryEpisode episodes=${existing.deliveryEpisodeCount} " +
                "deadline=${existing.deadlineElapsed}"
        )
        if (
            RecoveryCampaignPolicy.shouldEscalateDeliveryProtectionKill(
                nowElapsed = now,
                startedElapsed = existing.startedElapsed,
                deliveryEpisodeCount = existing.deliveryEpisodeCount,
                killCount = existing.killCount,
                verifiedFrozen = verifiedFrozen
            )
        ) {
            escalateDeliveryProtectionKillLocked(existing, signalKind)
        } else {
            performDeliveryProtectionPassLocked(existing, initial = false)
        }
    }

    private fun runDeliveryProtectionTickLocked(packageName: String, generation: Long) {
        val lease = deliveryProtectionByPackage[packageName] ?: return
        if (lease.generation != generation) return
        if (!running) {
            finishDeliveryProtectionLocked(packageName, generation, "engine_stopped")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val interactive = readScreenInteractiveLocked()
        if (interactive == true) {
            finishDeliveryProtectionLocked(packageName, generation, "screen_interactive")
            return
        }
        if (now >= lease.deadlineElapsed) {
            finishDeliveryProtectionLocked(packageName, generation, "deadline_complete")
            return
        }
        performDeliveryProtectionPassLocked(lease, initial = false)
        persistStatusLocked()
    }

    private fun performDeliveryProtectionPassLocked(
        lease: DeliveryProtectionLease,
        initial: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        val details = mutableListOf<String>()
        if (
            initial || lease.lastTuneElapsed <= 0L ||
            now - lease.lastTuneElapsed >= DELIVERY_PROTECTION_TUNE_INTERVAL_MS
        ) {
            tunePackageLocked(lease.packageName)
            tunePackageLocked(GMS_PACKAGE)
            lease.lastTuneElapsed = now
            details += "tune"
        }

        var processes = GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage(lease.packageName)
        )
        if (processes.isEmpty()) {
            val unstop = runPackageUnstopWithRetryLocked(lease.packageName)
            details += "package_unstop:${unstop.summary()}"
            if (!unstop.success) commandFailureCount += 1
        }
        processes.forEach { process ->
            if (readFreezeState(process.pid).frozen == true) {
                val result = unfreezeLocked(process)
                if (!result.stdout.contains("not_applicable_secondary_process")) {
                    actionCount += 1
                    details += "unfreeze_${process.pid}:${result.summary()}"
                    if (!isUnfreezeAccepted(result)) commandFailureCount += 1
                }
            }
        }

        val gmsProcesses = listGmsProcessesLocked()
        gmsProcesses.filter { readFreezeState(it.pid).frozen == true }.forEach { process ->
            val result = unfreezeLocked(process)
            if (!result.stdout.contains("not_applicable_secondary_process")) {
                actionCount += 1
                details += "gms_unfreeze_${process.pid}:${result.summary()}"
                if (!isUnfreezeAccepted(result)) commandFailureCount += 1
            }
        }
        if (
            initial || lease.lastBinderPulseElapsed <= 0L ||
            now - lease.lastBinderPulseElapsed >= DELIVERY_PROTECTION_BINDER_PULSE_INTERVAL_MS
        ) {
            val pulse = sendGmsBinderPulseLocked()
            details += "gms_binder_pulse:${pulse.summary()}"
            lease.lastBinderPulseElapsed = now
        }

        processes = GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage(lease.packageName)
        )
        val anyFrozen = processes.any { readFreezeState(it.pid).frozen == true }
        if (initial || processes.isEmpty() || anyFrozen || lease.lastLauncherElapsed <= 0L) {
            val launcher = startPackageForegroundLocked(
                packageName = lease.packageName,
                reason = "delivery_protection"
            )
            details += "launcher=${launcher.name.lowercase()}"
            if (launcher == BackgroundLauncherWakeResult.STARTED) {
                lease.lastLauncherElapsed = now
            }
        }
        if (details.isNotEmpty()) {
            eventLocked(
                "package_delivery_protection_pass",
                "${lease.packageName} generation=${lease.generation} initial=$initial " +
                    details.joinToString(" | ").take(1_500)
            )
        }
    }

    private fun escalateDeliveryProtectionKillLocked(
        lease: DeliveryProtectionLease,
        signalKind: VendorFreezeSignalKind
    ) {
        lease.killCount += 1
        deliveryProtectionKillCount += 1
        val oldPids = GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage(lease.packageName)
        ).mapTo(linkedSetOf()) { it.pid }
        eventLocked(
            "package_delivery_protection_kill_started",
            "${lease.packageName} generation=${lease.generation} signal=$signalKind " +
                "oldPids=${oldPids.sorted()}"
        )
        val result = runner.run(
            "am", "kill", "--user", "0", lease.packageName,
            timeoutMs = PACKAGE_KILL_TIMEOUT_MS
        )
        actionCount += 1
        if (!result.success) commandFailureCount += 1
        val remaining = waitForOldPidsRemovedLocked(
            lease.packageName,
            oldPids,
            PACKAGE_KILL_VERIFY_WAIT_MS
        )
        val unstop = runPackageUnstopWithRetryLocked(lease.packageName)
        if (!unstop.success) commandFailureCount += 1
        tunePackageLocked(lease.packageName)
        val launcher = startPackageForegroundLocked(
            packageName = lease.packageName,
            reason = "delivery_protection_kill_successor"
        )
        if (launcher == BackgroundLauncherWakeResult.STARTED) {
            lease.lastLauncherElapsed = SystemClock.elapsedRealtime()
        }
        eventLocked(
            "package_delivery_protection_kill_completed",
            "${lease.packageName} generation=${lease.generation} remaining=${remaining.sorted()} " +
                "kill=${result.summary()} unstop=${unstop.summary()} launcher=${launcher.name.lowercase()}"
        )
    }

    private fun finishDeliveryProtectionLocked(
        packageName: String,
        generation: Long,
        reason: String
    ) {
        val lease = deliveryProtectionByPackage[packageName] ?: return
        if (lease.generation != generation) return
        deliveryProtectionFutureByPackage.remove(packageName)?.cancel(false)
        deliveryProtectionByPackage.remove(packageName)
        returnHomeLocked("delivery_protection_$reason")
        deliveryProtectionCompletionCount += 1
        eventLocked(
            "package_delivery_protection_finished",
            "$packageName generation=$generation reason=$reason episodes=${lease.deliveryEpisodeCount} " +
                "kills=${lease.killCount} durationMs=" +
                (SystemClock.elapsedRealtime() - lease.startedElapsed).coerceAtLeast(0L)
        )
    }

    private fun cancelDeliveryProtectionLeasesLocked(reason: String) {
        val leases = deliveryProtectionByPackage.values.toList()
        deliveryProtectionFutureByPackage.values.forEach { it.cancel(false) }
        deliveryProtectionFutureByPackage.clear()
        deliveryProtectionByPackage.clear()
        if (leases.isNotEmpty()) returnHomeLocked("delivery_protection_cancel_$reason")
        leases.forEach { lease ->
            eventLocked(
                "package_delivery_protection_cancelled",
                "${lease.packageName} generation=${lease.generation} reason=$reason"
            )
        }
    }

    private fun attemptCircuitDeliveryRescueLocked(
        packageName: String,
        circuitUntil: Long
    ) {
        packageCircuitDeliveryRescueCount += 1
        val details = mutableListOf<String>()
        tunePackageLocked(packageName)

        val processes = GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage(packageName)
        )
        processes.forEach { process ->
            val before = readFreezeState(process.pid)
            val unfreeze = unfreezeLocked(process)
            if (!unfreeze.stdout.contains("not_applicable_secondary_process")) {
                actionCount += 1
                details += "unfreeze_${process.pid}:${unfreeze.summary()}"
                if (!isUnfreezeAccepted(unfreeze)) commandFailureCount += 1
            }
            val afterActivityManager = readFreezeState(process.pid)
            if (
                afterActivityManager.frozen == true &&
                config.rootCgroupThaw &&
                before.controlFile != null
            ) {
                directCgroupAttemptCount += 1
                actionCount += 1
                val direct = directCgroupThaw(before) == true
                details += "cgroup_${process.pid}:$direct"
                if (direct) {
                    directCgroupSuccessCount += 1
                } else {
                    commandFailureCount += 1
                }
            }
        }

        val gmsUnfreeze = unfreezePackageLocked(GMS_PACKAGE)
        actionCount += 1
        details += "gms_unfreeze:${gmsUnfreeze.summary()}"
        if (!isUnfreezeAccepted(gmsUnfreeze)) commandFailureCount += 1
        val binderPulse = sendGmsBinderPulseLocked()
        details += "gms_binder_pulse:${binderPulse.summary()}"
        tunePackageLocked(GMS_PACKAGE)

        val now = SystemClock.elapsedRealtime()
        startOrExtendDeliveryProtectionLocked(
            packageName = packageName,
            now = now,
            signalKind = VendorFreezeSignalKind.GCM_DELIVERY_CANCELLED,
            // The event path has already debounced and recorded the real
            // delivery episode. Circuit rescue must not manufacture a second
            // episode and prematurely consume the one-kill escalation budget.
            newDeliveryEpisode = false,
            verifiedFrozen = true
        )
        details += "delivery_protection=started_or_extended"

        val remaining = GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage(packageName)
        ).filter { process -> readFreezeState(process.pid).frozen == true }
        eventLocked(
            if (remaining.isEmpty()) {
                "package_circuit_delivery_rescue_verified"
            } else {
                "package_circuit_delivery_rescue_unresolved"
            },
            "$packageName circuitUntil=$circuitUntil remaining=${remaining.map { it.pid }.sorted()} " +
                details.joinToString(" | ").take(1_500)
        )
    }

    private fun rebuildPackageProcessLocked(packageName: String, trigger: String) {
        try {
            val removal = performPackageProcessRemovalLocked(packageName, trigger)
            if (removal.verified) {
                startPackageSuccessorGuardLocked(packageName, trigger)
            }
        } catch (error: Throwable) {
            errorCount += 1
            lastPackageRebuildOutcome = PackageRebuildOutcome(
                packageName = packageName,
                trigger = trigger,
                result = "exception:${error.javaClass.simpleName}",
                startedElapsed = SystemClock.elapsedRealtime(),
                completedElapsed = SystemClock.elapsedRealtime()
            )
            eventLocked(
                "package_process_rebuild_failed",
                "$packageName ${error.javaClass.simpleName}:${error.message}"
            )
        } finally {
            packageRebuildInProgress.remove(packageName)
            persistStatusLocked(force = true)
        }
    }

    private fun performPackageProcessRemovalLocked(
        packageName: String,
        trigger: String,
        requestedStrategy: RecoveryCampaignPolicy.PackageResetStrategy =
            RecoveryCampaignPolicy.PackageResetStrategy.KILL
    ): PackageProcessRemoval {
        val started = SystemClock.elapsedRealtime()
        packageRebuildAttemptCount += 1
        val oldProcesses = GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage(packageName)
        )
        val oldPids = oldProcesses.mapTo(linkedSetOf()) { it.pid }
        if (oldPids.isEmpty()) {
            pulseAbsentPackageLocked(
                packageName = packageName,
                reason = "rebuild_no_matching_process"
            )
            val completed = SystemClock.elapsedRealtime()
            lastPackageRebuildByPackage[packageName] = completed
            packageRebuildHistoryByPackage
                .getOrPut(packageName) { ArrayDeque() }
                .addLast(completed)
            pruneDeliveryFailureStateLocked(packageName, completed)
            lastPackageRebuildOutcome = PackageRebuildOutcome(
                packageName = packageName,
                trigger = trigger,
                result = "process_already_absent_recovery_started",
                startedElapsed = started,
                completedElapsed = completed
            )
            eventLocked(
                "package_process_absent_recovery_started",
                "$packageName trigger=$trigger"
            )
            return PackageProcessRemoval(
                verified = true,
                oldPids = emptySet(),
                remainingOldPids = emptySet(),
                commandDetail = "process_already_absent",
                result = "process_already_absent_recovery_started"
            )
        }

        val commandDetails = mutableListOf<String>()
        var strategy = requestedStrategy
        if (strategy == RecoveryCampaignPolicy.PackageResetStrategy.FORCE_STOP_UNSTOP) {
            val canUnstop = verifyPackageUnstopBeforeForceStopLocked(packageName, commandDetails)
            if (!canUnstop) {
                strategy = if (supportsStopApp) {
                    RecoveryCampaignPolicy.PackageResetStrategy.STOP_APP
                } else {
                    RecoveryCampaignPolicy.PackageResetStrategy.KILL
                }
                eventLocked(
                    "package_force_stop_blocked",
                    "$packageName trigger=$trigger reason=package_unstop_unavailable fallback=$strategy"
                )
            }
        }
        if (strategy == RecoveryCampaignPolicy.PackageResetStrategy.STOP_APP && !supportsStopApp) {
            strategy = RecoveryCampaignPolicy.PackageResetStrategy.KILL
        }

        eventLocked(
            "package_process_rebuild_started",
            "$packageName trigger=$trigger oldPids=${oldPids.sorted()} strategy=$strategy"
        )

        var remainingOldPids: Set<Int> = oldPids
        var transitionVerified = false
        when (strategy) {
            RecoveryCampaignPolicy.PackageResetStrategy.KILL -> {
                val killResult = runner.run(
                    "am", "kill", "--user", "0", packageName,
                    timeoutMs = PACKAGE_KILL_TIMEOUT_MS
                )
                actionCount += 1
                commandDetails += "am_kill:${killResult.summary()}"
                if (!killResult.success) commandFailureCount += 1
                remainingOldPids = waitForOldPidsRemovedLocked(
                    packageName = packageName,
                    oldPids = oldPids,
                    waitMs = PACKAGE_KILL_VERIFY_WAIT_MS
                )
                if (remainingOldPids.isNotEmpty() && supportsStopApp) {
                    eventLocked(
                        "package_process_rebuild_escalated",
                        "$packageName trigger=$trigger action=am_stop_app remaining=${remainingOldPids.sorted()}"
                    )
                    val stopResult = runner.run(
                        "am", "stop-app", "--user", "0", packageName,
                        timeoutMs = PACKAGE_STOP_APP_TIMEOUT_MS
                    )
                    actionCount += 1
                    commandDetails += "am_stop_app:${stopResult.summary()}"
                    if (!stopResult.success) commandFailureCount += 1
                    remainingOldPids = waitForOldPidsRemovedLocked(
                        packageName = packageName,
                        oldPids = oldPids,
                        waitMs = PACKAGE_STOP_VERIFY_WAIT_MS
                    )
                }
                transitionVerified = remainingOldPids.isEmpty()
            }

            RecoveryCampaignPolicy.PackageResetStrategy.STOP_APP -> {
                val stopResult = runner.run(
                    "am", "stop-app", "--user", "0", packageName,
                    timeoutMs = PACKAGE_STOP_APP_TIMEOUT_MS
                )
                actionCount += 1
                commandDetails += "am_stop_app:${stopResult.summary()}"
                if (!stopResult.success) commandFailureCount += 1
                remainingOldPids = waitForOldPidsRemovedLocked(
                    packageName = packageName,
                    oldPids = oldPids,
                    waitMs = PACKAGE_STOP_VERIFY_WAIT_MS
                )
                if (remainingOldPids.isNotEmpty()) {
                    val killResult = runner.run(
                        "am", "kill", "--user", "0", packageName,
                        timeoutMs = PACKAGE_KILL_TIMEOUT_MS
                    )
                    actionCount += 1
                    commandDetails += "am_kill_fallback:${killResult.summary()}"
                    if (!killResult.success) commandFailureCount += 1
                    remainingOldPids = waitForOldPidsRemovedLocked(
                        packageName = packageName,
                        oldPids = oldPids,
                        waitMs = PACKAGE_KILL_VERIFY_WAIT_MS
                    )
                }
                transitionVerified = remainingOldPids.isEmpty()
            }

            RecoveryCampaignPolicy.PackageResetStrategy.FORCE_STOP_UNSTOP -> {
                var forceStopAccepted = false
                for (attempt in 1..PACKAGE_FORCE_STOP_MAX_ATTEMPTS) {
                    val forceResult = runner.run(
                        "am", "force-stop", "--user", "0", packageName,
                        timeoutMs = PACKAGE_FORCE_STOP_TIMEOUT_MS
                    )
                    actionCount += 1
                    commandDetails += "am_force_stop#$attempt:${forceResult.summary()}"
                    if (forceResult.success) {
                        forceStopAccepted = true
                    } else {
                        commandFailureCount += 1
                    }
                    remainingOldPids = waitForOldPidsRemovedLocked(
                        packageName = packageName,
                        oldPids = oldPids,
                        waitMs = PACKAGE_STOP_VERIFY_WAIT_MS
                    )
                    if (remainingOldPids.isEmpty()) break
                    SystemClock.sleep(PACKAGE_FORCE_STOP_RETRY_DELAY_MS)
                }
                SystemClock.sleep(PACKAGE_FORCE_STOP_SETTLE_MS)
                val unstopResult = runPackageUnstopWithRetryLocked(packageName)
                commandDetails += "package_unstop:${unstopResult.summary()}"
                if (!unstopResult.success) {
                    commandFailureCount += 1
                    errorCount += 1
                } else {
                    SystemClock.sleep(PACKAGE_POST_UNSTOP_SETTLE_MS)
                }
                val stoppedFlag = readPackageStoppedFlagLocked(packageName)
                transitionVerified = forceStopAccepted &&
                    remainingOldPids.isEmpty() &&
                    unstopResult.success &&
                    stoppedFlag == false
                eventLocked(
                    if (transitionVerified) {
                        "package_force_stop_unstop_verified"
                    } else {
                        "package_force_stop_unstop_unverified"
                    },
                    "$packageName trigger=$trigger remaining=${remainingOldPids.sorted()} " +
                        "forceStopAccepted=$forceStopAccepted unstopAccepted=${unstopResult.success} " +
                        "stopped=$stoppedFlag"
                )
                if (unstopResult.success) {
                    sendGmsBinderPulseLocked()
                    wakeGmsDependentsLocked()
                }
            }
        }

        val actionElapsed = SystemClock.elapsedRealtime()
        lastPackageRebuildByPackage[packageName] = actionElapsed
        packageRebuildHistoryByPackage
            .getOrPut(packageName) { ArrayDeque() }
            .addLast(actionElapsed)
        pruneDeliveryFailureStateLocked(packageName, actionElapsed)

        val verified = remainingOldPids.isEmpty() && transitionVerified
        val outcome = if (verified) {
            "old_process_removed_${strategy.name.lowercase()}"
        } else {
            "process_transition_unverified_${strategy.name.lowercase()}"
        }
        if (verified) {
            packageRebuildSuccessCount += 1
            deliveryFailureEpisodesByPackage[packageName]?.clear()
            val gmsUnfreeze = unfreezePackageLocked(GMS_PACKAGE)
            actionCount += 1
            if (!isUnfreezeAccepted(gmsUnfreeze)) commandFailureCount += 1
            tunePackageLocked(packageName)
            vendorHoldUntilByPackage[packageName] = maxOf(
                vendorHoldUntilByPackage[packageName] ?: 0L,
                SystemClock.elapsedRealtime() + VendorFreezeRecoveryPolicy.DELIVERY_CRITICAL_HOLD_MS
            )
            eventLocked(
                "package_process_rebuild_succeeded",
                "$packageName oldPids=${oldPids.sorted()} strategy=$strategy " +
                    "waitMs=${SystemClock.elapsedRealtime() - started}"
            )
        } else {
            errorCount += 1
            eventLocked(
                "package_process_rebuild_unverified",
                "$packageName outcome=$outcome commands=${commandDetails.joinToString(" | ")} " +
                    "remaining=${remainingOldPids.sorted()}"
            )
        }

        val detail = commandDetails.joinToString(" | ")
        lastPackageRebuildOutcome = PackageRebuildOutcome(
            packageName = packageName,
            trigger = trigger,
            result = outcome,
            oldPids = oldPids.toList().sorted(),
            remainingOldPids = remainingOldPids.toList().sorted(),
            commandDetail = detail,
            startedElapsed = started,
            completedElapsed = SystemClock.elapsedRealtime()
        )
        return PackageProcessRemoval(
            verified = verified,
            oldPids = oldPids,
            remainingOldPids = remainingOldPids,
            commandDetail = detail,
            result = outcome
        )
    }

    private fun waitForOldPidsRemovedLocked(
        packageName: String,
        oldPids: Set<Int>,
        waitMs: Long
    ): Set<Int> {
        if (oldPids.isEmpty()) return emptySet()
        val deadline = SystemClock.elapsedRealtime() + waitMs.coerceAtLeast(0L)
        while (true) {
            val currentPids = GuardianProcessParser.matching(
                listProcessesLocked(),
                processTargetsForPackage(packageName)
            ).mapTo(linkedSetOf()) { it.pid }
            val remaining = oldPids.intersect(currentPids)
            if (remaining.isEmpty()) return emptySet()
            if (SystemClock.elapsedRealtime() >= deadline) return remaining
            SystemClock.sleep(PACKAGE_KILL_VERIFY_POLL_MS)
        }
    }

    private fun startPackageSuccessorGuardLocked(packageName: String, trigger: String) {
        packageSuccessorGuardFutureByPackage.remove(packageName)?.cancel(false)
        val generation = packageSuccessorGuardStartCount + 1L
        packageSuccessorGuardStartCount = generation
        val now = SystemClock.elapsedRealtime()
        val guard = PackageSuccessorGuard(
            packageName = packageName,
            trigger = trigger,
            generation = generation,
            startedElapsed = now,
            deadlineElapsed = now + RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_GUARD_DURATION_MS
        )
        packageSuccessorGuardByPackage[packageName] = guard
        eventLocked(
            "package_successor_guard_started",
            "$packageName generation=$generation trigger=$trigger durationMs=" +
                RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_GUARD_DURATION_MS
        )
        tunePackageLocked(packageName)
        val future = executor.scheduleWithFixedDelay(
            {
                synchronized(lock) {
                    runCatching { runPackageSuccessorGuardTickLocked(packageName, generation) }
                        .onFailure { error ->
                            errorCount += 1
                            eventLocked(
                                "package_successor_guard_failed",
                                "$packageName generation=$generation ${error.javaClass.simpleName}:${error.message}"
                            )
                            finishPackageSuccessorGuardLocked(
                                packageName,
                                generation,
                                "exception:${error.javaClass.simpleName}"
                            )
                        }
                }
            },
            0L,
            RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_GUARD_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        packageSuccessorGuardFutureByPackage[packageName] = future
    }

    private fun runPackageSuccessorGuardTickLocked(packageName: String, generation: Long) {
        if (!running) {
            finishPackageSuccessorGuardLocked(packageName, generation, "engine_stopped")
            return
        }
        val guard = packageSuccessorGuardByPackage[packageName] ?: return
        if (guard.generation != generation) return
        val now = SystemClock.elapsedRealtime()
        val processes = GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage(packageName)
        )
        val currentPids = processes.mapTo(linkedSetOf()) { it.pid }
        if (currentPids != guard.lastObservedPids) {
            eventLocked(
                "package_successor_observed",
                "$packageName generation=$generation pids=${currentPids.sorted()} previous=${guard.lastObservedPids.sorted()}"
            )
            guard.lastObservedPids = currentPids
            guard.frozenPids.clear()
            guard.stableSinceElapsed = 0L
            processes.forEach { process ->
                val result = unfreezeLocked(process)
                if (!result.stdout.contains("not_applicable_secondary_process")) {
                    actionCount += 1
                    if (!isUnfreezeAccepted(result)) commandFailureCount += 1
                }
            }
        }

        if (processes.isEmpty()) {
            guard.stableSinceElapsed = 0L
            if (guard.absentSinceElapsed <= 0L || guard.absentSinceElapsed > now) {
                guard.absentSinceElapsed = now
                eventLocked(
                    "package_successor_absent",
                    "$packageName generation=$generation reset=${guard.resetCount}"
                )
            }
            if (
                RecoveryCampaignPolicy.shouldPulseAbsentPackageSuccessor(
                    nowElapsed = now,
                    absentSinceElapsed = guard.absentSinceElapsed,
                    lastPulseElapsed = guard.lastAbsencePulseElapsed
                )
            ) {
                guard.lastAbsencePulseElapsed = now
                pulseAbsentPackageLocked(
                    packageName = packageName,
                    reason = "successor_absent_generation_$generation"
                )
            }
            if (
                RecoveryCampaignPolicy.shouldBackgroundLaunchAbsentPackageSuccessor(
                    nowElapsed = now,
                    absentSinceElapsed = guard.absentSinceElapsed,
                    lastLaunchElapsed = guard.lastBackgroundLaunchElapsed,
                    launchCount = guard.backgroundLaunchCount
                )
            ) {
                guard.lastBackgroundLaunchElapsed = now
                val result = attemptBackgroundLauncherWakeLocked(
                    packageName = packageName,
                    reason = "successor_absent_generation_$generation"
                )
                if (result != BackgroundLauncherWakeResult.DEFERRED_SCREEN_INTERACTIVE) {
                    guard.backgroundLaunchCount += 1
                }
                eventLocked(
                    "package_successor_background_launch",
                    "$packageName generation=$generation attempts=${guard.backgroundLaunchCount} " +
                        "result=${result.name.lowercase()}"
                )
            }
            if (now >= guard.deadlineElapsed) {
                finishPackageSuccessorGuardLocked(packageName, generation, "expired_process_absent")
            }
            persistStatusLocked()
            return
        }

        if (guard.absentSinceElapsed > 0L) {
            eventLocked(
                "package_successor_returned",
                "$packageName generation=$generation absentMs=${now - guard.absentSinceElapsed} " +
                    "launches=${guard.backgroundLaunchCount} pids=${currentPids.sorted()}"
            )
            guard.absentSinceElapsed = 0L
            guard.lastAbsencePulseElapsed = 0L
            guard.lastBackgroundLaunchElapsed = 0L
            guard.backgroundLaunchCount = 0
        }

        val frozenBefore = processes.filter { readFreezeState(it.pid).frozen == true }
        val frozenBeforePids = frozenBefore.mapTo(linkedSetOf()) { it.pid }
        val newlyFrozen = frozenBeforePids - guard.frozenPids
        if (newlyFrozen.isNotEmpty()) {
            packageSuccessorGuardRefreezeCount += newlyFrozen.size
            guard.refreezeCount += newlyFrozen.size
            eventLocked(
                "package_successor_refrozen",
                "$packageName generation=$generation pids=${newlyFrozen.sorted()} resetCount=${guard.resetCount}"
            )
        }
        guard.frozenPids.clear()
        guard.frozenPids.addAll(frozenBeforePids)

        frozenBefore.forEach { process ->
            val result = unfreezeLocked(process)
            if (!result.stdout.contains("not_applicable_secondary_process")) {
                actionCount += 1
                if (!isUnfreezeAccepted(result)) commandFailureCount += 1
            }
        }
        val frozenAfter = processes.filter { readFreezeState(it.pid).frozen == true }
        val verifiedFrozen = frozenAfter.isNotEmpty()
        if (!verifiedFrozen) {
            guard.frozenPids.clear()
            if (guard.stableSinceElapsed <= 0L || guard.stableSinceElapsed > now) {
                guard.stableSinceElapsed = now
            }
            if (now - guard.stableSinceElapsed >= RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_STABLE_MS) {
                packageSuccessorGuardVerifiedCount += 1
                finishPackageSuccessorGuardLocked(packageName, generation, "stable_thawed")
                return
            }
        } else {
            guard.stableSinceElapsed = 0L
            val vendorFamily = currentVendorFamilyLocked()
            if (
                RecoveryCampaignPolicy.shouldOpenPackageSuccessorCircuit(
                    vendorFamily = vendorFamily,
                    resetCount = guard.resetCount,
                    refreezeCount = guard.refreezeCount
                )
            ) {
                if (!guard.circuitRescueAttempted) {
                    guard.circuitRescueAttempted = true
                    val rescue = attemptBackgroundLauncherWakeLocked(
                        packageName = packageName,
                        reason = "oem_refreeze_circuit_rescue_generation_$generation",
                        foregroundHoldMs =
                            RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_CIRCUIT_RESCUE_HOLD_MS
                    )
                    eventLocked(
                        "package_successor_circuit_rescue",
                        "$packageName generation=$generation resets=${guard.resetCount} " +
                            "refreezes=${guard.refreezeCount} result=${rescue.name.lowercase()}"
                    )
                    val afterRescueProcesses = GuardianProcessParser.matching(
                        listProcessesLocked(),
                        processTargetsForPackage(packageName)
                    )
                    val stillFrozenAfterRescue = afterRescueProcesses.any { process ->
                        readFreezeState(process.pid).frozen == true
                    }
                    if (!stillFrozenAfterRescue) {
                        guard.frozenPids.clear()
                        guard.stableSinceElapsed = SystemClock.elapsedRealtime()
                        persistStatusLocked()
                        return
                    }
                }

                val until =
                    SystemClock.elapsedRealtime() +
                        RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_CIRCUIT_BREAKER_COOLDOWN_MS
                packageSuccessorCircuitUntilByPackage[packageName] = until
                eventLocked(
                    "package_successor_circuit_breaker_opened",
                    "$packageName generation=$generation vendor=$vendorFamily " +
                        "resets=${guard.resetCount} refreezes=${guard.refreezeCount} until=$until"
                )
                finishPackageSuccessorGuardLocked(
                    packageName,
                    generation,
                    "oem_refreeze_circuit_breaker"
                )
                return
            }
            val shouldReset = RecoveryCampaignPolicy.shouldResetPackageSuccessor(
                nowElapsed = now,
                lastResetElapsed = guard.lastResetElapsed,
                resetCount = guard.resetCount,
                verifiedFrozen = true
            )
            if (
                guard.resetCount >= RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_FAST_RESETS &&
                !guard.longGuardReported
            ) {
                guard.longGuardReported = true
                eventLocked(
                    "package_successor_long_guard_entered",
                    "$packageName generation=$generation resets=${guard.resetCount} " +
                        "nextBackoffMs=${RecoveryCampaignPolicy.packageSuccessorResetIntervalMs(guard.resetCount)}"
                )
            }
            if (shouldReset && !packageRebuildInProgress.contains(packageName)) {
                val nextResetCount = guard.resetCount + 1
                val strategy = RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                    vendorFamily = vendorFamily,
                    nextResetCount = nextResetCount,
                    refreezeCount = guard.refreezeCount
                )
                packageRebuildInProgress += packageName
                guard.resetCount = nextResetCount
                guard.lastResetElapsed = now
                eventLocked(
                    "package_successor_reset_started",
                    "$packageName generation=$generation reset=${guard.resetCount} strategy=$strategy " +
                        "frozen=${frozenAfter.joinToString { "${it.name}:${it.pid}" }}"
                )
                try {
                    val removal = performPackageProcessRemovalLocked(
                        packageName = packageName,
                        trigger = "successor_refreeze_generation_$generation",
                        requestedStrategy = strategy
                    )
                    guard.lastObservedPids = emptySet()
                    guard.frozenPids.clear()
                    eventLocked(
                        "package_successor_reset_completed",
                        "$packageName generation=$generation reset=${guard.resetCount} " +
                            "strategy=$strategy result=${removal.result}"
                    )
                } finally {
                    packageRebuildInProgress.remove(packageName)
                }
            } else if (
                guard.resetCount >= RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_MAX_RESETS &&
                !guard.resetBudgetReported
            ) {
                guard.resetBudgetReported = true
                eventLocked(
                    "package_successor_reset_backoff_exhausted",
                    "$packageName generation=$generation count=${guard.resetCount} " +
                        "guardContinuesUntil=${guard.deadlineElapsed}"
                )
            }
        }

        if (now >= guard.deadlineElapsed) {
            val result = when {
                verifiedFrozen -> "expired_refrozen"
                guard.stableSinceElapsed > 0L -> "expired_not_yet_stable"
                else -> "expired_unstable"
            }
            finishPackageSuccessorGuardLocked(packageName, generation, result)
            return
        }
        persistStatusLocked()
    }

    private fun finishPackageSuccessorGuardLocked(
        packageName: String,
        generation: Long,
        result: String
    ) {
        val guard = packageSuccessorGuardByPackage[packageName]
        if (guard == null || guard.generation != generation) return
        packageSuccessorGuardFutureByPackage.remove(packageName)?.cancel(false)
        packageSuccessorGuardByPackage.remove(packageName)
        eventLocked(
            "package_successor_guard_finished",
            "$packageName generation=$generation result=$result resets=${guard.resetCount} " +
                "refreezes=${guard.refreezeCount} durationMs=" +
                (SystemClock.elapsedRealtime() - guard.startedElapsed)
        )
        persistStatusLocked(force = true)
    }

    private fun cancelPackageSuccessorGuardsLocked(reason: String) {
        packageSuccessorGuardFutureByPackage.values.forEach { it.cancel(false) }
        packageSuccessorGuardFutureByPackage.clear()
        if (packageSuccessorGuardByPackage.isNotEmpty()) {
            eventLocked(
                "package_successor_guards_cancelled",
                "reason=$reason packages=${packageSuccessorGuardByPackage.keys.joinToString()}"
            )
        }
        packageSuccessorGuardByPackage.clear()
    }

    private fun pruneDeliveryFailureStateLocked(packageName: String, now: Long) {
        val episodeCutoff =
            (now - DeliveryFailureEscalationPolicy.EVIDENCE_WINDOW_MS).coerceAtLeast(0L)
        deliveryFailureEpisodesByPackage[packageName]?.let { episodes ->
            while (episodes.firstOrNull()?.let { it < episodeCutoff || it > now } == true) {
                episodes.removeFirst()
            }
        }
        val historyCutoff =
            (now - DeliveryFailureEscalationPolicy.HISTORY_WINDOW_MS).coerceAtLeast(0L)
        packageRebuildHistoryByPackage[packageName]?.let { history ->
            while (history.firstOrNull()?.let { it < historyCutoff || it > now } == true) {
                history.removeFirst()
            }
        }
    }

    private fun startVendorRecoveryBurstLocked(signal: VendorFreezeSignal) {
        val packageName = signal.packageName
        val now = SystemClock.elapsedRealtime()
        val exhaustedUntil = vendorRecoveryExhaustedUntilByPackage[packageName] ?: 0L
        if (now < exhaustedUntil) {
            eventLocked(
                "vendor_recovery_cooldown",
                "$packageName until=$exhaustedUntil kind=${signal.kind} critical=${signal.deliveryCritical}"
            )
            return
        }

        val existing = vendorRecoveryFuturesByPackage[packageName]
        val existingCritical = vendorRecoveryCriticalByPackage[packageName] == true
        if (existing != null && existing.any { !it.isDone && !it.isCancelled }) {
            if (!signal.deliveryCritical || existingCritical) {
                eventLocked(
                    "vendor_recovery_signal_coalesced",
                    "$packageName kind=${signal.kind} critical=${signal.deliveryCritical}"
                )
                return
            }
            existing.forEach { it.cancel(false) }
            vendorRecoveryFuturesByPackage.remove(packageName)
            eventLocked("vendor_recovery_escalated", "$packageName ordinary_to_critical")
        }

        val generation = (vendorRecoveryGenerationByPackage[packageName] ?: 0L) + 1L
        vendorRecoveryGenerationByPackage[packageName] = generation
        vendorRecoveryCriticalByPackage[packageName] = signal.deliveryCritical
        vendorRecoverySignalKindByPackage[packageName] = signal.kind
        val futures = mutableListOf<ScheduledFuture<*>>()
        vendorRecoveryFuturesByPackage[packageName] = futures
        eventLocked(
            "vendor_recovery_burst_started",
            "$packageName kind=${signal.kind} generation=$generation critical=${signal.deliveryCritical}"
        )

        VendorFreezeRecoveryPolicy.REASSERT_DELAYS_MS.forEachIndexed { pass, delayMs ->
            val task = Runnable {
                synchronized(lock) {
                    if (!running) return@synchronized
                    if (vendorRecoveryGenerationByPackage[packageName] != generation) {
                        return@synchronized
                    }
                    applyVendorRecoveryPassLocked(
                        packageName = packageName,
                        signalKind = signal.kind,
                        deliveryCritical = signal.deliveryCritical,
                        generation = generation,
                        pass = pass
                    )
                    if (pass == VendorFreezeRecoveryPolicy.REASSERT_DELAYS_MS.lastIndex) {
                        vendorRecoveryFuturesByPackage.remove(packageName)
                        vendorRecoveryCriticalByPackage.remove(packageName)
                        vendorRecoverySignalKindByPackage.remove(packageName)
                    }
                }
            }
            futures += executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun applyVendorRecoveryPassLocked(
        packageName: String,
        signalKind: VendorFreezeSignalKind,
        deliveryCritical: Boolean,
        generation: Long,
        pass: Int
    ) {
        val now = SystemClock.elapsedRealtime()
        val recoveryPackages = linkedSetOf(packageName).apply {
            if (deliveryCritical && packageName != GMS_PACKAGE) add(GMS_PACKAGE)
        }
        if (deliveryCritical && packageName != GMS_PACKAGE) {
            vendorHoldUntilByPackage[GMS_PACKAGE] = maxOf(
                vendorHoldUntilByPackage[GMS_PACKAGE] ?: 0L,
                now + VendorFreezeRecoveryPolicy.AOSP_HOLD_MS
            )
        }
        val targetNames = recoveryPackages.flatMap(::processTargetsForPackage).distinct()
        val processes = GuardianProcessParser.matching(listProcessesLocked(), targetNames)
        var succeeded = 0
        var failed = 0
        var stillFrozen = 0
        var invisible = 0

        processes.forEach { process ->
            val before = readFreezeState(process.pid)
            val ownerPackage = packageForProcess(process.name)
            val activityManagerApplicable =
                GuardianTargetResolver.canUseActivityManagerUnfreeze(
                    process.name,
                    ownerPackage
                )
            var amAttempted = false
            var amAccepted = false
            if (activityManagerApplicable) {
                val result = unfreezePackageLocked(ownerPackage!!)
                amAttempted = true
                amAccepted = result.success
                actionCount += 1
                if (!amAccepted) {
                    failed += 1
                    commandFailureCount += 1
                    errorCount += 1
                } else {
                    succeeded += 1
                }
            }

            // Verify ActivityManager first. Direct cgroup thaw is a fallback,
            // not a second unconditional write that would hide which path
            // actually worked or manufacture a failure after AM already thawed.
            val afterActivityManager = if (amAttempted) readFreezeState(process.pid) else before
            var directAttempted = false
            var directAccepted = false
            val shouldTryDirect = FreezeRecoveryClassifier.shouldTryDirectCgroup(
                enabled = config.rootCgroupThaw,
                hasControlFile = before.controlFile != null,
                beforeFrozen = before.frozen,
                afterActivityManagerFrozen = afterActivityManager.frozen,
                activityManagerAttempted = amAttempted,
                activityManagerAccepted = amAccepted
            )
            if (shouldTryDirect) {
                directAttempted = true
                directCgroupAttemptCount += 1
                actionCount += 1
                directAccepted = directCgroupThaw(before) == true
                if (directAccepted) {
                    directCgroupSuccessCount += 1
                    succeeded += 1
                } else {
                    failed += 1
                    commandFailureCount += 1
                }
            }
            val after = if (directAttempted) readFreezeState(process.pid) else afterActivityManager
            val verdict = FreezeRecoveryClassifier.classify(
                beforeFrozen = before.frozen,
                afterFrozen = after.frozen,
                commandAttempted = amAttempted || directAttempted,
                commandAccepted = amAccepted || directAccepted
            )
            when (verdict) {
                FreezeRecoveryVerdict.VERIFIED_THAWED -> {
                    effectiveThawCount += 1
                    lastActionByName[process.name] = now
                }
                FreezeRecoveryVerdict.STILL_FROZEN -> {
                    stillFrozen += 1
                    verificationFailureCount += 1
                }
                FreezeRecoveryVerdict.COMMAND_FAILED -> {
                    if (after.frozen == true) stillFrozen += 1
                }
                FreezeRecoveryVerdict.STATE_UNOBSERVABLE -> invisible += 1
                FreezeRecoveryVerdict.NO_SAFE_COMMAND_PATH -> {
                    if (after.frozen == true) {
                        stillFrozen += 1
                        noSafeCommandPathCount += 1
                    }
                }
                FreezeRecoveryVerdict.NOT_NEEDED -> Unit
            }
            // Rate-limit the normal cycle even when a vendor keeps the process
            // frozen. The scheduled burst remains the only rapid retry path.
            lastActionByName[process.name] = now
        }

        if (pass == 0) {
            recoveryPackages.forEach(::tunePackageLocked)
        }
        lastVendorActionByPackage[packageName] = now
        vendorRecoveryPassCount += 1
        eventLocked(
            "vendor_recovery_pass",
            "$packageName kind=$signalKind generation=$generation pass=$pass " +
                "processes=${processes.size} success=$succeeded failed=$failed " +
                "stillFrozen=$stillFrozen invisible=$invisible critical=$deliveryCritical"
        )

        if (processes.isEmpty()) {
            // `am unfreeze <package>` resolves an existing process. When the
            // process has already vanished, issuing it only creates a false
            // "could not find process" error. The next real delivery may
            // recreate the app, so retain policy tuning but skip fake recovery.
            eventLocked(
                "vendor_recovery_no_process",
                "$packageName kind=$signalKind generation=$generation pass=$pass " +
                    "package_process_absent am_unfreeze_skipped"
            )
        } else if (stillFrozen > 0) {
            eventLocked(
                "vendor_recovery_verification_failed",
                "$packageName generation=$generation pass=$pass stillFrozen=$stillFrozen"
            )
        } else if (invisible == 0) {
            eventLocked(
                "vendor_recovery_verified_thawed",
                "$packageName generation=$generation pass=$pass"
            )
        } else {
            eventLocked(
                "vendor_recovery_state_unobservable",
                "$packageName generation=$generation pass=$pass invisible=$invisible"
            )
        }

        if (pass == VendorFreezeRecoveryPolicy.REASSERT_DELAYS_MS.lastIndex && stillFrozen > 0) {
            vendorRecoveryExhaustedUntilByPackage[packageName] =
                now + VendorFreezeRecoveryPolicy.EXHAUSTED_COOLDOWN_MS
            recoveryEscalationCount += 1
            eventLocked(
                "vendor_recovery_exhausted",
                "$packageName generation=$generation stillFrozen=$stillFrozen critical=$deliveryCritical"
            )
            if (deliveryCritical && DeliveryFailureEscalationPolicy.isRebuildTarget(packageName)) {
                maybeSchedulePackageRebuildLocked(
                    packageName = packageName,
                    now = now,
                    verifiedFrozenAfterBurst = true
                )
            }
            if (
                packageName == GMS_PACKAGE &&
                config.vendorEmergencyRecoveryEnabled &&
                lastGmsTransportProbe.observable &&
                !lastGmsTransportProbe.healthy &&
                gmsTransportConsecutiveMissing >= 3
            ) {
                recoverGmsLocked(
                    trigger = "vendor_freeze_exhausted_mcs_missing",
                    manual = false,
                    automaticEvidenceReason = "vendor_freeze_exhausted_mcs_missing",
                    emergency = true
                )
            }
        }
    }

    private fun maybeWakeManagedPackagesLocked(
        now: Long,
        matchedProcesses: List<GuardianProcess>
    ) {
        MANAGED_LIVENESS_PACKAGES.forEach { packageName ->
            if (packageName !in config.packageTargets || !packageInstalled(packageName)) {
                managedPackageFrozenSinceByPackage.remove(packageName)
                return@forEach
            }
            val packageProcesses = matchedProcesses.filter { process ->
                packageForProcess(process.name) == packageName
            }
            val processPresent = packageProcesses.isNotEmpty()
            if (!processPresent) {
                managedPackageFrozenSinceByPackage.remove(packageName)
                if (
                    !RecoveryCampaignPolicy.shouldWakeManagedPackage(
                        nowElapsed = now,
                        lastWakeElapsed = lastManagedPackageWakeByPackage[packageName] ?: 0L,
                        processPresent = false
                    )
                ) {
                    return@forEach
                }
                lastManagedPackageWakeByPackage[packageName] = now
                pulseAbsentPackageLocked(packageName, "managed_liveness_absent")
                val launch = attemptBackgroundLauncherWakeLocked(
                    packageName = packageName,
                    reason = "managed_liveness_absent"
                )
                eventLocked(
                    "managed_package_wake_attempted",
                    "$packageName processPresent=false launcher=${launch.name.lowercase()}"
                )
                return@forEach
            }

            val frozen = packageProcesses.any { process ->
                readFreezeState(process.pid).frozen == true
            }
            if (!frozen) {
                if (managedPackageFrozenSinceByPackage.remove(packageName) != null) {
                    eventLocked(
                        "managed_package_verified_thawed",
                        "$packageName pids=${packageProcesses.map { it.pid }.sorted()}"
                    )
                }
                return@forEach
            }

            val frozenSince = managedPackageFrozenSinceByPackage[packageName]
                ?.takeIf { it > 0L && it <= now }
                ?: now.also {
                    managedPackageFrozenSinceByPackage[packageName] = it
                    eventLocked(
                        "managed_package_verified_frozen",
                        "$packageName pids=${packageProcesses.map { process -> process.pid }.sorted()}"
                    )
                }
            if (
                !RecoveryCampaignPolicy.shouldAttemptFrozenManagedPackageWake(
                    nowElapsed = now,
                    frozenSinceElapsed = frozenSince,
                    lastWakeElapsed =
                        lastManagedPackageFrozenWakeByPackage[packageName] ?: 0L
                )
            ) {
                return@forEach
            }

            lastManagedPackageFrozenWakeByPackage[packageName] = now
            tunePackageLocked(packageName)
            val launch = attemptBackgroundLauncherWakeLocked(
                packageName = packageName,
                reason = "managed_liveness_frozen",
                foregroundHoldMs =
                    RecoveryCampaignPolicy.MANAGED_PACKAGE_FROZEN_FOREGROUND_HOLD_MS
            )
            val remainingFrozen = GuardianProcessParser.matching(
                listProcessesLocked(),
                processTargetsForPackage(packageName)
            ).filter { process -> readFreezeState(process.pid).frozen == true }
            eventLocked(
                if (remainingFrozen.isEmpty()) {
                    "managed_package_frozen_wake_verified"
                } else {
                    "managed_package_frozen_wake_failed"
                },
                "$packageName launcher=${launch.name.lowercase()} " +
                    "remaining=${remainingFrozen.map { it.pid }.sorted()}"
            )
            if (remainingFrozen.isEmpty()) {
                managedPackageFrozenSinceByPackage.remove(packageName)
            }
        }
    }

    private fun maybeProbeAdbTcp5555Locked(now: Long) {
        if (
            !AdbTcpPortHealthPolicy.shouldProbe(
                nowElapsed = now,
                lastProbeElapsed = adbTcp5555LastProbeElapsed
            )
        ) {
            return
        }
        adbTcp5555LastProbeElapsed = now
        adbTcp5555ProbeCount += 1

        val socketResult = runner.run(
            "ss", "-H", "-ltn",
            timeoutMs = SOCKET_PROBE_TIMEOUT_MS
        )
        if (!socketResult.success) {
            eventLocked(
                "adb_tcp_5555_probe_unobservable",
                socketResult.summary()
            )
            return
        }

        val listenerPresent =
            AdbTcpPortHealthPolicy.listeningOnPort(socketResult.stdout)
        val configured = adbTcp5555ConfiguredLocked()
        adbTcp5555Configured = configured
        val armed = adbTcp5555ObservedHealthy || configured
        val changed = listenerPresent != adbTcp5555ListenerPresent
        adbTcp5555ListenerPresent = listenerPresent

        if (listenerPresent) {
            adbTcp5555ObservedHealthy = true
            adbTcp5555LastHealthyElapsed = now
            adbTcp5555MissingSinceElapsed = 0L
            if (changed) {
                eventLocked(
                    "adb_tcp_5555_listener_healthy",
                    "configured=$configured source=ss"
                )
            }
            return
        }

        if (!armed) {
            if (changed || adbTcp5555ProbeCount == 1L) {
                eventLocked(
                    "adb_tcp_5555_monitor_unarmed",
                    "listenerNeverObserved=true configured=$configured"
                )
            }
            return
        }

        if (
            adbTcp5555MissingSinceElapsed <= 0L ||
            adbTcp5555MissingSinceElapsed > now
        ) {
            adbTcp5555MissingSinceElapsed = now
            eventLocked(
                "adb_tcp_5555_listener_missing",
                "configured=$configured lastHealthy=$adbTcp5555LastHealthyElapsed"
            )
        }

        if (
            !AdbTcpPortHealthPolicy.shouldRecover(
                nowElapsed = now,
                armed = true,
                missingSinceElapsed = adbTcp5555MissingSinceElapsed,
                lastRecoveryElapsed = adbTcp5555LastRecoveryElapsed
            )
        ) {
            return
        }
        if (
            "com.termux" !in config.packageTargets ||
            !packageInstalled("com.termux")
        ) {
            eventLocked(
                "adb_tcp_5555_recovery_skipped",
                "termux_not_targeted_or_not_installed"
            )
            return
        }

        adbTcp5555LastRecoveryElapsed = now
        adbTcp5555RecoveryCount += 1
        tunePackageLocked("com.termux")
        GuardianProcessParser.matching(
            listProcessesLocked(),
            processTargetsForPackage("com.termux")
        ).forEach { process ->
            if (readFreezeState(process.pid).frozen == true) {
                val unfreeze = unfreezeLocked(process)
                if (!unfreeze.stdout.contains("not_applicable_secondary_process")) {
                    actionCount += 1
                    if (!isUnfreezeAccepted(unfreeze)) commandFailureCount += 1
                }
            }
        }
        val launcher = attemptBackgroundLauncherWakeLocked(
            packageName = "com.termux",
            reason = "adb_tcp_5555_listener_missing",
            foregroundHoldMs = AdbTcpPortHealthPolicy.FOREGROUND_HOLD_MS
        )
        SystemClock.sleep(1_000L)
        val verify = runner.run(
            "ss", "-H", "-ltn",
            timeoutMs = SOCKET_PROBE_TIMEOUT_MS
        )
        val recovered =
            verify.success &&
                AdbTcpPortHealthPolicy.listeningOnPort(verify.stdout)
        if (recovered) {
            adbTcp5555ListenerPresent = true
            adbTcp5555ObservedHealthy = true
            adbTcp5555LastHealthyElapsed = SystemClock.elapsedRealtime()
            adbTcp5555MissingSinceElapsed = 0L
        }
        eventLocked(
            if (recovered) {
                "adb_tcp_5555_recovery_verified"
            } else {
                "adb_tcp_5555_recovery_unresolved"
            },
            "launcher=${launcher.name.lowercase()} configured=$configured " +
                "verify=${verify.summary()}"
        )
    }

    private fun adbTcp5555ConfiguredLocked(): Boolean =
        listOf("service.adb.tcp.port", "persist.adb.tcp.port").any { property ->
            val value = runner.run(
                "getprop", property,
                timeoutMs = PACKAGE_QUERY_TIMEOUT_MS
            ).stdout.trim()
            value.split(',', ' ').any { token ->
                token.trim().toIntOrNull() == AdbTcpPortHealthPolicy.PORT
            }
        }

    private fun pulseAbsentPackageLocked(packageName: String, reason: String) {
        val details = mutableListOf<String>()
        val canUnstop = verifyPackageUnstopBeforeForceStopLocked(packageName, details)
        if (canUnstop) {
            val unstop = runPackageUnstopWithRetryLocked(packageName)
            details += "package_unstop:${unstop.summary()}"
            if (!unstop.success) {
                commandFailureCount += 1
                errorCount += 1
            }
        } else {
            details += "package_unstop:unsupported"
        }

        tunePackageLocked(packageName)
        if (packageName in DELIVERY_PACKAGE_TARGETS) {
            val gmsUnfreeze = unfreezePackageLocked(GMS_PACKAGE)
            actionCount += 1
            details += "gms_unfreeze:${gmsUnfreeze.summary()}"
            if (!isUnfreezeAccepted(gmsUnfreeze)) commandFailureCount += 1
            val pulse = sendGmsBinderPulseLocked()
            details += "gms_binder_pulse:${pulse.summary()}"
        }
        eventLocked(
            "package_successor_absence_pulse",
            "$packageName reason=$reason ${details.joinToString(" | ").take(1_000)}"
        )
    }

    private fun attemptBackgroundLauncherWakeLocked(
        packageName: String,
        reason: String,
        foregroundHoldMs: Long = PACKAGE_LAUNCH_HOME_DELAY_MS
    ): BackgroundLauncherWakeResult {
        val started = startPackageForegroundLocked(packageName, reason)
        if (started != BackgroundLauncherWakeResult.STARTED) return started
        SystemClock.sleep(foregroundHoldMs.coerceAtLeast(0L))
        val home = returnHomeLocked("package_background_launch_$reason")
        eventLocked(
            "package_background_launch_succeeded",
            "$packageName reason=$reason home=$home " +
                "foregroundHoldMs=${foregroundHoldMs.coerceAtLeast(0L)}"
        )
        return BackgroundLauncherWakeResult.STARTED
    }

    private fun startPackageForegroundLocked(
        packageName: String,
        reason: String
    ): BackgroundLauncherWakeResult {
        if (packageName !in BACKGROUND_LAUNCH_WAKE_TARGETS) {
            eventLocked(
                "package_background_launch_skipped",
                "$packageName reason=$reason target_not_allowed"
            )
            return BackgroundLauncherWakeResult.NOT_ALLOWED
        }

        val interactive = readScreenInteractiveLocked()
        if (interactive != false) {
            eventLocked(
                "package_background_launch_deferred",
                "$packageName reason=$reason screenInteractive=${interactive ?: "unknown"}"
            )
            return BackgroundLauncherWakeResult.DEFERRED_SCREEN_INTERACTIVE
        }

        val resolve = runner.run(
            "cmd", "package", "resolve-activity",
            "--brief", "--user", "0",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER",
            "-p", packageName,
            timeoutMs = PACKAGE_LAUNCH_RESOLVE_TIMEOUT_MS
        )
        actionCount += 1
        val component = resolve.stdout.lineSequence()
            .map(String::trim)
            .firstOrNull { line ->
                line.contains('/') &&
                    !line.contains("No activity", ignoreCase = true) &&
                    !line.contains("priority=", ignoreCase = true)
            }
        if (!resolve.success || component.isNullOrBlank()) {
            commandFailureCount += 1
            eventLocked(
                "package_background_launch_failed",
                "$packageName reason=$reason resolve=${resolve.summary()}"
            )
            return BackgroundLauncherWakeResult.RESOLVE_FAILED
        }

        val start = runner.run(
            "am", "start", "--user", "0",
            "--activity-no-animation",
            "--activity-exclude-from-recents",
            "-n", component,
            timeoutMs = PACKAGE_LAUNCH_TIMEOUT_MS
        )
        actionCount += 1
        if (!start.success) {
            commandFailureCount += 1
            eventLocked(
                "package_background_launch_failed",
                "$packageName reason=$reason component=$component start=${start.summary()}"
            )
            return BackgroundLauncherWakeResult.START_FAILED
        }
        eventLocked(
            "package_background_foreground_started",
            "$packageName reason=$reason component=$component"
        )
        return BackgroundLauncherWakeResult.STARTED
    }

    private fun returnHomeLocked(reason: String): Boolean {
        val home = runner.run(
            "am", "start", "--user", "0",
            "--activity-no-animation",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.HOME",
            timeoutMs = PACKAGE_LAUNCH_TIMEOUT_MS
        )
        actionCount += 1
        if (!home.success) commandFailureCount += 1
        eventLocked("package_background_home_requested", "reason=$reason result=${home.summary()}")
        return home.success
    }

    private fun readScreenInteractiveLocked(): Boolean? {
        val result = runner.run(
            "dumpsys", "power",
            timeoutMs = PACKAGE_QUERY_TIMEOUT_MS
        )
        if (!result.success) return null
        val wakefulness = Regex("""\bmWakefulness=(Awake|Dreaming|Dozing|Asleep)\b""")
            .find(result.stdout)
            ?.groupValues
            ?.getOrNull(1)
        return when (wakefulness) {
            "Awake", "Dreaming" -> true
            "Dozing", "Asleep" -> false
            else -> Regex("""\bmInteractive=(true|false)\b""")
                .find(result.stdout)
                ?.groupValues
                ?.getOrNull(1)
                ?.toBooleanStrictOrNull()
        }
    }

    private fun processTargetsForPackage(packageName: String): List<String> {
        val configured = config.processTargets.filter { processName ->
            packageForProcess(processName) == packageName
        }
        return if (configured.isNotEmpty()) configured else listOf(packageName)
    }

    private fun packageForProcess(processName: String): String? =
        GuardianTargetResolver.ownerPackage(processName, config.packageTargets)

    private fun cancelVendorRecoveryBurstsLocked() {
        vendorRecoveryFuturesByPackage.values
            .flatten()
            .forEach { future -> future.cancel(false) }
        vendorRecoveryFuturesByPackage.clear()
        vendorRecoveryCriticalByPackage.clear()
        vendorRecoverySignalKindByPackage.clear()
    }

    private fun stopEventWatcherLocked() {
        eventWatcherAlive = false
        eventWatcherProcess?.destroy()
        eventWatcherProcess = null
        eventWatcherThread?.interrupt()
        eventWatcherThread = null
    }

    private fun scheduleInitialCycleLocked() {
        initialCycleFuture?.cancel(false)
        initialCycleFuture = try {
            executor.schedule(
                {
                    synchronized(lock) {
                        initialCycleFuture = null
                        if (!running) return@synchronized
                        runCatching { runCycleLocked(force = true) }
                            .onFailure { error ->
                                errorCount += 1
                                eventLocked(
                                    "initial_cycle_failed",
                                    "${error.javaClass.simpleName}:${error.message.orEmpty()}"
                                )
                                persistStatusLocked(force = true)
                            }
                    }
                },
                INITIAL_CYCLE_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
        } catch (error: Throwable) {
            errorCount += 1
            eventLocked(
                "initial_cycle_schedule_failed",
                "${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            null
        }
    }

    private fun restartScheduleLocked() {
        scheduled?.cancel(false)
        scheduled = executor.scheduleWithFixedDelay(
            {
                synchronized(lock) {
                    if (running) runCycleLocked(force = false)
                }
            },
            config.pollIntervalMs,
            config.pollIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun runCycleLocked(force: Boolean): String {
        val now = SystemClock.elapsedRealtime()
        vendorHoldUntilByPackage.entries.removeAll { (_, until) -> until <= now }
        vendorRecoveryExhaustedUntilByPackage.entries.removeAll { (_, until) -> until <= now }
        ensureCapabilitiesLocked()
        if (!eventWatcherAlive) startEventWatcherLocked()
        val processResult = listProcessesLocked()
        val matched = GuardianProcessParser.matching(processResult, config.processTargets)
        val currentNames = matched.mapTo(linkedSetOf()) { it.name }
        val states = ArrayList<GuardianProcessState>(matched.size)

        matched.forEach { process ->
            val before = readFreezeState(process.pid)
            val previousPid = previousPidByName[process.name]
            val lastAction = lastActionByName[process.name]
            val policyDue = GuardianActionPolicy.shouldReassert(
                previousPid = previousPid,
                currentPid = process.pid,
                lastActionElapsed = lastAction,
                nowElapsed = now,
                reassertIntervalMs = config.reassertIntervalMs
            )
            val guardedPackage = packageForProcess(process.name)
            // Vendor signals own the rapid five-pass retry burst. The normal
            // poller reasserts at the configured interval instead of issuing a
            // command every 15 seconds while a ROM keeps a process frozen.
            val shouldAct = force || policyDue
            var action = "observed"
            var commandDetail = ""
            var amAttempted = false
            var amAccepted = false
            var directAttempted = false
            var directAccepted = false

            if (shouldAct) {
                val ownerPackage = packageForProcess(process.name)
                if (GuardianTargetResolver.canUseActivityManagerUnfreeze(process.name, ownerPackage)) {
                    val result = unfreezePackageLocked(ownerPackage!!)
                    amAttempted = true
                    amAccepted = result.success
                    actionCount += 1
                    commandDetail = result.summary()
                    action = if (config.stickyUnfreeze && supportsStickyUnfreeze) {
                        "am_unfreeze_sticky"
                    } else {
                        "am_unfreeze"
                    }
                    if (!result.success) {
                        commandFailureCount += 1
                        errorCount += 1
                        eventLocked(
                            "unfreeze_command_failed",
                            "${process.name} pid=${process.pid} ${result.summary()}"
                        )
                    }
                } else {
                    action = "secondary_process_observed"
                    commandDetail = "am_unfreeze_not_applicable owner=${ownerPackage.orEmpty()}"
                }

                val afterActivityManager = if (amAttempted) readFreezeState(process.pid) else before
                val shouldTryDirect = FreezeRecoveryClassifier.shouldTryDirectCgroup(
                    enabled = config.rootCgroupThaw,
                    hasControlFile = before.controlFile != null,
                    beforeFrozen = before.frozen,
                    afterActivityManagerFrozen = afterActivityManager.frozen,
                    activityManagerAttempted = amAttempted,
                    activityManagerAccepted = amAccepted
                )
                if (shouldTryDirect) {
                    directAttempted = true
                    directCgroupAttemptCount += 1
                    actionCount += 1
                    val direct = directCgroupThaw(before)
                    directAccepted = direct == true
                    if (directAccepted) {
                        directCgroupSuccessCount += 1
                    } else {
                        commandFailureCount += 1
                    }
                    action += "+cgroup_thaw"
                    commandDetail += if (directAccepted) {
                        " cgroup_thaw=accepted"
                    } else {
                        " cgroup_thaw=rejected"
                    }
                }
            }

            val after = when {
                !shouldAct -> before
                directAttempted -> readFreezeState(process.pid)
                amAttempted -> readFreezeState(process.pid)
                else -> before
            }
            val verdict = FreezeRecoveryClassifier.classify(
                beforeFrozen = before.frozen,
                afterFrozen = after.frozen,
                commandAttempted = amAttempted || directAttempted,
                commandAccepted = amAccepted || directAccepted
            )
            when (verdict) {
                FreezeRecoveryVerdict.VERIFIED_THAWED -> {
                    effectiveThawCount += 1
                    lastActionByName[process.name] = now
                    eventLocked(
                        "unfreeze_verified",
                        "${process.name} pid=${process.pid} before=${before.frozen} after=${after.frozen}"
                    )
                }
                FreezeRecoveryVerdict.STILL_FROZEN -> {
                    verificationFailureCount += 1
                    eventLocked(
                        "unfreeze_verification_failed",
                        "${process.name} pid=${process.pid} evidence=${after.detail}"
                    )
                }
                FreezeRecoveryVerdict.NO_SAFE_COMMAND_PATH -> {
                    if (before.frozen == true) noSafeCommandPathCount += 1
                }
                FreezeRecoveryVerdict.COMMAND_FAILED -> Unit
                FreezeRecoveryVerdict.STATE_UNOBSERVABLE,
                FreezeRecoveryVerdict.NOT_NEEDED -> Unit
            }
            if (shouldAct) {
                // An attempted reassertion, successful or not, starts the
                // normal-cycle cooldown. Fast retries are handled only by the
                // coalesced vendor recovery burst.
                lastActionByName[process.name] = now
            }

            previousPidByName[process.name] = process.pid
            states += GuardianProcessState(
                pid = process.pid,
                name = process.name,
                ownerPackage = guardedPackage.orEmpty(),
                beforeFrozen = before.frozen,
                afterFrozen = after.frozen,
                freezeEvidence = after.detail,
                action = action,
                commandAccepted = if (amAttempted || directAttempted) amAccepted || directAccepted else null,
                thawVerified = when (after.frozen) {
                    false -> true
                    true -> false
                    null -> null
                },
                recoveryVerdict = verdict.name,
                actionDetail = commandDetail
            )
        }

        previousPidByName.keys.retainAll(currentNames)
        lastActionByName.keys.retainAll(currentNames)
        latestProcesses = states
        maybeWakeManagedPackagesLocked(now, matched)
        maybeProbeAdbTcp5555Locked(now)

        if (force || lastTuneElapsed <= 0L || now - lastTuneElapsed >= config.tuningIntervalMs) {
            tunePackagesLocked()
            lastTuneElapsed = now
        }

        maybeProbeGmsTransportLocked(now)
        maybeStartVerifiedGmsCampaignLocked()

        cycleCount += 1
        lastCycleElapsed = SystemClock.elapsedRealtime()
        persistStatusLocked()
        return statusJsonLocked()
    }

    private fun recordGmsFreezeEventLocked(now: Long, rawLine: String) {
        pruneGmsStateLocked(now)
        val previous = gmsFreezeEvents.lastOrNull()
        if (previous != null && now >= previous && now - previous < GMS_FREEZE_EVENT_DEBOUNCE_MS) {
            return
        }
        gmsFreezeEvents.addLast(now)
        eventLocked(
            "gms_freeze_evidence",
            "count=${gmsFreezeEvents.size} ${rawLine.take(220)}"
        )
    }

    private fun maybeProbeGmsTransportLocked(now: Long) {
        if (gmsRecoveryInProgress) return
        val verifiedGmsFrozen = latestProcesses.any { state ->
            state.ownerPackage == GMS_PACKAGE && state.afterFrozen == true
        }
        val probeInterval = if (verifiedGmsFrozen) {
            minOf(config.gmsTransportProbeIntervalMs, GMS_FROZEN_TRANSPORT_PROBE_INTERVAL_MS)
        } else {
            config.gmsTransportProbeIntervalMs
        }
        if (
            lastGmsTransportProbeElapsed > 0L &&
            now >= lastGmsTransportProbeElapsed &&
            now - lastGmsTransportProbeElapsed < probeInterval
        ) {
            return
        }

        val probe = probeGmsTransportLocked()
        val persistentRunning = listGmsProcessesLocked().any { process ->
            process.name == "$GMS_PACKAGE.persistent"
        }
        applyGmsTransportProbeLocked(
            probe = probe,
            now = now,
            persistentRunning = persistentRunning,
            source = "scheduled"
        )

        val decision = GmsTransportHealthPolicy.decide(
            automaticEnabled = config.gmsRecoveryEnabled,
            nowElapsed = now,
            probe = probe,
            gmsPersistentRunning = persistentRunning,
            consecutiveMissing = gmsTransportConsecutiveMissing,
            missingSinceElapsed = gmsTransportMissingSinceElapsed,
            lastHealthyElapsed = lastGmsTransportHealthyElapsed,
            lastBadAuthenticationElapsed = lastGmsBadAuthenticationElapsed,
            lastConnectAttemptElapsed = lastGmsMcsConnectAttemptElapsed,
            evidenceWindowMs = config.gmsTransportBadAuthWindowMs,
            missingAfterBadAuthMs = config.gmsTransportMissingAfterBadAuthMs,
            transportLostMs = config.gmsTransportLostMs
        )
        if (decision.recover) {
            eventLocked(
                "gms_transport_recovery_requested",
                "reason=${decision.reason} missing=$gmsTransportConsecutiveMissing " +
                    "missingFor=${now - gmsTransportMissingSinceElapsed}"
            )
            recoverGmsLocked(
                trigger = decision.reason,
                manual = false,
                automaticEvidenceReason = decision.reason
            )
        }
    }

    private fun applyGmsTransportProbeLocked(
        probe: GmsTransportProbe,
        now: Long,
        persistentRunning: Boolean,
        source: String
    ) {
        val previous = lastGmsTransportProbe
        lastGmsTransportProbe = probe
        lastGmsTransportProbeElapsed = now
        gmsTransportProbeCount += 1
        if (!probe.observable) {
            gmsTransportUnobservableCount += 1
            gmsTransportConsecutiveMissing = 0
            gmsTransportMissingSinceElapsed = 0L
        } else if (probe.healthy) {
            gmsTransportHealthyCount += 1
            lastGmsTransportHealthyElapsed = now
            gmsTransportConsecutiveMissing = 0
            gmsTransportMissingSinceElapsed = 0L
        } else {
            if (gmsTransportMissingSinceElapsed <= 0L || gmsTransportMissingSinceElapsed > now) {
                gmsTransportMissingSinceElapsed = now
            }
            gmsTransportConsecutiveMissing += 1
        }
        val changed = previous.observable != probe.observable || previous.healthy != probe.healthy
        if (
            changed || source == "campaign" ||
            gmsTransportConsecutiveMissing in setOf(1, 3, 6, 10)
        ) {
            eventLocked(
                "gms_transport_probe",
                "source=$source observable=${probe.observable} healthy=${probe.healthy} " +
                    "ports=${probe.establishedPorts.sorted()} missing=$gmsTransportConsecutiveMissing " +
                    "persistent=$persistentRunning ${probe.detail}"
            )
        }
    }

    private fun probeGmsTransportLocked(): GmsTransportProbe {
        val preferred = runner.run("ss", "-H", "-tnp", timeoutMs = SOCKET_PROBE_TIMEOUT_MS)
        val result = if (preferred.success) {
            preferred
        } else {
            runner.run("ss", "-H", "-tn", timeoutMs = SOCKET_PROBE_TIMEOUT_MS)
        }
        if (!result.success) {
            return GmsTransportProbe(
                observable = false,
                establishedPorts = emptySet(),
                detail = result.summary()
            )
        }
        val ports = GmsTransportSocketParser.establishedMcsPorts(result.stdout)
        return GmsTransportProbe(
            observable = true,
            establishedPorts = ports,
            detail = "lines=${result.stdout.lineSequence().count()}"
        )
    }

    private fun maybeStartVerifiedGmsCampaignLocked() {
        if (gmsRecoveryInProgress || !config.vendorEmergencyRecoveryEnabled) return
        if (!lastGmsTransportProbe.observable || lastGmsTransportProbe.healthy) return
        if (gmsTransportConsecutiveMissing < 3) return
        val gmsProcesses = listGmsProcessesLocked()
        val frozen = gmsProcesses.filter { process -> readFreezeState(process.pid).frozen == true }
        if (frozen.isEmpty()) return
        eventLocked(
            "gms_verified_outage_detected",
            "frozen=${frozen.joinToString { "${it.name}:${it.pid}" }} " +
                "missing=$gmsTransportConsecutiveMissing ports=${lastGmsTransportProbe.establishedPorts.sorted()}"
        )
        recoverGmsLocked(
            trigger = "verified_cgroup_frozen_mcs_missing",
            manual = false,
            automaticEvidenceReason = "verified_cgroup_frozen_mcs_missing",
            emergency = true
        )
    }

    private fun recoverGmsLocked(
        trigger: String,
        manual: Boolean,
        automaticEvidenceReason: String? = null,
        emergency: Boolean = false
    ): String {
        val now = SystemClock.elapsedRealtime()
        pruneGmsStateLocked(now)
        if (gmsRecoveryInProgress || gmsRecoveryCampaign != null) {
            eventLocked("gms_recovery_rejected", "already_in_progress")
            return statusJsonLocked()
        }
        if (!packageInstalled(GMS_PACKAGE)) {
            errorCount += 1
            eventLocked("gms_recovery_failed", "package_not_installed")
            lastGmsRecoveryOutcome = GmsRecoveryOutcome(
                trigger = trigger,
                result = "package_not_installed",
                startedElapsed = now,
                completedElapsed = SystemClock.elapsedRealtime()
            )
            return statusJsonLocked()
        }

        val gmsProcesses = listGmsProcessesLocked()
        val anyGmsFrozen = gmsProcesses.any { process -> readFreezeState(process.pid).frozen == true }
        val strongEvidence =
            anyGmsFrozen &&
                lastGmsTransportProbe.observable &&
                !lastGmsTransportProbe.healthy &&
                gmsTransportConsecutiveMissing >= 3
        val vendorFamily = currentVendorFamilyLocked()
        val campaignDecision = RecoveryCampaignPolicy.decideGmsCampaign(
            nowElapsed = now,
            lastCampaignCompletedElapsed = lastGmsRecoveryCompletedElapsed,
            campaignHistory = gmsRecoveryHistory.toList(),
            manual = manual,
            strongEvidence = strongEvidence,
            consecutiveFailureCount = gmsConsecutiveCampaignFailures
        )
        if (!campaignDecision.allowed) {
            eventLocked(
                "gms_recovery_blocked",
                "manual=$manual reason=${campaignDecision.reason} strongEvidence=$strongEvidence " +
                    "frozen=$anyGmsFrozen transport=${lastGmsTransportProbe.healthy} " +
                    "missing=$gmsTransportConsecutiveMissing"
            )
            lastGmsRecoveryOutcome = GmsRecoveryOutcome(
                trigger = trigger,
                result = "blocked:${campaignDecision.reason}",
                startedElapsed = now,
                completedElapsed = now
            )
            return statusJsonLocked()
        }

        val generation = gmsRecoveryGeneration + 1L
        gmsRecoveryGeneration = generation
        val oldPids = gmsProcesses.mapTo(linkedSetOf()) { it.pid }
        val campaign = GmsRecoveryCampaign(
            trigger = trigger,
            manual = manual,
            generation = generation,
            startedElapsed = now,
            deadlineElapsed = now + RecoveryCampaignPolicy.GMS_CAMPAIGN_DURATION_MS,
            initialPids = oldPids,
            nextResetEligibleElapsed = now +
                RecoveryCampaignPolicy.gmsInitialResetDelayMs(vendorFamily)
        )
        gmsRecoveryCampaign = campaign
        gmsRecoveryInProgress = true
        gmsRecoveryAttemptCount += 1
        lastGmsRecoveryElapsed = now
        gmsRecoveryHistory.addLast(now)
        pruneGmsStateLocked(now)
        eventLocked(
            "gms_recovery_campaign_started",
            "trigger=$trigger manual=$manual generation=$generation oldPids=${oldPids.sorted()} " +
                "strongEvidence=$strongEvidence emergency=$emergency vendor=$vendorFamily " +
                "decision=${campaignDecision.reason} evidence=${automaticEvidenceReason.orEmpty()} " +
                "nextResetEligibleElapsed=${campaign.nextResetEligibleElapsed}"
        )
        ensureGmsPreconnectionLeaseLocked(
            campaign = campaign,
            nowElapsed = now,
            reason = "campaign_started",
            force = true
        )
        persistStatusLocked(force = true)

        gmsRecoveryCampaignFuture?.cancel(false)
        gmsRecoveryCampaignFuture = executor.scheduleWithFixedDelay(
            {
                synchronized(lock) {
                    runCatching { runGmsRecoveryCampaignTickLocked(generation) }
                        .onFailure { error ->
                            errorCount += 1
                            eventLocked(
                                "gms_recovery_campaign_failed",
                                "generation=$generation ${error.javaClass.simpleName}:${error.message}"
                            )
                            finishGmsRecoveryCampaignLocked(
                                generation,
                                "exception:${error.javaClass.simpleName}"
                            )
                        }
                }
            },
            0L,
            RecoveryCampaignPolicy.GMS_CAMPAIGN_TICK_MS,
            TimeUnit.MILLISECONDS
        )
        return statusJsonLocked()
    }

    private fun runGmsRecoveryCampaignTickLocked(generation: Long) {
        if (!running) {
            finishGmsRecoveryCampaignLocked(generation, "engine_stopped")
            return
        }
        val campaign = gmsRecoveryCampaign ?: return
        if (campaign.generation != generation) return
        val now = SystemClock.elapsedRealtime()
        val processesBefore = listGmsProcessesLocked()
        val pidsBefore = processesBefore.mapTo(linkedSetOf()) { it.pid }
        if (pidsBefore != campaign.lastObservedPids) {
            if (
                campaign.lastObservedPids.isNotEmpty() &&
                pidsBefore.isNotEmpty() &&
                pidsBefore != campaign.lastObservedPids
            ) {
                gmsPidRestartCount += 1
            }
            eventLocked(
                "gms_recovery_successor_observed",
                "generation=$generation pids=${pidsBefore.sorted()} previous=${campaign.lastObservedPids.sorted()}"
            )
            campaign.lastObservedPids = pidsBefore
            campaign.frozenPids.clear()
            campaign.stableSinceElapsed = 0L
            campaign.stabilizationStartedElapsed = 0L
            campaign.stabilizationDegradedSinceElapsed = 0L
            campaign.stabilizationLeaseRequestedElapsed = 0L
            if (pidsBefore.isNotEmpty()) {
                ensureGmsPreconnectionLeaseLocked(
                    campaign = campaign,
                    nowElapsed = now,
                    reason = "successor_observed",
                    force = true
                )
            }
        }

        ensureGmsPreconnectionLeaseLocked(
            campaign = campaign,
            nowElapsed = now,
            reason = "campaign_refresh",
            force = false
        )

        val frozenBefore = processesBefore.filter { readFreezeState(it.pid).frozen == true }
        val frozenBeforePids = frozenBefore.mapTo(linkedSetOf()) { it.pid }
        val newlyFrozen = frozenBeforePids - campaign.frozenPids
        if (newlyFrozen.isNotEmpty()) {
            gmsRecoverySuccessorRefreezeCount += newlyFrozen.size
            campaign.refreezeCount += newlyFrozen.size
            eventLocked(
                "gms_recovery_successor_refrozen",
                "generation=$generation pids=${newlyFrozen.sorted()} resetCount=${campaign.resetCount}"
            )
        }
        campaign.frozenPids.clear()
        campaign.frozenPids.addAll(frozenBeforePids)

        processesBefore.forEach { process ->
            if (readFreezeState(process.pid).frozen == true) {
                val result = unfreezeLocked(process)
                if (!result.stdout.contains("not_applicable_secondary_process")) {
                    actionCount += 1
                    if (!isUnfreezeAccepted(result)) commandFailureCount += 1
                }
            }
        }

        val processesAfter = listGmsProcessesLocked()
        val frozenAfter = processesAfter.filter { readFreezeState(it.pid).frozen == true }
        val anyFrozen = frozenAfter.isNotEmpty()
        val probe = probeGmsTransportLocked()
        applyGmsTransportProbeLocked(
            probe = probe,
            now = now,
            persistentRunning = processesAfter.any { it.name == "$GMS_PACKAGE.persistent" },
            source = "campaign"
        )

        if (!anyFrozen && probe.healthy) {
            if (campaign.stabilizationStartedElapsed <= 0L) {
                campaign.stabilizationStartedElapsed = now
                campaign.stabilizationLeaseRequestedElapsed = now
                val lease = ensureGmsPreconnectionLeaseLocked(
                    campaign = campaign,
                    nowElapsed = now,
                    reason = "transport_restored",
                    force = true
                )
                eventLocked(
                    "gms_recovery_stabilization_lease_started",
                    "generation=$generation ports=${probe.establishedPorts.sorted()} " +
                        "lease=${lease?.summary().orEmpty()} " +
                        "durationMs=${RecoveryCampaignPolicy.GMS_STABILIZATION_LEASE_MS}"
                )
            }
            campaign.stabilizationDegradedSinceElapsed = 0L
            if (campaign.stableSinceElapsed <= 0L || campaign.stableSinceElapsed > now) {
                campaign.stableSinceElapsed = now
                eventLocked(
                    "gms_recovery_stability_window_started",
                    "generation=$generation ports=${probe.establishedPorts.sorted()} " +
                        "requiredMs=${RecoveryCampaignPolicy.GMS_CAMPAIGN_STABLE_MS}"
                )
            }
            if (
                RecoveryCampaignPolicy.campaignStable(
                    nowElapsed = now,
                    stableSinceElapsed = campaign.stableSinceElapsed,
                    anyGmsFrozen = false,
                    transportHealthy = true
                )
            ) {
                finishGmsRecoveryCampaignLocked(generation, "stable_transport_verified")
                return
            }
        } else {
            campaign.stableSinceElapsed = 0L
            if (campaign.stabilizationStartedElapsed > 0L) {
                if (
                    campaign.stabilizationDegradedSinceElapsed <= 0L ||
                    campaign.stabilizationDegradedSinceElapsed > now
                ) {
                    campaign.stabilizationDegradedSinceElapsed = now
                    eventLocked(
                        "gms_recovery_stabilization_degraded",
                        "generation=$generation frozen=$anyFrozen ports=${probe.establishedPorts.sorted()}"
                    )
                }
            }
        }

        val stabilizationGraceActive =
            campaign.stabilizationStartedElapsed > 0L &&
                campaign.stabilizationDegradedSinceElapsed > 0L &&
                now - campaign.stabilizationDegradedSinceElapsed <
                    RecoveryCampaignPolicy.GMS_STABILIZATION_DEGRADED_GRACE_MS

        val vendorFamily = currentVendorFamilyLocked()
        val resetEligible = now >= campaign.nextResetEligibleElapsed
        if (
            !stabilizationGraceActive &&
            resetEligible &&
            RecoveryCampaignPolicy.shouldResetGmsAgain(
                nowElapsed = now,
                lastResetElapsed = campaign.lastResetElapsed,
                resetCount = campaign.resetCount,
                anyGmsFrozen = anyFrozen,
                transportHealthy = probe.healthy,
                maxResetCount = RecoveryCampaignPolicy.gmsMaxResetsPerCampaign(
                    vendorFamily
                )
            )
        ) {
            resetGmsPackageLocked(campaign, processesAfter, frozenAfter, probe)
        } else if (stabilizationGraceActive) {
            eventLocked(
                "gms_recovery_reset_deferred_stabilization_grace",
                "generation=$generation degradedForMs=" +
                    (now - campaign.stabilizationDegradedSinceElapsed).coerceAtLeast(0L) +
                    " frozen=$anyFrozen ports=${probe.establishedPorts.sorted()}"
            )
        } else if (
            !resetEligible &&
            !probe.healthy &&
            campaign.resetWaitReportedForCount != campaign.resetCount
        ) {
            campaign.resetWaitReportedForCount = campaign.resetCount
            eventLocked(
                "gms_recovery_reset_deferred_preconnection_lease",
                "generation=$generation resetCount=${campaign.resetCount} " +
                    "waitRemainingMs=" +
                    (campaign.nextResetEligibleElapsed - now).coerceAtLeast(0L) +
                    " frozen=$anyFrozen ports=${probe.establishedPorts.sorted()}"
            )
        }

        if (now >= campaign.deadlineElapsed) {
            val result = when {
                probe.healthy && anyFrozen -> "expired_transport_up_processes_frozen"
                !probe.healthy && !anyFrozen -> "expired_transport_missing"
                !probe.healthy && anyFrozen -> "expired_frozen_transport_missing"
                else -> "expired_not_stable"
            }
            finishGmsRecoveryCampaignLocked(generation, result)
            return
        }
        persistStatusLocked()
    }

    private fun resetGmsPackageLocked(
        campaign: GmsRecoveryCampaign,
        currentProcesses: List<GuardianProcess>,
        frozenProcesses: List<GuardianProcess>,
        transportProbe: GmsTransportProbe
    ) {
        val now = SystemClock.elapsedRealtime()
        campaign.resetCount += 1
        campaign.lastResetElapsed = now
        gmsRecoveryResetCount += 1
        val oldPids = currentProcesses.mapTo(linkedSetOf()) { it.pid }
        val details = mutableListOf<String>()
        val vendorFamily = currentVendorFamilyLocked()
        val campaignForceStopAllowed = RecoveryCampaignPolicy.shouldUseForceStopForGms(
            vendorFamily = vendorFamily,
            resetCount = campaign.resetCount,
            refreezeCount = campaign.refreezeCount,
            forceStopCount = campaign.forceStopCount
        )
        val forceStopDecision = RecoveryCampaignPolicy.decideGmsForceStop(
            nowElapsed = now,
            forceStopHistory = gmsForceStopHistory.toList()
        )
        val forceStopAllowed = campaignForceStopAllowed && forceStopDecision.allowed
        val forceStopBlockReason = when {
            !campaignForceStopAllowed -> "campaign_escalation_or_budget"
            !forceStopDecision.allowed -> forceStopDecision.reason
            else -> forceStopDecision.reason
        }
        eventLocked(
            "gms_recovery_reset_started",
            "generation=${campaign.generation} reset=${campaign.resetCount} oldPids=${oldPids.sorted()} " +
                "frozen=${frozenProcesses.map { "${it.name}:${it.pid}" }} " +
                "transport=${transportProbe.healthy} vendor=$vendorFamily " +
                "strategy=${if (forceStopAllowed) "force_stop_budget_available" else "non_destructive_or_stop_app"} " +
                "forceStopReason=$forceStopBlockReason " +
                "forceStops=${campaign.forceStopCount}/${RecoveryCampaignPolicy.gmsMaxForceStopsPerCampaign(vendorFamily)} " +
                "dailyForceStops=${gmsForceStopHistory.size}/${RecoveryCampaignPolicy.GMS_FORCE_STOP_MAX_PER_24_HOURS}"
        )

        var remainingOldPids: Set<Int> = oldPids
        var stopAppSucceeded = false
        if (!forceStopAllowed && supportsStopApp) {
            val stopResult = runner.run(
                "am", "stop-app", "--user", "0", GMS_PACKAGE,
                timeoutMs = GMS_STOP_APP_TIMEOUT_MS
            )
            actionCount += 1
            gmsRecoveryStopAppCount += 1
            details += "am_stop_app:${stopResult.summary()}"
            if (!stopResult.success) commandFailureCount += 1
            remainingOldPids = waitForGmsOldPidsRemovedLocked(oldPids, GMS_STOP_VERIFY_WAIT_MS)
            stopAppSucceeded = stopResult.success && remainingOldPids.isEmpty()
        }

        if (forceStopAllowed) {
            remainingOldPids = forceStopAndUnstopGmsLocked(
                campaign = campaign,
                oldPids = oldPids,
                details = details,
                reason = "bounded_vendor_or_refreeze_escalation"
            )
        } else if (!stopAppSucceeded) {
            details += "am_force_stop:deferred_$forceStopBlockReason"
            eventLocked(
                "gms_recovery_destructive_reset_deferred",
                "generation=${campaign.generation} reset=${campaign.resetCount} " +
                    "vendor=$vendorFamily forceStops=${campaign.forceStopCount} " +
                    "max=${RecoveryCampaignPolicy.gmsMaxForceStopsPerCampaign(vendorFamily)} " +
                    "globalReason=$forceStopBlockReason"
            )
        }

        val pulseResult = sendGmsBinderPulseLocked()
        details += "binder_pulse:${pulseResult.summary()}"
        if (pulseResult.success) {
            SystemClock.sleep(GMS_POST_BINDER_PULSE_SETTLE_MS)
            listGmsProcessesLocked().forEach { process ->
                val unfreezeResult = unfreezeLocked(process)
                if (!unfreezeResult.stdout.contains("not_applicable_secondary_process")) {
                    actionCount += 1
                    if (!isUnfreezeAccepted(unfreezeResult)) commandFailureCount += 1
                }
            }
        }
        wakeGmsDependentsLocked()
        tunePackageLocked(GMS_PACKAGE)
        val resetCompletedElapsed = SystemClock.elapsedRealtime()
        val nextWaitMs = RecoveryCampaignPolicy.gmsPostResetWaitMs(
            vendorFamily = vendorFamily,
            resetCount = campaign.resetCount,
            forceStopCount = campaign.forceStopCount
        )
        campaign.nextResetEligibleElapsed = if (nextWaitMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            resetCompletedElapsed + nextWaitMs
        }
        campaign.resetWaitReportedForCount = -1
        ensureGmsPreconnectionLeaseLocked(
            campaign = campaign,
            nowElapsed = resetCompletedElapsed,
            reason = "reset_${campaign.resetCount}_completed",
            force = true
        )
        campaign.commandDetails += details
        eventLocked(
            "gms_recovery_reset_completed",
            "generation=${campaign.generation} reset=${campaign.resetCount} " +
                "remainingOldPids=${remainingOldPids.sorted()} " +
                "nextResetEligibleElapsed=${campaign.nextResetEligibleElapsed} " +
                "commands=${details.joinToString(" | ")}"
        )
    }

    private fun forceStopAndUnstopGmsLocked(
        campaign: GmsRecoveryCampaign,
        oldPids: Set<Int>,
        details: MutableList<String>,
        reason: String
    ): Set<Int> {
        val canUnstop = verifyPackageUnstopBeforeForceStopLocked(GMS_PACKAGE, details)
        if (!canUnstop) {
            details += "am_force_stop:skipped_unstop_unavailable"
            eventLocked(
                "gms_recovery_force_stop_blocked",
                "generation=${campaign.generation} reset=${campaign.resetCount} " +
                    "reason=package_unstop_unavailable strategyReason=$reason"
            )
            return oldPids
        }

        campaign.forceStopCount += 1
        gmsForceStopHistory.addLast(SystemClock.elapsedRealtime())
        pruneGmsStateLocked(SystemClock.elapsedRealtime())
        eventLocked(
            "gms_recovery_force_stop_started",
            "generation=${campaign.generation} reset=${campaign.resetCount} " +
                "reason=$reason oldPids=${oldPids.sorted()}"
        )
        var remainingOldPids: Set<Int> = oldPids
        var forceStopAccepted = false
        for (attempt in 1..GMS_FORCE_STOP_MAX_ATTEMPTS) {
            val forceResult = runner.run(
                "am", "force-stop", "--user", "0", GMS_PACKAGE,
                timeoutMs = GMS_FORCE_STOP_TIMEOUT_MS
            )
            actionCount += 1
            gmsRecoveryForceStopCount += 1
            details += "am_force_stop#$attempt:${forceResult.summary()}"
            if (forceResult.success) {
                forceStopAccepted = true
            } else {
                commandFailureCount += 1
            }
            remainingOldPids = waitForGmsOldPidsRemovedLocked(
                oldPids,
                GMS_STOP_VERIFY_WAIT_MS
            )
            if (remainingOldPids.isEmpty()) break
            eventLocked(
                "gms_recovery_force_stop_retry",
                "generation=${campaign.generation} reset=${campaign.resetCount} " +
                    "attempt=$attempt remainingOldPids=${remainingOldPids.sorted()}"
            )
            SystemClock.sleep(GMS_FORCE_STOP_RETRY_DELAY_MS)
        }

        // The successful iQOO recovery sequence needs a real stopped-state
        // transition before clearing FLAG_STOPPED. Do not collapse force-stop
        // and unstop into the same scheduler slice.
        SystemClock.sleep(GMS_FORCE_STOP_SETTLE_MS)

        val unstopResult = runPackageUnstopWithRetryLocked(GMS_PACKAGE)
        details += "package_unstop:${unstopResult.summary()}"
        if (!unstopResult.success) {
            commandFailureCount += 1
            errorCount += 1
            eventLocked(
                "gms_recovery_unstop_failed",
                "generation=${campaign.generation} reset=${campaign.resetCount} " +
                    unstopResult.summary()
            )
        } else {
            SystemClock.sleep(GMS_POST_UNSTOP_SETTLE_MS)
        }

        val transitionVerified =
            forceStopAccepted && remainingOldPids.isEmpty() && unstopResult.success
        eventLocked(
            if (transitionVerified) {
                "gms_recovery_force_stop_unstop_verified"
            } else {
                "gms_recovery_force_stop_unstop_unverified"
            },
            "generation=${campaign.generation} reset=${campaign.resetCount} " +
                "remainingOldPids=${remainingOldPids.sorted()} reason=$reason " +
                "forceStopAccepted=$forceStopAccepted unstopAccepted=${unstopResult.success}"
        )
        return remainingOldPids
    }

    private fun verifyPackageUnstopBeforeForceStopLocked(
        packageName: String,
        details: MutableList<String>
    ): Boolean {
        if (supportsPackageUnstop) return true
        val probe = runner.run(
            "cmd", "package", "unstop", "--user", "0", packageName,
            timeoutMs = GMS_UNSTOP_TIMEOUT_MS
        )
        actionCount += 1
        details += "package_unstop_preflight:${probe.summary()}"
        if (probe.success) {
            supportsPackageUnstop = true
            eventLocked("package_unstop_runtime_verified", probe.summary())
            return true
        }
        commandFailureCount += 1
        return false
    }

    private fun runPackageUnstopWithRetryLocked(packageName: String): GuardianCommandResult {
        var result = runner.run(
            "cmd", "package", "unstop", "--user", "0", packageName,
            timeoutMs = GMS_UNSTOP_TIMEOUT_MS
        )
        actionCount += 1
        var attempt = 1
        while (!result.success && attempt < GMS_UNSTOP_MAX_ATTEMPTS) {
            SystemClock.sleep(GMS_UNSTOP_RETRY_DELAY_MS)
            result = runner.run(
                "cmd", "package", "unstop", "--user", "0", packageName,
                timeoutMs = GMS_UNSTOP_TIMEOUT_MS
            )
            actionCount += 1
            attempt += 1
        }
        return result
    }

    private fun readPackageStoppedFlagLocked(packageName: String): Boolean? {
        val result = runner.run(
            "dumpsys", "package", packageName,
            timeoutMs = PACKAGE_QUERY_TIMEOUT_MS
        )
        if (!result.success) return null
        val match = Regex("""\bstopped=(true|false)\b""")
            .find(result.stdout)
            ?: return null
        return match.groupValues[1].toBooleanStrictOrNull()
    }

    private fun waitForGmsOldPidsRemovedLocked(oldPids: Set<Int>, waitMs: Long): Set<Int> {
        if (oldPids.isEmpty()) return emptySet()
        val deadline = SystemClock.elapsedRealtime() + waitMs.coerceAtLeast(0L)
        while (true) {
            val current = listGmsProcessesLocked().mapTo(linkedSetOf()) { it.pid }
            val remaining = oldPids.intersect(current)
            if (remaining.isEmpty()) return emptySet()
            if (SystemClock.elapsedRealtime() >= deadline) return remaining
            SystemClock.sleep(GMS_STOP_VERIFY_POLL_MS)
        }
    }

    private fun sendGmsBinderPulseLocked(): GuardianCommandResult {
        val result = runner.run(
            "am", "broadcast", "--user", "0", "--receiver-foreground",
            "-a", GMS_BINDER_PULSE_ACTION,
            "-n", GMS_BINDER_PULSE_COMPONENT,
            timeoutMs = GMS_BINDER_PULSE_TIMEOUT_MS
        )
        actionCount += 1
        if (!result.success) commandFailureCount += 1
        eventLocked("gms_recovery_binder_pulse", result.summary())
        return result
    }

    private fun ensureGmsPreconnectionLeaseLocked(
        campaign: GmsRecoveryCampaign,
        nowElapsed: Long,
        reason: String,
        force: Boolean
    ): GuardianCommandResult? {
        if (
            !force &&
            campaign.preconnectionLeaseRequestedElapsed > 0L &&
            nowElapsed - campaign.preconnectionLeaseRequestedElapsed <
                RecoveryCampaignPolicy.GMS_PRECONNECTION_LEASE_REFRESH_MS
        ) {
            return null
        }
        val result = sendGmsStabilizationLeaseLocked()
        campaign.preconnectionLeaseRequestedElapsed = nowElapsed
        eventLocked(
            "gms_recovery_preconnection_lease_requested",
            "generation=${campaign.generation} reason=$reason " +
                "result=${result.summary()} durationMs=" +
                RecoveryCampaignPolicy.GMS_STABILIZATION_LEASE_MS
        )
        return result
    }

    private fun sendGmsStabilizationLeaseLocked(): GuardianCommandResult {
        val result = runner.run(
            "am", "broadcast", "--user", "0", "--receiver-foreground",
            "-a", GMS_BINDER_STABILIZATION_ACTION,
            "-n", GMS_BINDER_PULSE_COMPONENT,
            timeoutMs = GMS_BINDER_PULSE_TIMEOUT_MS
        )
        actionCount += 1
        if (!result.success) commandFailureCount += 1
        eventLocked("gms_recovery_stabilization_lease_requested", result.summary())
        return result
    }

    private fun finishGmsRecoveryCampaignLocked(generation: Long, result: String) {
        val campaign = gmsRecoveryCampaign ?: return
        if (campaign.generation != generation) return
        gmsRecoveryCampaignFuture?.cancel(false)
        gmsRecoveryCampaignFuture = null
        val now = SystemClock.elapsedRealtime()
        val finalProcesses = listGmsProcessesLocked()
        val finalPids = finalProcesses.map { it.pid }.sorted()
        val finalFrozen = finalProcesses.any { readFreezeState(it.pid).frozen == true }
        val finalProbe = probeGmsTransportLocked()
        applyGmsTransportProbeLocked(
            probe = finalProbe,
            now = now,
            persistentRunning = finalProcesses.any { it.name == "$GMS_PACKAGE.persistent" },
            source = "campaign_final"
        )
        val success = result == "stable_transport_verified" && !finalFrozen && finalProbe.healthy
        lastGmsRecoveryCompletedElapsed = now
        if (success) {
            gmsRecoverySuccessCount += 1
            gmsTransportVerifiedRecoveryCount += 1
            gmsFreezeEvents.clear()
            gmsConsecutiveCampaignFailures = 0
        } else {
            errorCount += 1
            gmsConsecutiveCampaignFailures =
                (gmsConsecutiveCampaignFailures + 1).coerceAtMost(1000)
        }
        gmsRecoveryInProgress = false
        gmsRecoveryCampaign = null
        if (campaign.manual) {
            gmsManualRecoveryState = if (success) "completed_success" else "completed_failure:$result"
        }
        lastGmsRecoveryOutcome = GmsRecoveryOutcome(
            trigger = campaign.trigger,
            result = if (success) "campaign_transport_verified" else result,
            oldPids = campaign.initialPids.toList().sorted(),
            newPids = finalPids,
            commandDetail = campaign.commandDetails.flatten().joinToString(" | ").take(2_000),
            transportObservable = finalProbe.observable,
            transportPorts = finalProbe.establishedPorts.toList().sorted(),
            startedElapsed = campaign.startedElapsed,
            completedElapsed = now
        )
        eventLocked(
            "gms_recovery_campaign_finished",
            "generation=$generation result=${lastGmsRecoveryOutcome.result} success=$success " +
                "resets=${campaign.resetCount} refreezes=${campaign.refreezeCount} " +
                "finalPids=$finalPids frozen=$finalFrozen ports=${finalProbe.establishedPorts.sorted()} " +
                "consecutiveFailures=$gmsConsecutiveCampaignFailures nextRetryMs=" +
                RecoveryCampaignPolicy.gmsAutomaticRetryIntervalMs(gmsConsecutiveCampaignFailures)
        )
        persistStatusLocked(force = true)
    }

    private fun cancelGmsRecoveryCampaignLocked(reason: String) {
        val campaign = gmsRecoveryCampaign
        gmsRecoveryCampaignFuture?.cancel(false)
        gmsRecoveryCampaignFuture = null
        gmsRecoveryCampaign = null
        gmsRecoveryInProgress = false
        if (campaign != null) {
            eventLocked(
                "gms_recovery_campaign_cancelled",
                "generation=${campaign.generation} reason=$reason resets=${campaign.resetCount}"
            )
        }
    }

    private fun wakeGmsDependentsLocked() {
        val dependents = GuardianProcessParser.matching(
            listProcessesLocked(),
            listOf(
                "com.whatsapp",
                "com.whatsapp.w4b",
                "org.thoughtcrime.securesms",
                "com.tailscale.ipn"
            )
        )
        dependents.forEach { process ->
            val result = unfreezeLocked(process)
            actionCount += 1
            if (!result.success) errorCount += 1
        }
        eventLocked(
            "gms_dependents_woken",
            "processes=${dependents.joinToString { "${it.name}:${it.pid}" }}"
        )
    }

    private fun listGmsProcessesLocked(): List<GuardianProcess> =
        GuardianProcessParser.matching(
            listProcessesLocked(),
            listOf(GMS_PACKAGE, "$GMS_PACKAGE.persistent")
        )

    private fun pruneGmsStateLocked(now: Long) {
        val freezeCutoff = (now - config.gmsFreezeWindowMs).coerceAtLeast(0L)
        while (gmsFreezeEvents.firstOrNull()?.let { it < freezeCutoff || it > now } == true) {
            gmsFreezeEvents.removeFirst()
        }
        val historyCutoff = (now - RECOVERY_HISTORY_WINDOW_MS).coerceAtLeast(0L)
        while (gmsRecoveryHistory.firstOrNull()?.let { it < historyCutoff || it > now } == true) {
            gmsRecoveryHistory.removeFirst()
        }
        while (gmsForceStopHistory.firstOrNull()?.let { it < historyCutoff || it > now } == true) {
            gmsForceStopHistory.removeFirst()
        }
    }

    private fun listProcessesLocked(): List<GuardianProcess> {
        val preferred = runner.run("ps", "-A", "-o", "PID,NAME,ARGS", timeoutMs = 6_000L)
        if (preferred.success) return GuardianProcessParser.parse(preferred.stdout)
        val fallback = runner.run("ps", "-A", timeoutMs = 6_000L)
        if (!fallback.success) {
            errorCount += 1
            eventLocked("process_scan_failed", fallback.summary())
            return emptyList()
        }
        return GuardianProcessParser.parse(fallback.stdout)
    }

    private fun unfreezeLocked(process: GuardianProcess): GuardianCommandResult {
        val ownerPackage = packageForProcess(process.name)
        if (GuardianTargetResolver.canUseActivityManagerUnfreeze(process.name, ownerPackage)) {
            return unfreezeProcessNameLocked(ownerPackage!!)
        }
        if (ownerPackage == null) {
            return notApplicableUnfreezeResult(process.name, "owner_package_unknown")
        }
        if (supportsSecondaryProcessUnfreeze) {
            return unfreezeProcessNameLocked(process.name)
        }
        if (secondaryUnfreezeProbeAttempted.add(process.name)) {
            val probe = unfreezeProcessNameLocked(process.name)
            if (isUnfreezeAccepted(probe)) {
                supportsSecondaryProcessUnfreeze = true
                eventLocked(
                    "secondary_process_unfreeze_supported",
                    "process=${process.name} result=${probe.summary()}"
                )
                return probe
            }
            eventLocked(
                "secondary_process_unfreeze_unsupported",
                "process=${process.name} result=${probe.summary()}"
            )
        }
        return notApplicableUnfreezeResult(process.name, "not_applicable_secondary_process")
    }

    private fun notApplicableUnfreezeResult(
        processName: String,
        reason: String
    ): GuardianCommandResult = GuardianCommandResult(
        command = listOf("am", "unfreeze", processName),
        exitCode = 0,
        stdout = reason,
        stderr = "",
        timedOut = false,
        durationMs = 0L
    )

    private fun unfreezePackageLocked(packageName: String): GuardianCommandResult =
        unfreezeProcessNameLocked(packageName)

    private fun unfreezeProcessNameLocked(processName: String): GuardianCommandResult {
        val command = mutableListOf("am", "unfreeze")
        if (config.stickyUnfreeze && supportsStickyUnfreeze) command += "--sticky"
        command += processName
        command += listOf("--user", "0")
        return runner.run(command, timeoutMs = 8_000L)
    }

    private fun isUnfreezeAccepted(result: GuardianCommandResult): Boolean =
        result.success && (
            result.stdout.contains("Unfreezing process", ignoreCase = true) ||
                result.stdout.contains("already unfrozen", ignoreCase = true)
            )

    private fun currentVendorFamilyLocked(): BackgroundPolicyVendorFamily {
        val cached = lastBackgroundPolicyReport.device.family
        if (cached != BackgroundPolicyVendorFamily.UNKNOWN) return cached
        val propertyNames = listOf(
            "ro.product.manufacturer",
            "ro.product.brand",
            "ro.product.model",
            "ro.product.name",
            "ro.product.device",
            "ro.product.vendor.brand",
            "ro.product.system.brand",
            "ro.vivo.os.version",
            "ro.vivo.os.name",
            "ro.vivo.product.overseas",
            "ro.build.version.opporom",
            "ro.build.version.oplusrom",
            "ro.rom.version",
            "ro.miui.ui.version.name",
            "ro.mi.os.version.name",
            "ro.mi.os.version.incremental",
            "ro.build.version.emui",
            "ro.build.version.magic",
            "ro.build.version.oneui"
        )
        val properties = propertyNames.associateWith { name ->
            runner.run("getprop", name, timeoutMs = PACKAGE_QUERY_TIMEOUT_MS)
                .stdout
                .trim()
        }
        return BackgroundPolicyVendorDetector.detect(properties).family
    }

    private fun tunePackagesLocked() {
        applyBackgroundPolicyLocked(
            JSONObject()
                .put("source", "scheduled_tune")
                .toString()
        )
    }

    private fun tunePackageLocked(packageName: String) {
        if (!GuardianEngineConfig.isSafePackageName(packageName)) return
        applyBackgroundPolicyLocked(
            JSONObject()
                .put("source", "targeted_tune")
                .put("packages", JSONArray().put(packageName))
                .toString()
        )
    }

    private fun applyBackgroundPolicyLocked(requestJson: String): BackgroundPolicyReport {
        val report = backgroundPolicyEngine.apply(config, requestJson)
        lastBackgroundPolicyReport = report
        actionCount += report.commandsAttempted
        errorCount += report.failedCommands
        eventLocked(
            "background_policy_applied",
            "vendor=${report.device.family} verified=${report.verifiedTargets}/${report.installedTargets} " +
                "commands=${report.commandsSucceeded}/${report.commandsAttempted} " +
                "oemUserAction=${report.requiresOemUserAction} source=${report.source}"
        )
        report.targets.forEach { target ->
            eventLocked(
                "background_policy_target",
                "${target.packageName} installed=${target.installed} verified=${target.fullyVerified} " +
                    "commands=${target.commandsSucceeded}/${target.commandsAttempted}"
            )
        }
        return report
    }

    private fun packageInstalled(packageName: String): Boolean {
        val modern = runner.run(
            "cmd", "package", "path", "--user", "0", packageName,
            timeoutMs = PACKAGE_QUERY_TIMEOUT_MS
        )
        if (modern.success && modern.stdout.lineSequence().any { it.startsWith("package:") }) {
            return true
        }
        val fallback = runner.run(
            "pm", "path", packageName,
            timeoutMs = PACKAGE_QUERY_TIMEOUT_MS
        )
        return fallback.success && fallback.stdout.lineSequence().any { it.startsWith("package:") }
    }

    private fun packageUid(packageName: String): Int? {
        val result = runner.run(
            "cmd", "package", "list", "packages", "-U", "--user", "0", packageName,
            timeoutMs = PACKAGE_QUERY_TIMEOUT_MS
        )
        val exact = result.stdout.lineSequence().firstOrNull { line ->
            line.startsWith("package:$packageName ") || line == "package:$packageName"
        }
        Regex("uid:(\\d+)").find(exact.orEmpty())
            ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        val fallback = runner.run(
            "dumpsys", "package", packageName,
            timeoutMs = PACKAGE_QUERY_TIMEOUT_MS
        )
        return Regex("(?:^|\\s)userId=(\\d+)(?:\\s|$)")
            .find(fallback.stdout)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun ensureCapabilitiesLocked() {
        if (capabilityChecked) return
        val id = runner.run("id")
        identity = id.stdout.ifBlank { "uid=${Process.myUid()} ${id.stderr}" }.take(300)
        val amHelp = runner.run("am", "help", timeoutMs = 10_000L)
        supportsStickyUnfreeze = amHelp.stdout.contains("unfreeze [--sticky]") ||
            amHelp.stdout.contains("unfreeze --sticky")
        supportsStopApp = amHelp.stdout.lineSequence().any { line ->
            line.trimStart().startsWith("stop-app")
        }
        val packageHelp = runner.run("cmd", "package", "help", timeoutMs = 10_000L)
        supportsPackageUnstop = packageHelp.success &&
            packageHelp.stdout.lineSequence().any { line ->
                line.contains("unstop")
            }
        val hibernationHelp = runner.run("cmd", "app_hibernation", "help", timeoutMs = 6_000L)
        supportsAppHibernation = hibernationHelp.success &&
            hibernationHelp.stdout.contains("set-state")
        capabilityChecked = true
        eventLocked(
            "capabilities_checked",
            "identity=$identity sticky=$supportsStickyUnfreeze stopApp=$supportsStopApp " +
                "packageUnstop=$supportsPackageUnstop hibernation=$supportsAppHibernation"
        )
    }

    private fun readFreezeState(pid: Int): FreezeEvidence {
        if (pid <= 0) return FreezeEvidence(null, "invalid_pid", null, null)
        val cgroupFile = File("/proc/$pid/cgroup")
        val lines = runCatching { cgroupFile.readLines() }.getOrElse {
            return FreezeEvidence(null, "cgroup_unreadable:${it.javaClass.simpleName}", null, null)
        }
        for (line in lines) {
            val parts = line.split(':', limit = 3)
            if (parts.size != 3) continue
            val controllers = parts[1]
            val relative = parts[2]
            if (!SAFE_CGROUP_PATH.matches(relative)) continue
            val candidates = buildList {
                if (controllers.isBlank()) {
                    add(File("/sys/fs/cgroup$relative/cgroup.freeze") to "cgroup2.freeze")
                    add(File("/sys/fs/cgroup$relative/cgroup.events") to "cgroup2.events")
                }
                if (controllers.split(',').contains("freezer")) {
                    add(File("/sys/fs/cgroup/freezer$relative/freezer.state") to "cgroup1.state")
                }
            }
            candidates.forEach { (file, kind) ->
                val text = runCatching { file.readText().trim() }.getOrNull() ?: return@forEach
                val frozen = when (kind) {
                    "cgroup2.freeze" -> text.lineSequence().firstOrNull()?.trim() == "1"
                    "cgroup2.events" -> Regex("(?:^|\\n)frozen\\s+1(?:$|\\n)").containsMatchIn(text)
                    "cgroup1.state" -> text.equals("FROZEN", ignoreCase = true)
                    else -> false
                }
                return FreezeEvidence(frozen, "$kind:$text", file, kind)
            }
        }
        return FreezeEvidence(null, "freeze_control_not_visible", null, null)
    }

    private fun directCgroupThaw(evidence: FreezeEvidence): Boolean? {
        val file = evidence.controlFile ?: return null
        return runCatching {
            when (evidence.controlKind) {
                "cgroup2.freeze" -> file.writeText("0")
                "cgroup2.events" -> File(file.parentFile, "cgroup.freeze").writeText("0")
                "cgroup1.state" -> file.writeText("THAWED")
                else -> return null
            }
            true
        }.getOrElse { error ->
            eventLocked(
                "cgroup_thaw_unavailable",
                "path=${file.path} ${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            false
        }
    }

    private fun eventLocked(type: String, detail: String) {
        if (recentEvents.size >= MAX_EVENTS) recentEvents.removeFirst()
        val event = GuardianEvent(
            elapsed = SystemClock.elapsedRealtime(),
            wallTimeMillis = System.currentTimeMillis(),
            type = type,
            detail = detail.take(MAX_EVENT_DETAIL)
        )
        recentEvents.addLast(event)
        if (!diagnosticStore.appendEvent(event.toJson())) {
            diagnosticWriteErrorCount += 1
        }
    }

    private fun persistStatusLocked(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (
            !force &&
            lastDiagnosticStatusWriteElapsed > 0L &&
            now >= lastDiagnosticStatusWriteElapsed &&
            now - lastDiagnosticStatusWriteElapsed < DIAGNOSTIC_STATUS_INTERVAL_MS
        ) {
            return
        }
        val snapshot = statusJsonLocked()
        cachedStatusJson = snapshot
        if (!diagnosticStore.writeStatus(snapshot)) {
            diagnosticWriteErrorCount += 1
        } else {
            lastDiagnosticStatusWriteElapsed = now
        }
    }

    private fun statusJsonLocked(): String = JSONObject()
        .put("schema", STATUS_SCHEMA)
        .put("engine", "PrivilegedGuardianEngine")
        .put("running", running)
        .put("uid", Process.myUid())
        .put("identity", identity)
        .put("startedElapsed", startedElapsed)
        .put("lastCycleElapsed", lastCycleElapsed)
        .put("lastTuneElapsed", lastTuneElapsed)
        .put("cycleCount", cycleCount)
        .put("actionCount", actionCount)
        .put("errorCount", errorCount)
        .put("commandFailureCount", commandFailureCount)
        .put("verificationFailureCount", verificationFailureCount)
        .put("effectiveThawCount", effectiveThawCount)
        .put("noSafeCommandPathCount", noSafeCommandPathCount)
        .put("recoveryEscalationCount", recoveryEscalationCount)
        .put("directCgroupAttemptCount", directCgroupAttemptCount)
        .put("directCgroupSuccessCount", directCgroupSuccessCount)
        .put("diagnosticWriteErrorCount", diagnosticWriteErrorCount)
        .put("diagnosticStatusPath", GuardianDiagnosticStore.STATUS_PATH)
        .put("diagnosticEventsPath", GuardianDiagnosticStore.EVENTS_PATH)
        .put("supportsStickyUnfreeze", supportsStickyUnfreeze)
        .put("supportsSecondaryProcessUnfreeze", supportsSecondaryProcessUnfreeze)
        .put("supportsStopApp", supportsStopApp)
        .put("supportsPackageUnstop", supportsPackageUnstop)
        .put("supportsAppHibernation", supportsAppHibernation)
        .put("eventWatcherAlive", eventWatcherAlive)
        .put("eventTriggerCount", eventTriggerCount)
        .put("vendorSignalCount", vendorSignalCount)
        .put("vendorDeliveryFailureCount", vendorDeliveryFailureCount)
        .put("vendorRecoveryPassCount", vendorRecoveryPassCount)
        .put("packageRebuildAttemptCount", packageRebuildAttemptCount)
        .put("packageRebuildSuccessCount", packageRebuildSuccessCount)
        .put("packageRebuildInProgress", JSONArray(packageRebuildInProgress.toList()))
        .put("packageSuccessorGuardStartCount", packageSuccessorGuardStartCount)
        .put("packageSuccessorGuardRefreezeCount", packageSuccessorGuardRefreezeCount)
        .put("packageSuccessorGuardVerifiedCount", packageSuccessorGuardVerifiedCount)
        .put("packageSuccessorGuards", JSONObject().apply {
            packageSuccessorGuardByPackage.forEach { (packageName, guard) ->
                put(packageName, guard.toJson())
            }
        })
        .put("packageSuccessorCircuitUntil", JSONObject().apply {
            packageSuccessorCircuitUntilByPackage.forEach { (packageName, until) ->
                put(packageName, until)
            }
        })
        .put("packageCircuitDeliveryRescueCount", packageCircuitDeliveryRescueCount)
        .put("lastPackageCircuitDeliveryRescue", JSONObject().apply {
            lastPackageCircuitDeliveryRescueByPackage.forEach { (packageName, elapsed) ->
                put(packageName, elapsed)
            }
        })
        .put("deliveryProtectionStartCount", deliveryProtectionStartCount)
        .put("deliveryProtectionCompletionCount", deliveryProtectionCompletionCount)
        .put("deliveryProtectionKillCount", deliveryProtectionKillCount)
        .put("deliveryProtectionLeases", JSONObject().apply {
            deliveryProtectionByPackage.forEach { (packageName, lease) ->
                put(packageName, lease.toJson())
            }
        })
        .put("lastPackageRebuild", lastPackageRebuildOutcome.toJson())
        .put("deliveryFailureEpisodes", JSONObject().apply {
            deliveryFailureEpisodesByPackage.forEach { (packageName, episodes) ->
                put(packageName, JSONArray(episodes.toList()))
            }
        })
        .put("vendorRecovery", JSONObject().apply {
            val packages = linkedSetOf<String>().apply {
                addAll(vendorSignalCountByPackage.keys)
                addAll(vendorHoldUntilByPackage.keys)
                addAll(vendorRelapseCountByPackage.keys)
            }
            packages.forEach { packageName ->
                put(packageName, JSONObject()
                    .put("signals", vendorSignalCountByPackage[packageName] ?: 0L)
                    .put("relapses", vendorRelapseCountByPackage[packageName] ?: 0L)
                    .put("lastSignalElapsed", lastVendorSignalByPackage[packageName] ?: 0L)
                    .put("lastActionElapsed", lastVendorActionByPackage[packageName] ?: 0L)
                    .put("holdUntilElapsed", vendorHoldUntilByPackage[packageName] ?: 0L)
                    .put("generation", vendorRecoveryGenerationByPackage[packageName] ?: 0L)
                    .put("critical", vendorRecoveryCriticalByPackage[packageName] == true)
                    .put("signalKind", vendorRecoverySignalKindByPackage[packageName]?.name.orEmpty())
                    .put("exhaustedUntilElapsed", vendorRecoveryExhaustedUntilByPackage[packageName] ?: 0L)
                )
            }
        })
        .put("root", Process.myUid() == 0)
        .put("gmsRecoveryEnabled", config.gmsRecoveryEnabled)
        .put("vendorEmergencyRecoveryEnabled", config.vendorEmergencyRecoveryEnabled)
        .put("gmsRecoveryInProgress", gmsRecoveryInProgress)
        .put("gmsManualRecoveryQueued", gmsManualRecoveryQueued)
        .put("gmsManualRecoveryRequestId", gmsManualRecoveryRequestId)
        .put("gmsManualRecoveryState", gmsManualRecoveryState)
        .put("gmsFreezeEventsInWindow", gmsFreezeEvents.size)
        .put("gmsRecoveryAttemptCount", gmsRecoveryAttemptCount)
        .put("gmsRecoverySuccessCount", gmsRecoverySuccessCount)
        .put("gmsRecoveryGeneration", gmsRecoveryGeneration)
        .put("gmsRecoveryResetCount", gmsRecoveryResetCount)
        .put("gmsRecoveryStopAppCount", gmsRecoveryStopAppCount)
        .put("gmsRecoveryForceStopCount", gmsRecoveryForceStopCount)
        .put("gmsRecoverySuccessorRefreezeCount", gmsRecoverySuccessorRefreezeCount)
        .put("gmsRecoveryCampaign", gmsRecoveryCampaign?.toJson() ?: JSONObject.NULL)
        .put("gmsPidRestartCount", gmsPidRestartCount)
        .put("gmsTransportVerifiedRecoveryCount", gmsTransportVerifiedRecoveryCount)
        .put("lastGmsRecoveryElapsed", lastGmsRecoveryElapsed)
        .put("lastGmsRecoveryCompletedElapsed", lastGmsRecoveryCompletedElapsed)
        .put("gmsConsecutiveCampaignFailures", gmsConsecutiveCampaignFailures)
        .put(
            "gmsNextAutomaticRetryIntervalMs",
            RecoveryCampaignPolicy.gmsAutomaticRetryIntervalMs(gmsConsecutiveCampaignFailures)
        )
        .put("gmsForceStopHistoryCount", gmsForceStopHistory.size)
        .put("lastGmsRecovery", lastGmsRecoveryOutcome.toJson())
        .put("gmsTransport", JSONObject()
            .put("observable", lastGmsTransportProbe.observable)
            .put("healthy", lastGmsTransportProbe.healthy)
            .put("establishedPorts", JSONArray(lastGmsTransportProbe.establishedPorts.toList().sorted()))
            .put("detail", lastGmsTransportProbe.detail)
            .put("lastProbeElapsed", lastGmsTransportProbeElapsed)
            .put("lastHealthyElapsed", lastGmsTransportHealthyElapsed)
            .put("missingSinceElapsed", gmsTransportMissingSinceElapsed)
            .put("consecutiveMissing", gmsTransportConsecutiveMissing)
            .put("probeCount", gmsTransportProbeCount)
            .put("healthyCount", gmsTransportHealthyCount)
            .put("unobservableCount", gmsTransportUnobservableCount)
            .put("badAuthenticationCount", gmsBadAuthenticationCount)
            .put("lastBadAuthenticationElapsed", lastGmsBadAuthenticationElapsed)
            .put("lastMcsConnectAttemptElapsed", lastGmsMcsConnectAttemptElapsed)
        )
        .put("adbTcp5555", JSONObject()
            .put("armed", adbTcp5555ObservedHealthy || adbTcp5555Configured)
            .put("configured", adbTcp5555Configured)
            .put("listenerPresent", adbTcp5555ListenerPresent)
            .put("observedHealthy", adbTcp5555ObservedHealthy)
            .put("lastProbeElapsed", adbTcp5555LastProbeElapsed)
            .put("lastHealthyElapsed", adbTcp5555LastHealthyElapsed)
            .put("missingSinceElapsed", adbTcp5555MissingSinceElapsed)
            .put("lastRecoveryElapsed", adbTcp5555LastRecoveryElapsed)
            .put("probeCount", adbTcp5555ProbeCount)
            .put("recoveryCount", adbTcp5555RecoveryCount)
        )
        .put("protectionHealth", protectionHealthJsonLocked())
        .put("backgroundPolicy", lastBackgroundPolicyReport.toJsonObject())
        .put("config", JSONObject(config.toJson()))
        .put("processes", JSONArray().apply {
            latestProcesses.forEach { state -> put(state.toJson()) }
        })
        .put("events", JSONArray().apply {
            recentEvents.forEach { event -> put(event.toJson()) }
        })
        .toString()

    private fun protectionHealthJsonLocked(): JSONObject {
        val frozen = latestProcesses.filter { it.afterFrozen == true }
        val transportDegraded = lastGmsTransportProbe.observable && !lastGmsTransportProbe.healthy
        val level = when {
            !running -> "GRAY"
            frozen.isNotEmpty() || (transportDegraded && gmsTransportConsecutiveMissing >= 3) -> "RED"
            !eventWatcherAlive || gmsRecoveryInProgress ||
                packageRebuildInProgress.isNotEmpty() ||
                packageSuccessorGuardByPackage.isNotEmpty() ||
                deliveryProtectionByPackage.isNotEmpty() || transportDegraded -> "YELLOW"
            else -> "GREEN"
        }
        return JSONObject()
            .put("level", level)
            .put("engineOnline", running)
            .put("watcherAlive", eventWatcherAlive)
            .put("frozenProcesses", JSONArray(frozen.map { "${it.name}:${it.pid}" }))
            .put("gmsTransportObservable", lastGmsTransportProbe.observable)
            .put("gmsTransportHealthy", lastGmsTransportProbe.healthy)
            .put(
                "recoveryInProgress",
                gmsRecoveryInProgress || packageRebuildInProgress.isNotEmpty() ||
                    packageSuccessorGuardByPackage.isNotEmpty() ||
                    deliveryProtectionByPackage.isNotEmpty()
            )
    }

    private enum class BackgroundLauncherWakeResult {
        STARTED,
        DEFERRED_SCREEN_INTERACTIVE,
        RESOLVE_FAILED,
        START_FAILED,
        NOT_ALLOWED
    }

    private data class PackageProcessRemoval(
        val verified: Boolean,
        val oldPids: Set<Int>,
        val remainingOldPids: Set<Int>,
        val commandDetail: String,
        val result: String
    )

    private data class DeliveryProtectionLease(
        val packageName: String,
        val generation: Long,
        val startedElapsed: Long,
        var deadlineElapsed: Long,
        var lastSignalElapsed: Long,
        var deliveryEpisodeCount: Int,
        var killCount: Int = 0,
        var lastTuneElapsed: Long = 0L,
        var lastBinderPulseElapsed: Long = 0L,
        var lastLauncherElapsed: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("packageName", packageName)
            .put("generation", generation)
            .put("startedElapsed", startedElapsed)
            .put("deadlineElapsed", deadlineElapsed)
            .put("lastSignalElapsed", lastSignalElapsed)
            .put("deliveryEpisodeCount", deliveryEpisodeCount)
            .put("killCount", killCount)
            .put("lastTuneElapsed", lastTuneElapsed)
            .put("lastBinderPulseElapsed", lastBinderPulseElapsed)
            .put("lastLauncherElapsed", lastLauncherElapsed)
    }

    private data class PackageSuccessorGuard(
        val packageName: String,
        val trigger: String,
        val generation: Long,
        val startedElapsed: Long,
        val deadlineElapsed: Long,
        var lastObservedPids: Set<Int> = emptySet(),
        val frozenPids: MutableSet<Int> = linkedSetOf(),
        var stableSinceElapsed: Long = 0L,
        var lastResetElapsed: Long = 0L,
        var resetCount: Int = 0,
        var refreezeCount: Int = 0,
        var absentSinceElapsed: Long = 0L,
        var lastAbsencePulseElapsed: Long = 0L,
        var lastBackgroundLaunchElapsed: Long = 0L,
        var backgroundLaunchCount: Int = 0,
        var circuitRescueAttempted: Boolean = false,
        var longGuardReported: Boolean = false,
        var resetBudgetReported: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("packageName", packageName)
            .put("trigger", trigger)
            .put("generation", generation)
            .put("startedElapsed", startedElapsed)
            .put("deadlineElapsed", deadlineElapsed)
            .put("lastObservedPids", JSONArray(lastObservedPids.toList().sorted()))
            .put("frozenPids", JSONArray(frozenPids.toList().sorted()))
            .put("stableSinceElapsed", stableSinceElapsed)
            .put("lastResetElapsed", lastResetElapsed)
            .put("resetCount", resetCount)
            .put("refreezeCount", refreezeCount)
            .put("absentSinceElapsed", absentSinceElapsed)
            .put("lastAbsencePulseElapsed", lastAbsencePulseElapsed)
            .put("lastBackgroundLaunchElapsed", lastBackgroundLaunchElapsed)
            .put("backgroundLaunchCount", backgroundLaunchCount)
            .put("circuitRescueAttempted", circuitRescueAttempted)
            .put("longGuardReported", longGuardReported)
            .put("resetBudgetReported", resetBudgetReported)
    }

    private data class GmsRecoveryCampaign(
        val trigger: String,
        val manual: Boolean,
        val generation: Long,
        val startedElapsed: Long,
        val deadlineElapsed: Long,
        val initialPids: Set<Int>,
        var lastObservedPids: Set<Int> = emptySet(),
        val frozenPids: MutableSet<Int> = linkedSetOf(),
        var stableSinceElapsed: Long = 0L,
        var lastResetElapsed: Long = 0L,
        var resetCount: Int = 0,
        var refreezeCount: Int = 0,
        var forceStopCount: Int = 0,
        var nextResetEligibleElapsed: Long = 0L,
        var resetWaitReportedForCount: Int = -1,
        var preconnectionLeaseRequestedElapsed: Long = 0L,
        var stabilizationStartedElapsed: Long = 0L,
        var stabilizationDegradedSinceElapsed: Long = 0L,
        var stabilizationLeaseRequestedElapsed: Long = 0L,
        val commandDetails: MutableList<List<String>> = mutableListOf()
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("trigger", trigger)
            .put("manual", manual)
            .put("generation", generation)
            .put("startedElapsed", startedElapsed)
            .put("deadlineElapsed", deadlineElapsed)
            .put("initialPids", JSONArray(initialPids.toList().sorted()))
            .put("lastObservedPids", JSONArray(lastObservedPids.toList().sorted()))
            .put("frozenPids", JSONArray(frozenPids.toList().sorted()))
            .put("stableSinceElapsed", stableSinceElapsed)
            .put("lastResetElapsed", lastResetElapsed)
            .put("resetCount", resetCount)
            .put("refreezeCount", refreezeCount)
            .put("forceStopCount", forceStopCount)
            .put("nextResetEligibleElapsed", nextResetEligibleElapsed)
            .put("resetWaitReportedForCount", resetWaitReportedForCount)
            .put("preconnectionLeaseRequestedElapsed", preconnectionLeaseRequestedElapsed)
            .put("stabilizationStartedElapsed", stabilizationStartedElapsed)
            .put("stabilizationDegradedSinceElapsed", stabilizationDegradedSinceElapsed)
            .put("stabilizationLeaseRequestedElapsed", stabilizationLeaseRequestedElapsed)
    }

    private data class GuardianProcessState(
        val pid: Int,
        val name: String,
        val ownerPackage: String,
        val beforeFrozen: Boolean?,
        val afterFrozen: Boolean?,
        val freezeEvidence: String,
        val action: String,
        val commandAccepted: Boolean?,
        val thawVerified: Boolean?,
        val recoveryVerdict: String,
        val actionDetail: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("pid", pid)
            .put("name", name)
            .put("ownerPackage", ownerPackage)
            .put("beforeFrozen", beforeFrozen ?: JSONObject.NULL)
            .put("afterFrozen", afterFrozen ?: JSONObject.NULL)
            .put("frozen", afterFrozen ?: JSONObject.NULL)
            .put("freezeEvidence", freezeEvidence)
            .put("action", action)
            .put("commandAccepted", commandAccepted ?: JSONObject.NULL)
            .put("actionSuccess", commandAccepted ?: JSONObject.NULL)
            .put("thawVerified", thawVerified ?: JSONObject.NULL)
            .put("recoveryVerdict", recoveryVerdict)
            .put("actionDetail", actionDetail)
    }

    private data class GuardianEvent(
        val elapsed: Long,
        val wallTimeMillis: Long,
        val type: String,
        val detail: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("elapsed", elapsed)
            .put("wallTimeMillis", wallTimeMillis)
            .put("type", type)
            .put("detail", detail)
    }

    private data class GmsRecoveryOutcome(
        val trigger: String = "",
        val result: String = "never",
        val oldPids: List<Int> = emptyList(),
        val newPids: List<Int> = emptyList(),
        val commandDetail: String = "",
        val transportObservable: Boolean = false,
        val transportPorts: List<Int> = emptyList(),
        val startedElapsed: Long = 0L,
        val completedElapsed: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("trigger", trigger)
            .put("result", result)
            .put("oldPids", JSONArray(oldPids))
            .put("newPids", JSONArray(newPids))
            .put("commandDetail", commandDetail)
            .put("transportObservable", transportObservable)
            .put("transportPorts", JSONArray(transportPorts))
            .put("startedElapsed", startedElapsed)
            .put("completedElapsed", completedElapsed)
    }

    private data class PackageRebuildOutcome(
        val packageName: String = "",
        val trigger: String = "",
        val result: String = "never",
        val oldPids: List<Int> = emptyList(),
        val remainingOldPids: List<Int> = emptyList(),
        val commandDetail: String = "",
        val startedElapsed: Long = 0L,
        val completedElapsed: Long = 0L
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("packageName", packageName)
            .put("trigger", trigger)
            .put("result", result)
            .put("oldPids", JSONArray(oldPids))
            .put("remainingOldPids", JSONArray(remainingOldPids))
            .put("commandDetail", commandDetail)
            .put("startedElapsed", startedElapsed)
            .put("completedElapsed", completedElapsed)
    }

    private data class FreezeEvidence(
        val frozen: Boolean?,
        val detail: String,
        val controlFile: File?,
        val controlKind: String?
    )

    companion object {
        private const val STATUS_SCHEMA = 13
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val GMS_STOP_APP_TIMEOUT_MS = 10_000L
        private const val GMS_FORCE_STOP_TIMEOUT_MS = 10_000L
        private const val GMS_UNSTOP_TIMEOUT_MS = 8_000L
        private const val GMS_UNSTOP_MAX_ATTEMPTS = 3
        private const val GMS_UNSTOP_RETRY_DELAY_MS = 500L
        private const val GMS_FORCE_STOP_MAX_ATTEMPTS = 1
        private const val GMS_FORCE_STOP_RETRY_DELAY_MS = 500L
        private const val GMS_FORCE_STOP_SETTLE_MS = 2_000L
        private const val GMS_POST_UNSTOP_SETTLE_MS = 750L
        private const val GMS_POST_BINDER_PULSE_SETTLE_MS = 1_000L
        private const val GMS_STOP_VERIFY_WAIT_MS = 5_000L
        private const val GMS_STOP_VERIFY_POLL_MS = 500L
        private const val GMS_BINDER_PULSE_TIMEOUT_MS = 10_000L
        private const val GMS_BINDER_PULSE_ACTION =
            "com.yubegreen.luonnotar.action.ADB_GMS_BINDER_PULSE_TEST"
        private const val GMS_BINDER_STABILIZATION_ACTION =
            "com.yubegreen.luonnotar.action.ADB_GMS_BINDER_STABILIZATION_LEASE"
        private const val GMS_BINDER_PULSE_COMPONENT =
            "com.yubegreen.luonnotar/.receiver.AdbGmsBinderPulseReceiver"
        private const val GMS_FREEZE_EVENT_DEBOUNCE_MS = 5_000L
        private const val GMS_LOG_SIGNAL_DEBOUNCE_MS = 5_000L
        private const val GMS_FROZEN_TRANSPORT_PROBE_INTERVAL_MS = 10_000L
        private const val SOCKET_PROBE_TIMEOUT_MS = 4_000L
        private const val PACKAGE_KILL_TIMEOUT_MS = 8_000L
        private const val PACKAGE_STOP_APP_TIMEOUT_MS = 8_000L
        private const val PACKAGE_FORCE_STOP_TIMEOUT_MS = 10_000L
        private const val PACKAGE_FORCE_STOP_MAX_ATTEMPTS = 2
        private const val PACKAGE_FORCE_STOP_RETRY_DELAY_MS = 500L
        private const val PACKAGE_FORCE_STOP_SETTLE_MS = 2_000L
        private const val PACKAGE_POST_UNSTOP_SETTLE_MS = 750L
        private const val PACKAGE_KILL_VERIFY_WAIT_MS = 5_000L
        private const val PACKAGE_STOP_VERIFY_WAIT_MS = 5_000L
        private const val PACKAGE_KILL_VERIFY_POLL_MS = 500L
        private const val PACKAGE_LAUNCH_RESOLVE_TIMEOUT_MS = 5_000L
        private const val PACKAGE_LAUNCH_TIMEOUT_MS = 8_000L
        private const val PACKAGE_LAUNCH_HOME_DELAY_MS = 750L
        private const val DELIVERY_PROTECTION_TUNE_INTERVAL_MS = 10_000L
        private const val DELIVERY_PROTECTION_BINDER_PULSE_INTERVAL_MS = 15_000L
        private const val DIAGNOSTIC_STATUS_INTERVAL_MS = 30_000L
        private const val INITIAL_CYCLE_DELAY_MS = 1_000L
        private const val RECOVERY_HISTORY_WINDOW_MS = 24L * 60L * 60L * 1_000L
        private const val MAX_EVENTS = 128
        private const val MAX_EVENT_DETAIL = 500
        private const val TUNING_COMMAND_TIMEOUT_MS = 4_000L
        private const val PACKAGE_QUERY_TIMEOUT_MS = 3_000L
        private val DELIVERY_PACKAGE_TARGETS =
            setOf("com.whatsapp", "com.whatsapp.w4b")
        private val BACKGROUND_LAUNCH_WAKE_TARGETS =
            setOf("com.whatsapp", "com.whatsapp.w4b", "com.termux")
        private val MANAGED_LIVENESS_PACKAGES = setOf("com.termux")
        private val SAFE_CGROUP_PATH = Regex("^/[A-Za-z0-9_./:@-]+$")
    }
}
