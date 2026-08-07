package com.yubegreen.luonnotar.privileged

import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
    private val gmsFastThawExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-gms-fast-thaw").apply { isDaemon = true }
    }
    private val gmsFastThawWorkerActive = AtomicBoolean(false)
    private val gmsFastThawPendingSignalElapsed = AtomicLong(0L)
    private val recentEvents = ArrayDeque<GuardianEvent>()
    private var scheduled: ScheduledFuture<*>? = null
    private var initialCycleFuture: ScheduledFuture<*>? = null
    private var eventWatcherRestartFuture: ScheduledFuture<*>? = null
    private var eventFastLaneReadyTimeoutFuture: ScheduledFuture<*>? = null
    private var eventWatcherProcess: java.lang.Process? = null
    private var consecutiveGmsMcsKickExhaustions = 0
    private var lastGmsMcsEmergencyEscalationElapsed = 0L
    private var eventWatcherThread: Thread? = null
    private var eventWatcherAlive = false
    private var eventWatcherMode = "none"
    private var eventFastLaneReady = false
    private var eventFastLaneBackend = "none"
    private var eventFastLaneTimeoutSupported = false
    private var eventFastLaneStickyConfigured = false
    private var eventFastLaneTargetEnabled = false
    private var eventFastLaneStartCount = 0L
    private var eventFastLaneFailureCount = 0L
    private var eventFastLaneSignalCount = 0L
    private var eventFastLaneImmediateAttemptCount = 0L
    private var eventFastLaneImmediateAcceptedCount = 0L
    private var eventFastLaneImmediateSkippedCount = 0L
    private var eventFastLaneShieldCompletionCount = 0L
    private var eventFastLaneShieldCommandCount = 0L
    private var eventFastLaneShieldAcceptedCount = 0L
    private var eventFastLaneFrozenPollCount = 0L
    private var eventFastLaneVerifiedThawCount = 0L
    private var eventFastLaneBlindReassertCount = 0L
    private var eventFastLaneExhaustedCount = 0L
    private var eventFastLaneLastEpisode = 0L
    private var eventFastLaneLastState = "never"
    private var eventFastLaneLastSignalElapsed = 0L
    private var eventFastLaneLastImmediateLatencyMs = 0L
    private var eventFastLaneMaxImmediateLatencyMs = 0L
    private val eventFastLaneSignalElapsedBySequence = linkedMapOf<Long, Long>()
    private val eventFastLaneProbeHandledSequences = linkedSetOf<Long>()
    private val eventFastLaneVerifiedSequences = linkedSetOf<Long>()
    private var eventFastLaneLastPostRecoveryElapsed = 0L
    private var eventFastLanePostRecoveryCount = 0L

    // r259: the independent cgroup sentinel is the single command owner and
    // treats repeated OEM refreezes as one durable defense episode.
    private var vendorBridgeRestartFuture: ScheduledFuture<*>? = null
    private var vendorBridgeReadyTimeoutFuture: ScheduledFuture<*>? = null
    private var vendorBridgeProcess: java.lang.Process? = null
    private var vendorBridgeThread: Thread? = null
    private var vendorBridgeDeviceFamily: BackgroundPolicyVendorFamily? = null
    private var vendorBridgeConfiguredTargets: Set<String> = emptySet()
    private var vendorBridgeAlive = false
    private var vendorBridgeReady = false
    private var vendorBridgeTimeoutSupported = false
    private var vendorBridgeStickyConfigured = false
    private var vendorBridgeStartCount = 0L
    private var vendorBridgeFailureCount = 0L
    private var vendorBridgeHeartbeatCount = 0L
    private var vendorBridgeLastHeartbeatElapsed = 0L
    private var vendorBridgeFrozenCount = 0L
    private var vendorBridgeRecoveryAttemptCount = 0L
    private var vendorBridgePlainRecoveryCount = 0L
    private var vendorBridgeAdoptReleaseCount = 0L
    private var vendorBridgeAdoptUnconfirmedCount = 0L
    private var vendorBridgeFrameworkLedgerRetryCount = 0L
    private var vendorBridgeVerifiedRecoveryCount = 0L
    private var vendorBridgeFailedRecoveryCount = 0L
    private var vendorBridgeLockCount = 0L
    private var vendorBridgeLastLockElapsed = 0L
    private var vendorBridgeLastTarget = "never"
    private var vendorBridgeLastMode = "never"
    private var vendorBridgeLastState = "never"
    private var vendorBridgeLastPid = 0
    private var vendorBridgeMainPid = 0
    private var vendorBridgeMainState = "unknown"
    private var vendorBridgePersistentPid = 0
    private var vendorBridgePersistentState = "unknown"
    private var vendorBridgeWhatsappPid = 0
    private var vendorBridgeWhatsappState = "disabled"
    private var vendorBridgeSignalPid = 0
    private var vendorBridgeSignalState = "disabled"
    private var vendorBridgeLastRecoveryLatencyMs = 0L
    private var vendorBridgeMaxRecoveryLatencyMs = 0L
    private var vendorBridgeShellPid = 0
    private var vendorBridgeParentStartTimeTicks = ""
    private var vendorBridgeShellStartTimeTicks = ""
    private var vendorBridgeHeartbeatPath = ""
    private var vendorBridgeOwnerPath = ""
    private var vendorBridgeHeartbeatFileAgeMs = -1L
    private var vendorBridgeHeartbeatFileValid = false
    private var vendorBridgeOwnerLeaseValid = false
    private var vendorBridgeGroupRecoveryCount = 0L
    private var vendorBridgeReleaseRetryCount = 0L
    private var vendorBridgeAdoptObservedCount = 0L
    private var vendorBridgeSuppressedInternalUnfreezeCount = 0L
    private var vendorBridgeLastCommandDetail = ""
    private var vendorBridgeLockEscalationCount = 0L
    private var vendorBridgeDefenseEpisodeCount = 0L
    private var vendorBridgeDefensePulseCount = 0L
    private var vendorBridgeDefenseStableCount = 0L
    private var vendorBridgeDefenseRefreezeCount = 0L
    private var vendorBridgeDefenseEscalationCount = 0L
    private var vendorBridgeDefensePidChangeCount = 0L
    private var vendorBridgeDefenseExpiredCount = 0L
    private var vendorBridgeDefenseLastSequence = 0L
    private var vendorBridgeDefenseLastPhase = "never"
    private var vendorBridgeDefenseLastElapsedMs = 0L
    private var vendorBridgeDefenseLastStableMs = 0L
    private var vendorBridgeDefenseLastAttempts = 0
    private var vendorBridgeDefenseAccountingSequence = 0L
    private var vendorBridgeDefenseLastCommandCount = 0
    private var vendorBridgeDefenseCommandCount = 0L
    private var vendorBridgeDefenseOwnershipUntilElapsed = 0L
    private var vendorBridgeDefenseOwnershipSequence = 0L
    private var vendorBridgeDefenseOwnershipPhase = "never"
    private var vendorBridgeDefenseRecoverySuppressionCount = 0L
    private val vendorBridgeDefensePulsedSequences = LinkedHashSet<Long>()
    private var legacyGuardianDetectedCount = 0L
    private var legacyGuardianStoppedCount = 0L
    private var legacyGuardianLastResult = "never"
    private var lastLegacyGuardianAuditElapsed = 0L
    private val lastDelegatedUnfreezeLogByPackage = linkedMapOf<String, Long>()
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
    private var supportsDirectActivityUnfreeze = false
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
    private val gmsVivoFastFreezerEvents = ArrayDeque<Long>()
    private var gmsTransportMissingEpisodePids: Set<Int> = emptySet()
    private var gmsRecentFreezerEvidenceLatchCount = 0L
    private var gmsRecentFreezerEvidenceLatchedMissingEpisodeElapsed = 0L
    private val gmsRecoveryHistory = ArrayDeque<Long>()
    private val gmsForceStopHistory = ArrayDeque<Long>()
    private var gmsRecoveryInProgress = false
    private var gmsManualRecoveryQueued = false
    private var gmsManualRecoveryRequestId = 0L
    private var gmsManualRecoveryState = "idle"
    private var lastGmsRecoveryElapsed = 0L
    private var lastGmsRecoveryCompletedElapsed = 0L
    private var gmsConsecutiveCampaignFailures = 0
    private var gmsCooldownBypassMissingEpisodeElapsed = 0L
    private var gmsCooldownBypassCount = 0L
    private var gmsPostSuccessProtectionUntilElapsed = 0L
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
    private var gmsFastThawAttemptCount = 0L
    private var gmsFastThawSuccessCount = 0L
    private var gmsFastThawCoalescedCount = 0L
    private var gmsFastThawRetryCount = 0L
    private var gmsFastThawFinalVerifiedCount = 0L
    private var gmsFastThawLastLatencyMs = 0L
    private var gmsFastThawMaxLatencyMs = 0L
    private var gmsFastThawDeadlineOverrunCount = 0L
    private var gmsFastThawMaxDeadlineOverrunMs = 0L
    private var gmsFastThawLastCompletedElapsed = 0L
    private var gmsFastThawAwaitingReconnectSinceElapsed = 0L
    private var gmsFastThawPostReconnectCount = 0L
    private var gmsFastThawLastPostReconnectLatencyMs = 0L
    private var gmsFastThawMaxPostReconnectLatencyMs = 0L
    private var gmsTransportCollapseCount = 0L
    private var gmsTransportLongestContinuousMs = 0L
    private var gmsImportanceFenceProbeCount = 0L
    private var gmsImportanceFenceStatusFailureCount = 0L
    private var gmsImportanceFenceActive = false
    private var gmsImportanceFenceAnyConnected = false
    private var gmsImportanceFenceBothConnected = false
    private var gmsImportanceFenceGeneration = 0L
    private var gmsImportanceFenceMainState = "never"
    private var gmsImportanceFenceMainAction = ""
    private var gmsImportanceFenceMainComponent = ""
    private var gmsImportanceFencePersistentState = "never"
    private var gmsImportanceFencePersistentAction = ""
    private var gmsImportanceFencePersistentComponent = ""
    private var gmsImportanceFenceLastProbeElapsed = 0L
    private var gmsImportanceFenceFreezeWhileAnyConnectedCount = 0L
    private var gmsImportanceFenceFreezeWhileBothConnectedCount = 0L
    private var gmsImportanceFenceUid = -1
    private var gmsImportanceFenceUidState = "unobserved"
    private var gmsImportanceFenceLastRawStatus = ""
    private val gmsImportanceFenceBaselineOomAdj = linkedMapOf<String, Int>()
    private val gmsImportanceFenceLastOomAdj = linkedMapOf<String, Int>()
    private val gmsImportanceFenceLowestOomAdj = linkedMapOf<String, Int>()
    private val gmsImportanceFenceHighestOomAdj = linkedMapOf<String, Int>()
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
        startVendorBridgeLocked()
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
        stopVendorBridgeLocked()
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
            stopVendorBridgeLocked()
            stopEventWatcherLocked()
            executor.shutdownNow()
            longOperationExecutor.shutdownNow()
            gmsFastThawExecutor.shutdownNow()
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

    private fun startEventWatcherLocked(forceLegacy: Boolean = false) {
        val targetEnabled = isGmsFastLaneTargetedLocked()
        val vendorBridgeOwnsGms = vendorBridgeOwnsGmsLocked()
        val sticky = config.stickyUnfreeze && supportsStickyUnfreeze
        // When the cgroup bridge owns GMS, the r256 logcat sidecar must remain
        // observational only; otherwise two unfreeze loops can split the
        // bounded adopt-release transaction. A Xiaomi bridge that only owns
        // WhatsApp/Signal does not disable the independent GMS fast lane.
        val wantsFastLane = !forceLegacy && targetEnabled && !vendorBridgeOwnsGms
        val currentProcessAlive = eventWatcherProcess?.isAlive == true
        val currentThreadAlive = eventWatcherThread?.isAlive == true
        val currentCompatible = currentProcessAlive && currentThreadAlive && when {
            wantsFastLane -> eventWatcherMode == "shell_fast_lane" &&
                eventFastLaneStickyConfigured == sticky &&
                eventFastLaneTargetEnabled
            else -> eventWatcherMode == "legacy_logcat"
        }
        if (currentCompatible) {
            eventWatcherAlive = true
            return
        }

        if (currentProcessAlive || currentThreadAlive) {
            eventLocked(
                "event_watcher_reconfigured",
                "oldMode=$eventWatcherMode wantsFastLane=$wantsFastLane " +
                    "sticky=$sticky targetEnabled=$targetEnabled"
            )
        }
        stopEventWatcherLocked()
        eventFastLaneTargetEnabled = targetEnabled
        if (!wantsFastLane) {
            val reason = when {
                forceLegacy -> "fast_lane_runtime_fallback"
                vendorBridgeOwnsGms -> "vendor_cgroup_bridge"
                else -> "gms_target_disabled"
            }
            startLegacyEventWatcherLocked(reason)
            return
        }

        eventFastLaneReady = false
        eventFastLaneBackend = "starting"
        eventFastLaneTimeoutSupported = false
        eventFastLaneStickyConfigured = sticky
        val startResult = runCatching {
            ProcessBuilder("/system/bin/sh")
                .redirectErrorStream(true)
                .start()
                .also { process ->
                    process.outputStream.bufferedWriter().use { writer ->
                        writer.write(
                            GmsFreezerFastLaneScript.build(
                                parentPid = Process.myPid(),
                                stickyUnfreeze = sticky
                            )
                        )
                        writer.flush()
                    }
                }
        }
        val process = startResult.getOrNull()
        if (process == null) {
            val error = startResult.exceptionOrNull()
            eventFastLaneFailureCount += 1
            errorCount += 1
            eventLocked(
                "gms_fast_lane_start_failed",
                "${error?.javaClass?.simpleName}:${error?.message.orEmpty()}"
            )
            startLegacyEventWatcherLocked("fast_lane_start_failed")
            return
        }

        eventWatcherProcess = process
        eventWatcherAlive = true
        eventWatcherMode = "shell_fast_lane"
        eventFastLaneStartCount += 1
        eventWatcherThread = thread(
            name = "luonnotar-gms-freezer-fast-lane",
            isDaemon = true
        ) {
            var failure: Throwable? = null
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> handleFastLaneOutput(process, line) }
                }
            } catch (error: Throwable) {
                failure = error
            }
            handleEventWatcherEnded(process, "shell_fast_lane", failure)
        }
        eventFastLaneReadyTimeoutFuture = runCatching {
            executor.schedule(
                {
                    synchronized(lock) {
                        eventFastLaneReadyTimeoutFuture = null
                        if (!running || eventWatcherProcess !== process || eventFastLaneReady) {
                            return@synchronized
                        }
                        eventFastLaneFailureCount += 1
                        errorCount += 1
                        eventLocked(
                            "gms_fast_lane_ready_timeout",
                            "processAlive=${process.isAlive} sticky=$sticky"
                        )
                        stopEventWatcherLocked()
                        if (running) startLegacyEventWatcherLocked("fast_lane_ready_timeout")
                    }
                },
                FAST_LANE_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        }.getOrElse { error ->
            errorCount += 1
            eventLocked(
                "gms_fast_lane_ready_timeout_schedule_failed",
                "${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            null
        }
        eventLocked(
            "gms_fast_lane_started",
            "shell owns logcat; sticky=$sticky parentPid=${Process.myPid()}"
        )
    }

    private fun startLegacyEventWatcherLocked(reason: String) {
        eventFastLaneReadyTimeoutFuture?.cancel(false)
        eventFastLaneReadyTimeoutFuture = null
        eventFastLaneReady = false
        eventFastLaneBackend = "legacy"
        eventFastLaneTimeoutSupported = false
        eventFastLaneStickyConfigured = false
        eventFastLaneTargetEnabled = isGmsFastLaneTargetedLocked()
        val startResult = runCatching {
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
        }
        val process = startResult.getOrNull()
        if (process == null) {
            val error = startResult.exceptionOrNull()
            eventWatcherAlive = false
            eventWatcherMode = "failed"
            errorCount += 1
            eventLocked(
                "event_watcher_start_failed",
                "reason=$reason ${error?.javaClass?.simpleName}:${error?.message.orEmpty()}"
            )
            scheduleEventWatcherRestartLocked(forceLegacy = true)
            return
        }

        eventWatcherProcess = process
        eventWatcherAlive = true
        eventWatcherMode = "legacy_logcat"
        eventWatcherThread = thread(
            name = "luonnotar-freezer-event-watcher-legacy",
            isDaemon = true
        ) {
            var failure: Throwable? = null
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> handleEventWatcherRawLine(process, line) }
                }
            } catch (error: Throwable) {
                failure = error
            }
            handleEventWatcherEnded(process, "legacy_logcat", failure)
        }
        eventLocked(
            "event_watcher_started",
            "mode=legacy_logcat reason=$reason events+system+main"
        )
    }

    private fun handleFastLaneOutput(process: java.lang.Process, line: String) {
        if (!synchronized(lock) { eventWatcherProcess === process }) return
        when (val record = GmsFreezerFastLaneProtocol.parse(line)) {
            is GmsFreezerFastLaneRecord.Ready -> synchronized(lock) {
                if (eventWatcherProcess !== process) return@synchronized
                eventFastLaneReadyTimeoutFuture?.cancel(false)
                eventFastLaneReadyTimeoutFuture = null
                eventFastLaneReady = true
                eventFastLaneBackend = record.backend
                eventFastLaneTimeoutSupported = record.timeout
                eventFastLaneStickyConfigured = record.sticky
                eventLocked(
                    "gms_fast_lane_ready",
                    "backend=${record.backend} sticky=${record.sticky} timeout=${record.timeout}"
                )
                persistStatusLocked(force = true)
            }
            is GmsFreezerFastLaneRecord.Signal -> {
                val receivedElapsed = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    if (eventWatcherProcess !== process) return@synchronized
                    eventFastLaneSignalCount += 1
                    eventFastLaneLastSignalElapsed = receivedElapsed
                    eventFastLaneSignalElapsedBySequence[record.sequence] = receivedElapsed
                    trimFastLaneSequenceHistoryLocked()
                    eventLocked(
                        "gms_fast_lane_signal",
                        "seq=${record.sequence} target=${record.target}"
                    )
                }
            }
            is GmsFreezerFastLaneRecord.FirstThaw -> {
                val completedElapsed = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    if (eventWatcherProcess !== process) return@synchronized
                    eventFastLaneBackend = record.backend
                    val receivedElapsed =
                        eventFastLaneSignalElapsedBySequence[record.sequence] ?: completedElapsed
                    val kotlinObservedLatency =
                        (completedElapsed - receivedElapsed).coerceAtLeast(0L)
                    val shellCommandDuration =
                        (record.durationCentiseconds * 10L).coerceAtLeast(0L)
                    if (record.skipped) {
                        eventFastLaneImmediateSkippedCount += 1
                    } else {
                        eventFastLaneLastImmediateLatencyMs = shellCommandDuration
                        eventFastLaneMaxImmediateLatencyMs =
                            maxOf(eventFastLaneMaxImmediateLatencyMs, shellCommandDuration)
                        eventFastLaneImmediateAttemptCount += 1
                        gmsFastThawAttemptCount += 1
                        actionCount += 1
                        if (record.exitCode == 0) {
                            eventFastLaneImmediateAcceptedCount += 1
                        } else {
                            commandFailureCount += 1
                        }
                        gmsFastThawLastLatencyMs = shellCommandDuration
                        gmsFastThawMaxLatencyMs =
                            maxOf(gmsFastThawMaxLatencyMs, shellCommandDuration)
                    }
                    eventLocked(
                        "gms_fast_lane_first_thaw",
                        "seq=${record.sequence} target=${record.target} backend=${record.backend} " +
                            "rc=${record.exitCode} skipped=${record.skipped} " +
                            "shellDurationMs=$shellCommandDuration " +
                            "kotlinObservedLatencyMs=$kotlinObservedLatency"
                    )
                }
            }
            is GmsFreezerFastLaneRecord.ProbeResult -> {
                var schedulePostRecovery = false
                synchronized(lock) {
                    if (eventWatcherProcess !== process) return@synchronized
                    eventFastLaneLastState = record.state
                    val recoveryReady = GmsFreezerFastLanePolicy.isRecoveryReady(
                        state = record.state,
                        acceptedCount = record.acceptedCount
                    )
                    if (recoveryReady) {
                        schedulePostRecovery = markFastLaneRecoveryLocked(
                            sequence = record.sequence,
                            state = record.state
                        )
                    }
                    eventLocked(
                        "gms_fast_lane_probe",
                        "seq=${record.sequence} state=${record.state} commands=${record.commandCount} " +
                            "accepted=${record.acceptedCount} frozenPolls=${record.frozenPollCount} " +
                            "verified=${record.verifiedThawCount} blind=${record.blindReassertCount}"
                    )
                    persistStatusLocked(force = true)
                }
                if (schedulePostRecovery) {
                    schedulePostFastLaneRecovery(record.sequence, record.state)
                }
            }
            is GmsFreezerFastLaneRecord.ShieldResult -> {
                var fallbackSignalElapsed = 0L
                var schedulePostRecovery = false
                synchronized(lock) {
                    if (eventWatcherProcess !== process) return@synchronized
                    eventFastLaneShieldCompletionCount += 1
                    eventFastLaneShieldCommandCount += record.commandCount.toLong()
                    eventFastLaneShieldAcceptedCount += record.acceptedCount.toLong()
                    eventFastLaneFrozenPollCount += record.frozenPollCount.toLong()
                    eventFastLaneVerifiedThawCount += record.verifiedThawCount.toLong()
                    eventFastLaneBlindReassertCount += record.blindReassertCount.toLong()
                    if (record.exhausted) eventFastLaneExhaustedCount += 1
                    eventFastLaneLastEpisode = record.episode
                    eventFastLaneLastState = record.state
                    val sequenceSignalElapsed =
                        eventFastLaneSignalElapsedBySequence.remove(record.sequence)
                            ?: eventFastLaneLastSignalElapsed.takeIf { it > 0L }
                            ?: SystemClock.elapsedRealtime()
                    actionCount += record.commandCount.toLong()
                    commandFailureCount +=
                        (record.commandCount - record.acceptedCount).coerceAtLeast(0).toLong()
                    val recoveryReady = GmsFreezerFastLanePolicy.isRecoveryReady(
                        state = record.state,
                        acceptedCount = record.acceptedCount
                    )
                    if (recoveryReady) {
                        schedulePostRecovery = markFastLaneRecoveryLocked(
                            sequence = record.sequence,
                            state = record.state
                        )
                    }
                    val requiresFallback =
                        GmsFreezerFastLanePolicy.requiresKotlinFallback(
                            state = record.state,
                            acceptedCount = record.acceptedCount,
                            exhausted = record.exhausted
                        )
                    if (requiresFallback) fallbackSignalElapsed = sequenceSignalElapsed
                    eventLocked(
                        "gms_fast_lane_shield_finished",
                        "seq=${record.sequence} episode=${record.episode} state=${record.state} " +
                            "commands=${record.commandCount} accepted=${record.acceptedCount} " +
                            "frozenPolls=${record.frozenPollCount} verified=${record.verifiedThawCount} " +
                            "blind=${record.blindReassertCount} durationMs=${record.durationCentiseconds * 10L} " +
                            "exhausted=${record.exhausted} fallback=$requiresFallback"
                    )
                    persistStatusLocked(force = true)
                }
                if (schedulePostRecovery) {
                    schedulePostFastLaneRecovery(record.sequence, record.state)
                }
                if (fallbackSignalElapsed > 0L) {
                    scheduleGmsFastThaw(fallbackSignalElapsed)
                }
            }
            is GmsFreezerFastLaneRecord.RawLog -> handleEventWatcherRawLine(process, record.line)
            is GmsFreezerFastLaneRecord.Diagnostic -> synchronized(lock) {
                if (eventWatcherProcess !== process) return@synchronized
                eventLocked(
                    "gms_fast_lane_diagnostic",
                    "type=${record.type} detail=${record.detail}"
                )
            }
            null -> synchronized(lock) {
                if (eventWatcherProcess !== process) return@synchronized
                eventLocked("gms_fast_lane_unparsed_output", line.take(MAX_EVENT_DETAIL))
            }
        }
    }

    private fun markFastLaneRecoveryLocked(sequence: Long, state: String): Boolean {
        val firstPostRecovery = eventFastLaneProbeHandledSequences.add(sequence)
        if (state == "thawed" && eventFastLaneVerifiedSequences.add(sequence)) {
            gmsFastThawSuccessCount += 1
            gmsFastThawFinalVerifiedCount += 1
            effectiveThawCount += 1
            gmsFastThawLastCompletedElapsed = SystemClock.elapsedRealtime()
            if (!lastGmsTransportProbe.healthy &&
                gmsFastThawAwaitingReconnectSinceElapsed <= 0L
            ) {
                gmsFastThawAwaitingReconnectSinceElapsed =
                    gmsFastThawLastCompletedElapsed
            }
        }
        trimFastLaneSequenceHistoryLocked()
        return firstPostRecovery
    }

    private fun trimFastLaneSequenceHistoryLocked() {
        while (eventFastLaneSignalElapsedBySequence.size > MAX_FAST_LANE_SEQUENCE_HISTORY) {
            eventFastLaneSignalElapsedBySequence.remove(
                eventFastLaneSignalElapsedBySequence.keys.first()
            )
        }
        while (eventFastLaneProbeHandledSequences.size > MAX_FAST_LANE_SEQUENCE_HISTORY) {
            eventFastLaneProbeHandledSequences.remove(
                eventFastLaneProbeHandledSequences.first()
            )
        }
        while (eventFastLaneVerifiedSequences.size > MAX_FAST_LANE_SEQUENCE_HISTORY) {
            eventFastLaneVerifiedSequences.remove(eventFastLaneVerifiedSequences.first())
        }
    }

    private fun schedulePostFastLaneRecovery(sequence: Long, state: String) {
        var reservedAt = 0L
        val accepted = synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (!running ||
                (eventFastLaneLastPostRecoveryElapsed > 0L &&
                    now >= eventFastLaneLastPostRecoveryElapsed &&
                    now - eventFastLaneLastPostRecoveryElapsed < FAST_LANE_POST_RECOVERY_COOLDOWN_MS)
            ) {
                false
            } else {
                reservedAt = now
                eventFastLaneLastPostRecoveryElapsed = now
                eventFastLanePostRecoveryCount += 1
                true
            }
        }
        if (!accepted) return
        runCatching {
            gmsFastThawExecutor.execute {
                if (!synchronized(lock) { running }) return@execute
                requestPostThawAnchorQuery()
                if (shouldKickMcsAfterThaw()) {
                    runGmsMcsKickWindow(trigger = "shell_fast_lane:$state:seq=$sequence")
                }
            }
        }.onFailure { error ->
            synchronized(lock) {
                if (eventFastLaneLastPostRecoveryElapsed == reservedAt) {
                    eventFastLaneLastPostRecoveryElapsed = 0L
                    eventFastLanePostRecoveryCount =
                        (eventFastLanePostRecoveryCount - 1L).coerceAtLeast(0L)
                }
                errorCount += 1
                eventLocked(
                    "gms_fast_lane_post_recovery_schedule_failed",
                    "seq=$sequence ${error.javaClass.simpleName}:${error.message.orEmpty()}"
                )
            }
        }
    }

    private fun scheduleVendorDefenseMcsPulse(
        sequence: Long,
        sourceProcess: java.lang.Process
    ) {
        runCatching {
            gmsFastThawExecutor.execute {
                val stillCurrent = synchronized(lock) {
                    running && vendorBridgeReady && vendorBridgeProcess === sourceProcess
                }
                if (!stillCurrent) return@execute
                requestPostThawAnchorQuery()
                if (shouldKickMcsAfterThaw()) {
                    val plan = GmsVendorDefensePolicy.reconnectPlan()
                    runGmsMcsKickWindow(
                        trigger = "vendor_defense_pulse:seq=$sequence",
                        maxRounds = plan.maxRounds,
                        allowEmergencyEscalation = plan.allowEmergencyEscalation
                    )
                } else {
                    synchronized(lock) {
                        eventLocked(
                            "gms_mcs_defense_pulse_skipped",
                            "seq=$sequence transport_already_healthy_or_not_required"
                        )
                    }
                }
            }
        }.onFailure { error ->
            synchronized(lock) {
                if (vendorBridgeProcess === sourceProcess) {
                    vendorBridgeDefensePulsedSequences.remove(sequence)
                }
                errorCount += 1
                eventLocked(
                    "gms_mcs_defense_pulse_schedule_failed",
                    "seq=$sequence ${error.javaClass.simpleName}:${error.message.orEmpty()}"
                )
            }
        }
    }

    private fun handleEventWatcherRawLine(process: java.lang.Process, line: String) {
        if (!synchronized(lock) { eventWatcherProcess === process }) return
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
        } ?: return
        var fastThawRequested = false
        var delegatedToFastLane = false
        var delegatedToVendorBridge = false
        val accepted = synchronized(lock) {
            recordVendorSignalLocked(signal, now)
            val gmsFreezeSignal = signal.packageName == GMS_PACKAGE
            val bridgeOwnedFreezeSignal =
                vendorBridgeOwnsPackageLocked(signal.packageName) &&
                    signal.kind in setOf(
                        VendorFreezeSignalKind.AOSP_APP_FROZEN,
                        VendorFreezeSignalKind.XIAOMI_GREEZER_DENIAL,
                        VendorFreezeSignalKind.UID_FROZEN_WAKELOCK
                    )
            // Ownership is policy-based. During bridge startup/restart we must
            // still suppress every fallback unfreeze path; otherwise an event
            // arriving inside that transition recreates a second command owner.
            delegatedToVendorBridge =
                running && bridgeOwnedFreezeSignal
            delegatedToFastLane =
                running &&
                    !delegatedToVendorBridge &&
                    eventFastLaneReady &&
                    gmsFreezeSignal &&
                    signal.kind == VendorFreezeSignalKind.AOSP_APP_FROZEN
            fastThawRequested =
                running &&
                    !delegatedToVendorBridge &&
                    !eventFastLaneReady &&
                    gmsFreezeSignal &&
                    signal.kind == VendorFreezeSignalKind.AOSP_APP_FROZEN
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
        if (fastThawRequested) {
            scheduleGmsFastThaw(signalElapsed = now)
        }
        if (accepted && (delegatedToVendorBridge || delegatedToFastLane)) {
            synchronized(lock) {
                eventLocked(
                    if (delegatedToVendorBridge) {
                        "vendor_recovery_delegated_to_cgroup_bridge"
                    } else {
                        "vendor_recovery_delegated_to_fast_lane"
                    },
                    "${signal.packageName} kind=${signal.kind}"
                )
            }
        } else if (accepted) {
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

    private fun startVendorBridgeLocked() {
        val targets = vendorBridgeTargetsLocked()
        if (targets.isEmpty()) {
            stopVendorBridgeLocked()
            vendorBridgeConfiguredTargets = emptySet()
            vendorBridgeLastState = "disabled"
            return
        }
        val previousTargets = vendorBridgeConfiguredTargets
        if (GMS_PACKAGE in targets && gmsFastThawWorkerActive.get()) {
            gmsFastThawPendingSignalElapsed.set(0L)
            vendorBridgeAlive = false
            vendorBridgeReady = false
            vendorBridgeLastState = "waiting_for_internal_unfreeze_owner"
            eventLocked(
                "vendor_bridge_start_deferred",
                "reason=gms_fast_thaw_worker_active targets=${targets.sorted()}"
            )
            scheduleVendorBridgeRestartLocked()
            return
        }
        gmsFastThawPendingSignalElapsed.set(0L)
        val sticky = config.stickyUnfreeze && supportsStickyUnfreeze
        val processAlive = vendorBridgeProcess?.isAlive == true
        val threadAlive = vendorBridgeThread?.isAlive == true
        if (
            processAlive &&
            threadAlive &&
            vendorBridgeStickyConfigured == sticky &&
            vendorBridgeConfiguredTargets == targets
        ) {
            vendorBridgeAlive = true
            return
        }

        if (processAlive || threadAlive) {
            eventLocked(
                "vendor_bridge_reconfigured",
                "sticky=$sticky processAlive=$processAlive threadAlive=$threadAlive " +
                    "oldTargets=${previousTargets.sorted()} newTargets=${targets.sorted()}"
            )
        }
        stopVendorBridgeLocked()
        quarantineLegacyUnfreezeShellsLocked()
        vendorBridgeConfiguredTargets = targets
        vendorBridgeStickyConfigured = sticky
        vendorBridgeMainPid = 0
        vendorBridgeMainState = if (GMS_PACKAGE in targets) "starting" else "disabled"
        vendorBridgePersistentPid = 0
        vendorBridgePersistentState = if (GMS_PACKAGE in targets) "starting" else "disabled"
        vendorBridgeWhatsappPid = 0
        vendorBridgeWhatsappState = if (WHATSAPP_PACKAGE in targets) "starting" else "disabled"
        vendorBridgeSignalPid = 0
        vendorBridgeSignalState = if (SIGNAL_PACKAGE in targets) "starting" else "disabled"
        vendorBridgeLastState = "starting"

        val startResult = runCatching {
            ProcessBuilder("/system/bin/sh")
                .redirectErrorStream(true)
                .start()
                .also { process ->
                    process.outputStream.bufferedWriter().use { writer ->
                        writer.write(
                            GmsVendorFreezeBridgeScript.build(
                                parentPid = Process.myPid(),
                                stickyUnfreeze = sticky,
                                monitorGms = GMS_PACKAGE in targets,
                                monitorWhatsApp = WHATSAPP_PACKAGE in targets,
                                monitorSignal = SIGNAL_PACKAGE in targets
                            )
                        )
                        writer.flush()
                    }
                }
        }
        val process = startResult.getOrNull()
        if (process == null) {
            val error = startResult.exceptionOrNull()
            vendorBridgeFailureCount += 1
            errorCount += 1
            vendorBridgeAlive = false
            vendorBridgeLastState = "start_failed"
            eventLocked(
                "vendor_bridge_start_failed",
                "${error?.javaClass?.simpleName}:${error?.message.orEmpty()}"
            )
            scheduleVendorBridgeRestartLocked()
            return
        }

        vendorBridgeProcess = process
        vendorBridgeAlive = true
        vendorBridgeReady = false
        vendorBridgeStartCount += 1
        vendorBridgeThread = thread(
            name = "luonnotar-vendor-freeze-bridge",
            isDaemon = true
        ) {
            var failure: Throwable? = null
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> handleVendorBridgeOutput(process, line) }
                }
            } catch (error: Throwable) {
                failure = error
            }
            handleVendorBridgeEnded(process, failure)
        }
        vendorBridgeReadyTimeoutFuture = runCatching {
            executor.schedule(
                {
                    synchronized(lock) {
                        vendorBridgeReadyTimeoutFuture = null
                        if (!running || vendorBridgeProcess !== process || vendorBridgeReady) {
                            return@synchronized
                        }
                        vendorBridgeFailureCount += 1
                        errorCount += 1
                        vendorBridgeLastState = "ready_timeout"
                        eventLocked(
                            "vendor_bridge_ready_timeout",
                            "processAlive=${process.isAlive} sticky=$sticky targets=${targets.sorted()}"
                        )
                        stopVendorBridgeLocked()
                        if (running) scheduleVendorBridgeRestartLocked()
                    }
                },
                VENDOR_BRIDGE_READY_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            )
        }.getOrElse { error ->
            errorCount += 1
            eventLocked(
                "vendor_bridge_ready_timeout_schedule_failed",
                "${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            null
        }
        eventLocked(
            "vendor_bridge_started",
            "strategy=${GmsVendorDefensePolicy.STRATEGY} sticky=$sticky parentPid=${Process.myPid()} " +
                "targets=${targets.sorted()}"
        )
    }

    private fun handleVendorBridgeOutput(process: java.lang.Process, line: String) {
        if (!synchronized(lock) { vendorBridgeProcess === process }) return
        when (val record = GmsVendorFreezeBridgeProtocol.parse(line)) {
            is GmsVendorFreezeBridgeRecord.Ready -> synchronized(lock) {
                if (vendorBridgeProcess !== process) return@synchronized
                val expectedHeartbeatPath = if (record.shellPid > 1) {
                    GmsVendorFreezeBridgeScript.heartbeatPath(
                        parentPid = Process.myPid(),
                        shellPid = record.shellPid
                    )
                } else {
                    ""
                }
                val expectedParentStart = readProcStartTimeTicks(Process.myPid()).orEmpty()
                val expectedShellStart = readProcStartTimeTicks(record.shellPid).orEmpty()
                val readyIdentityValid =
                    record.shellPid > 1 &&
                        record.strategy == GmsVendorDefensePolicy.STRATEGY &&
                        record.parentStartTimeTicks.isNotBlank() &&
                        record.shellStartTimeTicks.isNotBlank() &&
                        record.parentStartTimeTicks == expectedParentStart &&
                        record.shellStartTimeTicks == expectedShellStart &&
                        record.heartbeatPath == expectedHeartbeatPath &&
                        record.ownerPath == GmsVendorFreezeBridgeScript.COMMAND_OWNER_PATH
                if (!readyIdentityValid) {
                    vendorBridgeFailureCount += 1
                    errorCount += 1
                    vendorBridgeLastState = "ready_identity_invalid"
                    eventLocked(
                        "vendor_bridge_ready_rejected",
                        "shellPid=${record.shellPid} strategy=${record.strategy} " +
                            "parentStart=${record.parentStartTimeTicks} " +
                            "shellStart=${record.shellStartTimeTicks} expectedParent=$expectedParentStart " +
                            "expectedShell=$expectedShellStart heartbeat=${record.heartbeatPath.take(180)} " +
                            "owner=${record.ownerPath.take(180)}"
                    )
                    stopVendorBridgeLocked()
                    if (running) scheduleVendorBridgeRestartLocked()
                    return@synchronized
                }
                vendorBridgeReadyTimeoutFuture?.cancel(false)
                vendorBridgeReadyTimeoutFuture = null
                vendorBridgeReady = true
                vendorBridgeAlive = true
                vendorBridgeTimeoutSupported = record.timeout
                vendorBridgeStickyConfigured = record.sticky
                vendorBridgeShellPid = record.shellPid
                vendorBridgeParentStartTimeTicks = record.parentStartTimeTicks
                vendorBridgeShellStartTimeTicks = record.shellStartTimeTicks
                vendorBridgeHeartbeatPath = record.heartbeatPath
                vendorBridgeOwnerPath = record.ownerPath
                vendorBridgeLastState = "ready"
                vendorBridgeLastHeartbeatElapsed = SystemClock.elapsedRealtime()
                updateVendorBridgeFileHeartbeatLocked(vendorBridgeLastHeartbeatElapsed)
                eventLocked(
                    "vendor_bridge_ready",
                    "strategy=${record.strategy} sticky=${record.sticky} " +
                        "timeout=${record.timeout} shellPid=${record.shellPid} " +
                            "parentStart=${record.parentStartTimeTicks} shellStart=${record.shellStartTimeTicks} " +
                        "heartbeat=${record.heartbeatPath} targets=${vendorBridgeConfiguredTargets.sorted()}"
                )
                persistStatusLocked(force = true)
            }
            is GmsVendorFreezeBridgeRecord.Heartbeat -> synchronized(lock) {
                if (vendorBridgeProcess !== process) return@synchronized
                vendorBridgeHeartbeatCount += 1
                vendorBridgeLastHeartbeatElapsed = SystemClock.elapsedRealtime()
                vendorBridgeMainPid = record.mainPid
                vendorBridgeMainState = record.mainState
                vendorBridgePersistentPid = record.persistentPid
                vendorBridgePersistentState = record.persistentState
                vendorBridgeWhatsappPid = record.whatsappPid
                vendorBridgeWhatsappState = record.whatsappState
                vendorBridgeSignalPid = record.signalPid
                vendorBridgeSignalState = record.signalState
                val activeStates = listOf(
                    record.mainState,
                    record.persistentState,
                    record.whatsappState,
                    record.signalState
                ).filterNot { it == "disabled" }
                vendorBridgeLastState = when {
                    activeStates.any { it == "frozen" } -> "frozen"
                    activeStates.any { it == "thawed" } -> "thawed"
                    activeStates.isEmpty() -> "disabled"
                    else -> activeStates.joinToString("/")
                }
                // Keep the socket snapshot fresh every heartbeat, while the
                // on-disk diagnostic file remains under its 30-second throttle.
                persistStatusLocked(force = false)
            }
            is GmsVendorFreezeBridgeRecord.Frozen -> synchronized(lock) {
                if (vendorBridgeProcess !== process) return@synchronized
                vendorBridgeFrozenCount += 1
                vendorBridgeLastTarget = record.target
                vendorBridgeLastPid = record.pid
                vendorBridgeLastState = "frozen"
                if (
                    record.target == GMS_PACKAGE ||
                    record.target == "$GMS_PACKAGE.persistent"
                ) {
                    vendorBridgeDefenseOwnershipSequence = record.sequence
                    vendorBridgeDefenseOwnershipPhase = "pending"
                    vendorBridgeDefenseOwnershipUntilElapsed =
                        SystemClock.elapsedRealtime() + VENDOR_DEFENSE_PENDING_OWNER_TTL_MS
                }
                eventLocked(
                    "vendor_cgroup_frozen",
                    "seq=${record.sequence} target=${record.target} pid=${record.pid} " +
                        "path=${record.cgroupPath} consecutive=${record.consecutive}"
                )
            }
            is GmsVendorFreezeBridgeRecord.Defense -> {
                var schedulePulse = false
                synchronized(lock) {
                    if (vendorBridgeProcess !== process) return@synchronized
                    val nowElapsed = SystemClock.elapsedRealtime()
                    val elapsedMs = (record.elapsedCentiseconds * 10L).coerceAtLeast(0L)
                    val stableMs = (record.stableCentiseconds * 10L).coerceAtLeast(0L)
                    if (record.sequence != vendorBridgeDefenseAccountingSequence) {
                        vendorBridgeDefenseAccountingSequence = record.sequence
                        vendorBridgeDefenseLastCommandCount = 0
                    }
                    val commandDelta =
                        (record.commandCount - vendorBridgeDefenseLastCommandCount).coerceAtLeast(0)
                    vendorBridgeDefenseLastCommandCount = record.commandCount
                    vendorBridgeDefenseCommandCount += commandDelta.toLong()
                    actionCount += commandDelta.toLong()
                    vendorBridgeDefenseLastSequence = record.sequence
                    vendorBridgeDefenseLastPhase = record.phase
                    vendorBridgeDefenseLastElapsedMs = elapsedMs
                    vendorBridgeDefenseLastStableMs = stableMs
                    vendorBridgeDefenseLastAttempts = record.attempts
                    vendorBridgeMainPid = record.mainPid
                    vendorBridgePersistentPid = record.persistentPid
                    vendorBridgeLastState = "defense_${record.phase}"
                    when (record.phase) {
                        "started" -> vendorBridgeDefenseEpisodeCount += 1
                        "pulse_ready" -> {
                            vendorBridgeDefensePulseCount += 1
                            schedulePulse = vendorBridgeDefensePulsedSequences.add(record.sequence)
                        }
                        "refrozen" -> vendorBridgeDefenseRefreezeCount += 1
                        "stable_hold" -> Unit
                        "escalating" -> vendorBridgeDefenseEscalationCount += 1
                        "pid_changed" -> vendorBridgeDefensePidChangeCount += 1
                        "stable" -> vendorBridgeDefenseStableCount += 1
                        "expired" -> vendorBridgeDefenseExpiredCount += 1
                    }
                    when (record.phase) {
                        "stable", "expired" -> {
                            if (vendorBridgeDefenseOwnershipSequence == record.sequence) {
                                clearVendorDefenseRecoveryOwnershipLocked(record.phase)
                            }
                        }
                        "escalating" -> {
                            // Keep only a sub-second handoff lease. The VendorLock
                            // callback runs after this and is then allowed to start
                            // exactly one bounded recovery campaign.
                            vendorBridgeDefenseOwnershipSequence = record.sequence
                            vendorBridgeDefenseOwnershipPhase = record.phase
                            vendorBridgeDefenseOwnershipUntilElapsed =
                                nowElapsed + VENDOR_DEFENSE_ESCALATION_HANDOFF_MS
                        }
                        "stable_hold" -> {
                            vendorBridgeDefenseOwnershipSequence = record.sequence
                            vendorBridgeDefenseOwnershipPhase = record.phase
                            vendorBridgeDefenseOwnershipUntilElapsed =
                                nowElapsed + GmsVendorDefensePolicy.STABLE_HOLD_MILLISECONDS +
                                    VENDOR_DEFENSE_OWNER_GRACE_MS
                        }
                        else -> {
                            vendorBridgeDefenseOwnershipSequence = record.sequence
                            vendorBridgeDefenseOwnershipPhase = record.phase
                            vendorBridgeDefenseOwnershipUntilElapsed =
                                nowElapsed + VENDOR_DEFENSE_OWNER_ACTIVE_TTL_MS
                        }
                    }
                    while (vendorBridgeDefensePulsedSequences.size > MAX_FAST_LANE_SEQUENCE_HISTORY) {
                        vendorBridgeDefensePulsedSequences.remove(
                            vendorBridgeDefensePulsedSequences.first()
                        )
                    }
                    eventLocked(
                        "vendor_bridge_defense_${record.phase}",
                        "seq=${record.sequence} elapsedMs=$elapsedMs stableMs=$stableMs " +
                            "refreezes=${record.refreezes} attempts=${record.attempts} " +
                            "commands=${record.commandCount} commandDelta=$commandDelta " +
                            "mainPid=${record.mainPid} persistentPid=${record.persistentPid} " +
                            "detail=${record.detail.take(360)}"
                    )
                    persistStatusLocked(force = true)
                }
                if (schedulePulse) {
                    scheduleVendorDefenseMcsPulse(record.sequence, process)
                }
            }
            is GmsVendorFreezeBridgeRecord.Recovery -> {
                var schedulePostRecovery = false
                var scheduleDefenseFallbackPulse = false
                synchronized(lock) {
                    if (vendorBridgeProcess !== process) return@synchronized
                    val isGmsTarget = record.target == GMS_PACKAGE ||
                        record.target == "$GMS_PACKAGE.persistent"
                    val durationMs = (record.durationCentiseconds * 10L).coerceAtLeast(0L)
                    vendorBridgeRecoveryAttemptCount += 1
                    vendorBridgeLastTarget = record.target
                    vendorBridgeLastPid = record.pid
                    vendorBridgeLastMode = record.mode
                    vendorBridgeLastRecoveryLatencyMs = durationMs
                    vendorBridgeMaxRecoveryLatencyMs =
                        maxOf(vendorBridgeMaxRecoveryLatencyMs, durationMs)
                    when {
                        record.mode.startsWith("plain") -> vendorBridgePlainRecoveryCount += 1
                        record.mode.startsWith("adopt_release") -> vendorBridgeAdoptReleaseCount += 1
                        record.mode.startsWith("adopt_unconfirmed") -> {
                            vendorBridgeAdoptUnconfirmedCount += 1
                            vendorBridgeReleaseRetryCount += 1
                        }
                        record.mode.startsWith("framework_release_retry") -> {
                            vendorBridgeFrameworkLedgerRetryCount += 1
                            vendorBridgeReleaseRetryCount += 1
                        }
                        record.mode.startsWith("release_retry") -> vendorBridgeReleaseRetryCount += 1
                    }
                    if (record.group) vendorBridgeGroupRecoveryCount += 1
                    if (record.adoptObserved) vendorBridgeAdoptObservedCount += 1
                    vendorBridgeLastCommandDetail = record.detail.take(MAX_EVENT_DETAIL)
                    if (!record.mode.startsWith("defense_stable")) {
                        actionCount += record.commandCount.coerceAtLeast(0).toLong()
                    }
                    if (record.verified) {
                        vendorBridgeVerifiedRecoveryCount += 1
                        effectiveThawCount += 1
                        vendorBridgeLastState = "thawed"
                        if (isGmsTarget) {
                            gmsFastThawSuccessCount += 1
                            gmsFastThawFinalVerifiedCount += 1
                            gmsFastThawLastCompletedElapsed = SystemClock.elapsedRealtime()
                            if (!lastGmsTransportProbe.healthy &&
                                gmsFastThawAwaitingReconnectSinceElapsed <= 0L
                            ) {
                                gmsFastThawAwaitingReconnectSinceElapsed =
                                    gmsFastThawLastCompletedElapsed
                            }
                            if (record.mode.startsWith("defense_stable")) {
                                scheduleDefenseFallbackPulse =
                                    vendorBridgeDefensePulsedSequences.add(record.sequence)
                            } else {
                                schedulePostRecovery = true
                            }
                        }
                    } else {
                        vendorBridgeFailedRecoveryCount += 1
                        verificationFailureCount += 1
                        vendorBridgeLastState = "frozen"
                    }
                    eventLocked(
                        if (record.verified) {
                            "vendor_bridge_recovery_verified"
                        } else {
                            "vendor_bridge_recovery_failed"
                        },
                        "seq=${record.sequence} target=${record.target} pid=${record.pid} " +
                            "peerPid=${record.peerPid} group=${record.group} mode=${record.mode} " +
                            "plainRc=${record.plainExitCode} freezeRc=${record.freezeExitCode} " +
                            "releaseRc=${record.releaseExitCode} stickyRc=${record.stickyExitCode} " +
                            "adoptObserved=${record.adoptObserved} durationMs=$durationMs " +
                            "consecutive=${record.consecutive} commands=${record.commandCount} " +
                            "detail=${record.detail.take(360)}"
                    )
                    persistStatusLocked(force = true)
                }
                if (schedulePostRecovery) {
                    schedulePostFastLaneRecovery(
                        sequence = record.sequence,
                        state = "vendor_bridge:${record.mode}"
                    )
                } else if (scheduleDefenseFallbackPulse) {
                    scheduleVendorDefenseMcsPulse(record.sequence, process)
                }
            }
            is GmsVendorFreezeBridgeRecord.VendorLock -> {
                val signalElapsed = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    if (vendorBridgeProcess !== process) return@synchronized
                    vendorBridgeLockCount += 1
                    vendorBridgeLastLockElapsed = signalElapsed
                    vendorBridgeLastTarget = record.target
                    vendorBridgeLastPid = record.pid
                    vendorBridgeLastState = "vendor_lock"
                    eventLocked(
                        "vendor_refreeze_lock",
                        "seq=${record.sequence} target=${record.target} pid=${record.pid} " +
                            "failures=${record.failures} cooldownMs=${record.cooldownCentiseconds * 10L}"
                    )
                    persistStatusLocked(force = true)
                }
                // Only GMS owns a destructive transport-rebuild campaign.
                // WhatsApp and Signal remain on bounded adopt-release retries;
                // Signal is intentionally included without a separate kill or
                // force-stop campaign.
                if (
                    record.target == GMS_PACKAGE ||
                    record.target == "$GMS_PACKAGE.persistent"
                ) {
                    scheduleVendorBridgeLockEscalation(signalElapsed, record.sequence)
                }
            }
            is GmsVendorFreezeBridgeRecord.Diagnostic -> synchronized(lock) {
                if (vendorBridgeProcess !== process) return@synchronized
                eventLocked(
                    "vendor_bridge_diagnostic",
                    "type=${record.type} detail=${record.detail}"
                )
            }
            null -> synchronized(lock) {
                if (vendorBridgeProcess !== process) return@synchronized
                eventLocked("vendor_bridge_unparsed_output", line.take(MAX_EVENT_DETAIL))
            }
        }
    }

    private fun handleVendorBridgeEnded(process: java.lang.Process, failure: Throwable?) {
        synchronized(lock) {
            if (vendorBridgeProcess !== process) return
            vendorBridgeAlive = false
            vendorBridgeReady = false
            vendorBridgeProcess = null
            vendorBridgeThread = null
            vendorBridgeReadyTimeoutFuture?.cancel(false)
            vendorBridgeReadyTimeoutFuture = null
            vendorBridgeFailureCount += 1
            vendorBridgeLastState = "ended"
            if (!running) return
            errorCount += 1
            eventLocked(
                "vendor_bridge_failed",
                "${failure?.javaClass?.simpleName ?: "eof"}:${failure?.message.orEmpty()}"
            )
            scheduleVendorBridgeRestartLocked()
        }
    }

    private fun scheduleVendorBridgeRestartLocked() {
        vendorBridgeRestartFuture?.cancel(false)
        vendorBridgeRestartFuture = runCatching {
            executor.schedule(
                {
                    synchronized(lock) {
                        vendorBridgeRestartFuture = null
                        if (running && vendorBridgeProcess?.isAlive != true) {
                            startVendorBridgeLocked()
                        }
                    }
                },
                VENDOR_BRIDGE_RESTART_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
        }.getOrElse { error ->
            errorCount += 1
            eventLocked(
                "vendor_bridge_restart_schedule_failed",
                "${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            null
        }
    }

    private fun stopVendorBridgeLocked() {
        vendorBridgeRestartFuture?.cancel(false)
        vendorBridgeRestartFuture = null
        vendorBridgeReadyTimeoutFuture?.cancel(false)
        vendorBridgeReadyTimeoutFuture = null
        vendorBridgeAlive = false
        vendorBridgeReady = false
        vendorBridgeTimeoutSupported = false
        val process = vendorBridgeProcess
        vendorBridgeProcess = null
        process?.destroy()
        if (process != null) {
            runCatching {
                if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }
        vendorBridgeThread?.interrupt()
        vendorBridgeThread = null
        vendorBridgeShellPid = 0
        vendorBridgeParentStartTimeTicks = ""
        vendorBridgeShellStartTimeTicks = ""
        vendorBridgeHeartbeatFileValid = false
        vendorBridgeOwnerLeaseValid = false
        vendorBridgeHeartbeatFileAgeMs = -1L
        vendorBridgeDefensePulsedSequences.clear()
        vendorBridgeDefenseAccountingSequence = 0L
        vendorBridgeDefenseLastCommandCount = 0
    }

    private fun handleEventWatcherEnded(
        process: java.lang.Process,
        mode: String,
        failure: Throwable?
    ) {
        synchronized(lock) {
            if (eventWatcherProcess !== process) return
            eventWatcherAlive = false
            eventWatcherProcess = null
            eventWatcherThread = null
            eventFastLaneReadyTimeoutFuture?.cancel(false)
            eventFastLaneReadyTimeoutFuture = null
            eventFastLaneReady = false
            eventFastLaneBackend = "ended"
            eventFastLaneTimeoutSupported = false
            if (!running) return
            errorCount += 1
            if (mode == "shell_fast_lane") eventFastLaneFailureCount += 1
            eventLocked(
                "event_watcher_failed",
                "mode=$mode ${failure?.javaClass?.simpleName ?: "eof"}:" +
                    failure?.message.orEmpty()
            )
            scheduleEventWatcherRestartLocked(forceLegacy = mode == "shell_fast_lane")
        }
    }

    private fun scheduleEventWatcherRestartLocked(forceLegacy: Boolean) {
        eventWatcherRestartFuture?.cancel(false)
        eventWatcherRestartFuture = runCatching {
            executor.schedule(
                {
                    synchronized(lock) {
                        eventWatcherRestartFuture = null
                        if (running && eventWatcherProcess?.isAlive != true) {
                            startEventWatcherLocked(forceLegacy = forceLegacy)
                        }
                    }
                },
                EVENT_WATCHER_RESTART_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
        }.getOrElse { error ->
            errorCount += 1
            eventLocked(
                "event_watcher_restart_schedule_failed",
                "${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            null
        }
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
            if (gmsImportanceFenceActive && gmsImportanceFenceAnyConnected) {
                gmsImportanceFenceFreezeWhileAnyConnectedCount += 1
                if (gmsImportanceFenceBothConnected) {
                    gmsImportanceFenceFreezeWhileBothConnectedCount += 1
                }
                eventLocked(
                    "gms_importance_fence_freeze_observed",
                    "anyConnected=$gmsImportanceFenceAnyConnected " +
                        "bothConnected=$gmsImportanceFenceBothConnected " +
                        "mainState=$gmsImportanceFenceMainState " +
                        "persistentState=$gmsImportanceFencePersistentState " +
                        "uidState=$gmsImportanceFenceUidState"
                )
            }
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
                canDirectCgroupThawLocked() &&
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

    private fun scheduleGmsFastThaw(signalElapsed: Long) {
        val delegated = synchronized(lock) {
            if (vendorBridgeOwnsGmsCommandsLocked()) {
                recordVendorBridgeSuppressedUnfreezeLocked(GMS_PACKAGE, "gms_fast_thaw")
                true
            } else {
                false
            }
        }
        if (delegated) return
        if (!gmsFastThawWorkerActive.compareAndSet(false, true)) {
            gmsFastThawPendingSignalElapsed.set(signalElapsed)
            synchronized(lock) {
                gmsFastThawCoalescedCount += 1
                eventLocked(
                    "gms_fast_thaw_coalesced",
                    "signalElapsed=$signalElapsed active=true count=$gmsFastThawCoalescedCount"
                )
            }
            return
        }

        runCatching {
            gmsFastThawExecutor.execute {
                try {
                    val sticky = synchronized(lock) {
                        config.stickyUnfreeze && supportsStickyUnfreeze
                    }
                    val secondarySupported = synchronized(lock) {
                        supportsSecondaryProcessUnfreeze
                    }
                    val backend = synchronized(lock) {
                        if (supportsDirectActivityUnfreeze) {
                            ActivityManagerUnfreezeCommand.Backend.CMD_ACTIVITY
                        } else {
                            ActivityManagerUnfreezeCommand.Backend.AM
                        }
                    }
                    val burstStarted = SystemClock.elapsedRealtime()
                    val deadline = burstStarted + GMS_FAST_THAW_BURST_DURATION_MS
                    var passCount = 0
                    // The event itself is sufficient evidence to issue the first
                    // command. Scanning cgroups before acting was r255's largest
                    // avoidable latency and could consume the whole burst budget.
                    var frozenAfter = listOf(GMS_PACKAGE to -1)
                    val passDetails = mutableListOf<String>()

                    while (
                        frozenAfter.isNotEmpty() &&
                        passCount < GMS_FAST_THAW_MAX_PASSES &&
                        SystemClock.elapsedRealtime() < deadline
                    ) {
                        if (passCount > 0) {
                            val requestedDelay = GMS_FAST_THAW_RETRY_DELAYS_MS[passCount - 1]
                            val remaining = deadline - SystemClock.elapsedRealtime()
                            if (remaining <= 0L) break
                            SystemClock.sleep(minOf(requestedDelay, remaining))
                        }

                        val beforeCommand = SystemClock.elapsedRealtime()
                        if (beforeCommand >= deadline) break
                        passCount += 1
                        val remainingForCommand =
                            (deadline - beforeCommand).coerceAtLeast(1L)
                        val passResult = runGmsFastThawPass(
                            sticky = sticky,
                            secondarySupported = secondarySupported,
                            backend = backend,
                            timeoutMs = minOf(
                                GMS_FAST_THAW_COMMAND_TIMEOUT_MS,
                                remainingForCommand
                            )
                        )
                        val afterCommand = SystemClock.elapsedRealtime()
                        val remainingForVerify =
                            (deadline - afterCommand).coerceAtLeast(0L)
                        if (remainingForVerify > 0L) {
                            SystemClock.sleep(
                                minOf(GMS_FAST_THAW_VERIFY_DELAY_MS, remainingForVerify)
                            )
                        }
                        // Always perform one final verification read, but never
                        // schedule another retry after the hard deadline.
                        frozenAfter = fastReadFrozenGmsProcesses()
                        passDetails +=
                            "pass=$passCount accepted=${passResult.success} " +
                                "durationMs=${passResult.durationMs} " +
                                "remaining=${frozenAfter.map { "${it.first}:${it.second}" }}"
                    }

                    val commandCompleted = SystemClock.elapsedRealtime()
                    val deadlineOverrunMs =
                        (commandCompleted - deadline).coerceAtLeast(0L)
                    val latency = (commandCompleted - signalElapsed).coerceAtLeast(0L)
                    val finalVerified = frozenAfter.isEmpty()
                    var shouldRefreshAnchor = false
                    synchronized(lock) {
                        gmsFastThawAttemptCount += 1
                        gmsFastThawRetryCount += (passCount - 1).coerceAtLeast(0).toLong()
                        actionCount += passCount.toLong()
                        if (finalVerified) {
                            gmsFastThawSuccessCount += 1
                            gmsFastThawFinalVerifiedCount += 1
                            effectiveThawCount += 1
                            if (!lastGmsTransportProbe.healthy) {
                                if (gmsFastThawAwaitingReconnectSinceElapsed <= 0L) {
                                    gmsFastThawAwaitingReconnectSinceElapsed = commandCompleted
                                }
                                shouldRefreshAnchor = true
                            }
                        } else {
                            verificationFailureCount += 1
                        }
                        gmsFastThawLastLatencyMs = latency
                        gmsFastThawMaxLatencyMs = maxOf(gmsFastThawMaxLatencyMs, latency)
                        if (deadlineOverrunMs > 0L) {
                            gmsFastThawDeadlineOverrunCount += 1
                            gmsFastThawMaxDeadlineOverrunMs = maxOf(
                                gmsFastThawMaxDeadlineOverrunMs,
                                deadlineOverrunMs
                            )
                        }
                        gmsFastThawLastCompletedElapsed = commandCompleted
                        eventLocked(
                            if (finalVerified) {
                                "gms_fast_thaw_burst_verified"
                            } else {
                                "gms_fast_thaw_burst_exhausted"
                            },
                            "freezeToUnfreezeLatencyMs=$latency passes=$passCount " +
                                "burstDurationMs=${commandCompleted - burstStarted} " +
                                "deadlineOverrunMs=$deadlineOverrunMs " +
                                "remainingFrozen=${frozenAfter.map { "${it.first}:${it.second}" }} " +
                                "details=${passDetails.joinToString(" | ")}"
                        )
                        persistStatusLocked(force = true)
                    }
                    if (shouldRefreshAnchor) {
                        requestPostThawAnchorQuery()
                    }

                    if (shouldKickMcsAfterThaw()) {
                        runGmsMcsKickWindow(trigger = "fast_thaw")
                    }
                } catch (error: Throwable) {
                    synchronized(lock) {
                        errorCount += 1
                        eventLocked(
                            "gms_fast_thaw_failed",
                            "${error.javaClass.simpleName}:${error.message.orEmpty()}"
                        )
                    }
                } finally {
                    gmsFastThawWorkerActive.set(false)
                    val pendingSignal = gmsFastThawPendingSignalElapsed.getAndSet(0L)
                    if (pendingSignal > 0L) scheduleGmsFastThaw(pendingSignal)
                }
            }
        }.onFailure { error ->
            gmsFastThawWorkerActive.set(false)
            synchronized(lock) {
                errorCount += 1
                eventLocked(
                    "gms_fast_thaw_schedule_failed",
                    "${error.javaClass.simpleName}:${error.message.orEmpty()}"
                )
            }
        }
    }

    private fun runGmsFastThawPass(
        sticky: Boolean,
        secondarySupported: Boolean,
        backend: ActivityManagerUnfreezeCommand.Backend,
        timeoutMs: Long
    ): FastThawPassResult {
        val delegated = synchronized(lock) {
            if (vendorBridgeOwnsGmsCommandsLocked()) {
                recordVendorBridgeSuppressedUnfreezeLocked(GMS_PACKAGE, "gms_fast_thaw_pass")
                true
            } else {
                false
            }
        }
        if (delegated) return FastThawPassResult(success = true, durationMs = 0L)
        val commands = mutableListOf(
            ActivityManagerUnfreezeCommand.shellWithAmFallback(GMS_PACKAGE, sticky, backend)
        )
        if (secondarySupported) {
            commands += ActivityManagerUnfreezeCommand.shellWithAmFallback(
                "$GMS_PACKAGE.persistent",
                sticky,
                backend
            )
        }
        val shellCommand = commands.joinToString(
            separator = " & ",
            postfix = " & wait"
        ) { "($it)" }
        val started = SystemClock.elapsedRealtime()
        val result = runner.run(
            "sh", "-c", shellCommand,
            timeoutMs = timeoutMs.coerceAtLeast(1L)
        )
        val duration =
            (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
        synchronized(lock) {
            if (!result.success) commandFailureCount += 1
            eventLocked(
                "gms_fast_thaw_pass",
                "accepted=${result.success} durationMs=$duration result=${result.summary()}"
            )
        }
        return FastThawPassResult(result.success, duration)
    }

    private fun requestPostThawAnchorQuery() {
        val result = runner.run(
            "am", "broadcast", "--user", "0", "--receiver-foreground",
            "-a", GMS_BINDER_STABILIZATION_ACTION,
            "-n", GMS_BINDER_PULSE_COMPONENT,
            timeoutMs = GMS_BINDER_PULSE_TIMEOUT_MS
        )
        synchronized(lock) {
            actionCount += 1
            if (!result.success) commandFailureCount += 1
            eventLocked(
                "gms_fast_thaw_anchor_query_requested",
                result.summary()
            )
        }
    }
    private fun runGmsMcsKickWindow(
        trigger: String,
        maxRounds: Int = GMS_MCS_KICK_MAX_ROUNDS,
        allowEmergencyEscalation: Boolean = true
    ) {
        val startedElapsed = SystemClock.elapsedRealtime()
        val boundedRounds = maxRounds.coerceIn(1, GMS_MCS_KICK_MAX_ROUNDS)
        val connectAttemptBefore = synchronized(lock) {
            lastGmsMcsConnectAttemptElapsed
        }

        var success = false
        var successfulRound = 0
        var finalProbe = GmsTransportProbe(
            observable = false,
            establishedPorts = emptySet(),
            detail = "not_started"
        )

        synchronized(lock) {
            eventLocked(
                "gms_mcs_kick_started",
                "trigger=$trigger maxRounds=$boundedRounds " +
                    "allowEmergencyEscalation=$allowEmergencyEscalation"
            )
        }

        kickRounds@ for (round in 1..boundedRounds) {
            val bridgeOwnsGms = synchronized(lock) { vendorBridgeOwnsGmsCommandsLocked() }
            val sticky = synchronized(lock) {
                config.stickyUnfreeze && supportsStickyUnfreeze
            }
            val secondarySupported = synchronized(lock) {
                supportsSecondaryProcessUnfreeze
            }
            val backend = synchronized(lock) {
                if (supportsDirectActivityUnfreeze) {
                    ActivityManagerUnfreezeCommand.Backend.CMD_ACTIVITY
                } else {
                    ActivityManagerUnfreezeCommand.Backend.AM
                }
            }

            // With the vendor bridge active, the bridge is the only freezer
            // command owner. A verified bridge recovery is the prerequisite for
            // this transport kick; never race it with another shell unfreeze.
            if (bridgeOwnsGms) {
                val frozen = fastReadFrozenGmsProcesses()
                if (frozen.isNotEmpty()) {
                    synchronized(lock) {
                        eventLocked(
                            "gms_mcs_kick_deferred_to_vendor_bridge",
                            "trigger=$trigger round=$round frozen=${frozen.map { "${it.first}:${it.second}" }}"
                        )
                    }
                    break@kickRounds
                }
            } else {
                runGmsFastThawPass(
                    sticky = sticky,
                    secondarySupported = secondarySupported,
                    backend = backend,
                    timeoutMs = GMS_MCS_KICK_THAW_TIMEOUT_MS
                )
            }

            val broadcast = runner.run(
                "am", "broadcast",
                "--user", "0",
                "--receiver-foreground",
                "-a", GMS_GCM_RECONNECT_ACTION,
                "-p", GMS_PACKAGE,
                timeoutMs = GMS_MCS_KICK_BROADCAST_TIMEOUT_MS
            )

            synchronized(lock) {
                actionCount += 1
                if (!broadcast.success) commandFailureCount += 1

                eventLocked(
                    "gms_mcs_kick_broadcast",
                    "trigger=$trigger round=$round result=${broadcast.summary()}"
                )
            }

            /*
             * am broadcast 返回不代表动态 Receiver 已经真正收到。
             * 接下来三秒持续检查冻结状态，确保 persistent 有机会处理广播。
             */
            val guardDeadline =
                SystemClock.elapsedRealtime() + GMS_MCS_KICK_GUARD_MS

            while (SystemClock.elapsedRealtime() < guardDeadline) {
                val refrozen = fastReadFrozenGmsProcesses()
                if (refrozen.isNotEmpty()) {
                    if (bridgeOwnsGms) {
                        synchronized(lock) {
                            eventLocked(
                                "gms_mcs_kick_guard_delegated_to_vendor_bridge",
                                "trigger=$trigger round=$round frozen=${refrozen.map { "${it.first}:${it.second}" }}"
                            )
                        }
                        break
                    }
                    runGmsFastThawPass(
                        sticky = sticky,
                        secondarySupported = secondarySupported,
                        backend = backend,
                        timeoutMs = GMS_MCS_KICK_THAW_TIMEOUT_MS
                    )
                }

                finalProbe = fastProbeGmsTransport()

                if (finalProbe.healthy) {
                    success = true
                    successfulRound = round
                    break
                }

                SystemClock.sleep(GMS_MCS_KICK_POLL_MS)
            }

            if (success) break
        }

        val completedElapsed = SystemClock.elapsedRealtime()
        val connectAttemptAfter = synchronized(lock) {
            lastGmsMcsConnectAttemptElapsed
        }

        val mcsConnectLatencyMs =
            if (
                connectAttemptAfter > connectAttemptBefore &&
                connectAttemptAfter >= startedElapsed
            ) {
                connectAttemptAfter - startedElapsed
            } else {
                -1L
            }

        val transportLatencyMs =
            if (success) completedElapsed - startedElapsed else -1L

        var startEmergencyCampaign = false
        var escalationMode = "none"
        var exhaustionCountForEscalation = 0

        synchronized(lock) {
            if (success) {
                /*
                 * A restored MCS socket breaks the failure streak. A future
                 * escalation must be supported by two new full kick failures.
                 */
                consecutiveGmsMcsKickExhaustions = 0

                val persistentRunning = listGmsProcessesLocked().any {
                    it.name == "$GMS_PACKAGE.persistent"
                }

                applyGmsTransportProbeLocked(
                    probe = finalProbe,
                    now = completedElapsed,
                    persistentRunning = persistentRunning,
                    source = "mcs_kick"
                )
            } else if (allowEmergencyEscalation) {
                consecutiveGmsMcsKickExhaustions += 1
                exhaustionCountForEscalation =
                    consecutiveGmsMcsKickExhaustions

                val emergencyCooldownReady =
                    lastGmsMcsEmergencyEscalationElapsed == 0L ||
                        completedElapsed -
                        lastGmsMcsEmergencyEscalationElapsed >=
                        GMS_MCS_KICK_EMERGENCY_COOLDOWN_MS

                if (
                    consecutiveGmsMcsKickExhaustions >=
                    GMS_MCS_KICK_EXHAUSTION_THRESHOLD &&
                    emergencyCooldownReady
                ) {
                    val campaign = gmsRecoveryCampaign
                    when {
                        gmsRecoveryInProgress && campaign != null -> {
                            /*
                             * Do not recursively start a second campaign. Ask
                             * the active campaign to consume one bounded hard
                             * reset as soon as force-stop policy allows it.
                             */
                            if (!campaign.hardResetRequested) {
                                campaign.hardResetRequested = true
                                campaign.hardResetReason =
                                    "mcs_kick_exhausted"
                                campaign.hardResetRequestedElapsed =
                                    completedElapsed
                                campaign.nextResetEligibleElapsed =
                                    completedElapsed
                                campaign.resetWaitReportedForCount = -1
                                campaign.stabilizationGraceDeadlineElapsed = 0L
                                campaign.anchorOnlyAfterForceStopGate = false
                                escalationMode = "active_campaign_hard_reset"
                            } else {
                                escalationMode = "hard_reset_already_pending"
                            }
                        }
                        !gmsRecoveryInProgress -> {
                            startEmergencyCampaign = true
                            escalationMode = "new_emergency_campaign"
                        }
                        else -> {
                            escalationMode = "campaign_state_inconsistent"
                        }
                    }

                    if (escalationMode != "campaign_state_inconsistent") {
                        lastGmsMcsEmergencyEscalationElapsed =
                            completedElapsed
                        consecutiveGmsMcsKickExhaustions = 0
                    }
                }
            } else {
                // A defense-episode pulse is deliberately diagnostic and
                // one-shot. It must never build the global exhaustion streak
                // or recursively start a destructive GMS campaign.
                exhaustionCountForEscalation = consecutiveGmsMcsKickExhaustions
            }

            val usedRounds =
                if (success) successfulRound else boundedRounds

            eventLocked(
                if (success) {
                    "gms_mcs_kick_succeeded"
                } else if (!allowEmergencyEscalation) {
                    "gms_mcs_defense_pulse_inconclusive"
                } else {
                    "gms_mcs_kick_exhausted"
                },
                "trigger=$trigger rounds=$usedRounds" +
                    " mcsConnectLatencyMs=$mcsConnectLatencyMs" +
                    " transportLatencyMs=$transportLatencyMs" +
                    " ports=${finalProbe.establishedPorts.sorted()}" +
                    " consecutiveExhaustions=$exhaustionCountForEscalation" +
                    " escalationAllowed=$allowEmergencyEscalation"
            )

            if (escalationMode != "none") {
                eventLocked(
                    "gms_mcs_kick_emergency_escalation",
                    "trigger=$trigger mode=$escalationMode" +
                        " consecutiveExhaustions=" +
                        exhaustionCountForEscalation +
                        " cooldownMs=" +
                        GMS_MCS_KICK_EMERGENCY_COOLDOWN_MS +
                        " generation=${gmsRecoveryCampaign?.generation ?: 0L}"
                )
            }

            persistStatusLocked(force = true)
        }

        if (startEmergencyCampaign) {
            synchronized(lock) {
                recoverGmsLocked(
                    trigger = "mcs_kick_exhausted",
                    manual = false,
                    automaticEvidenceReason =
                        "consecutive_exhaustions=$exhaustionCountForEscalation",
                    emergency = true
                )
            }
        }
    }
    private fun shouldKickMcsAfterThaw(): Boolean {
        repeat(8) {
            val probe = fastProbeGmsTransport()

            if (!probe.healthy) {
                return true
            }

            SystemClock.sleep(250L)
        }

        return false
    }
    private fun fastProbeGmsTransport(): GmsTransportProbe {
        val result = runner.run(
            "ss", "-H", "-tn",
            timeoutMs = GMS_MCS_KICK_SOCKET_TIMEOUT_MS
        )

        if (!result.success) {
            return GmsTransportProbe(
                observable = false,
                establishedPorts = emptySet(),
                detail = result.summary()
            )
        }

        val ports =
            GmsTransportSocketParser.establishedMcsPorts(result.stdout)

        return GmsTransportProbe(
            observable = true,
            establishedPorts = ports,
            detail = "source=mcs_kick lines=${result.stdout.lineSequence().count()}"
        )
    }
    private fun fastReadFrozenGmsProcesses(): List<Pair<String, Int>> {
        val preferred = runner.run(
            "ps", "-A", "-o", "PID,NAME,ARGS",
            timeoutMs = GMS_FAST_THAW_PROCESS_SCAN_TIMEOUT_MS
        )
        val processes = if (preferred.success) {
            GuardianProcessParser.parse(preferred.stdout)
        } else {
            val fallback = runner.run(
                "ps", "-A",
                timeoutMs = GMS_FAST_THAW_PROCESS_SCAN_TIMEOUT_MS
            )
            if (fallback.success) {
                GuardianProcessParser.parse(fallback.stdout)
            } else {
                return listOf("scan_unobservable" to -1)
            }
        }
        return GuardianProcessParser.matching(
            processes,
            listOf(GMS_PACKAGE, "$GMS_PACKAGE.persistent")
        ).mapNotNull { process ->
            if (readFreezeState(process.pid).frozen == true) {
                process.name to process.pid
            } else {
                null
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
                enabled = canDirectCgroupThawLocked(),
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
            if (DeliveryFailureEscalationPolicy.isRebuildTarget(packageName)) {
                /*
                 * Xiaomi's Greezer denial is often logged as non-critical even
                 * though the verified result is cgroup.freeze=1. After the full
                 * thaw burst has failed, keeping that PID cannot deliver FCM.
                 * Use the existing non-force-stop package rebuild tier.
                 */
                maybeSchedulePackageRebuildLocked(
                    packageName = packageName,
                    now = now,
                    verifiedFrozenAfterBurst = true,
                    forcedReason = if (deliveryCritical) {
                        null
                    } else {
                        "verified_vendor_thaw_exhaustion"
                    }
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

    private fun vendorBridgeOwnerPackageLocked(processName: String): String? {
        val packageName = packageForProcess(processName) ?: processName
        return packageName.takeIf {
            running && it in vendorBridgeTargetsLocked()
        }
    }

    /**
     * Command ownership follows configured policy, not subprocess liveness.
     * During bridge startup or restart, falling back to a second unfreeze loop
     * would recreate the exact race this bridge exists to remove.
     */
    private fun vendorBridgeOwnsGmsCommandsLocked(): Boolean =
        running && GMS_PACKAGE in vendorBridgeTargetsLocked()

    private fun recordVendorBridgeSuppressedUnfreezeLocked(packageName: String, source: String) {
        vendorBridgeSuppressedInternalUnfreezeCount += 1
        val now = SystemClock.elapsedRealtime()
        val previous = lastDelegatedUnfreezeLogByPackage[packageName] ?: 0L
        if (previous <= 0L || now < previous || now - previous >= DELEGATED_UNFREEZE_LOG_INTERVAL_MS) {
            lastDelegatedUnfreezeLogByPackage[packageName] = now
            eventLocked(
                "unfreeze_delegated_to_vendor_bridge",
                "package=$packageName source=$source count=$vendorBridgeSuppressedInternalUnfreezeCount"
            )
        }
    }

    private fun updateVendorBridgeFileHeartbeatLocked(now: Long) {
        val path = vendorBridgeHeartbeatPath.ifBlank {
            GmsVendorFreezeBridgeScript.heartbeatPath(Process.myPid())
        }
        val values = runCatching {
            File(path).takeIf { it.isFile }?.readLines()?.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator > 0) line.substring(0, separator) to line.substring(separator + 1)
                else null
            }?.toMap()
        }.getOrNull().orEmpty()
        val shellPid = values["shellPid"]?.toIntOrNull() ?: 0
        val parentPid = values["parentPid"]?.toIntOrNull() ?: 0
        val owner = values["owner"].orEmpty()
        val heartbeatParentStart = values["parentStartTicks"].orEmpty()
        val heartbeatShellStart = values["shellStartTicks"].orEmpty()
        val atCs = values["atCs"]?.toLongOrNull() ?: 0L
        val ageMs = if (atCs > 0L) (now - atCs * 10L).coerceAtLeast(0L) else -1L
        val ownerPath = vendorBridgeOwnerPath.ifBlank {
            GmsVendorFreezeBridgeScript.COMMAND_OWNER_PATH
        }
        val ownerValues = runCatching {
            File(ownerPath).takeIf { it.isFile }?.readLines()?.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator > 0) line.substring(0, separator) to line.substring(separator + 1)
                else null
            }?.toMap()
        }.getOrNull().orEmpty()
        val currentParentStart = readProcStartTimeTicks(Process.myPid()).orEmpty()
        val currentShellStart = readProcStartTimeTicks(vendorBridgeShellPid).orEmpty()
        vendorBridgeOwnerLeaseValid =
            ownerValues["owner"] == "vendor_bridge" &&
                ownerValues["parentPid"]?.toIntOrNull() == Process.myPid() &&
                ownerValues["shellPid"]?.toIntOrNull() == vendorBridgeShellPid &&
                ownerValues["parentStartTicks"] == vendorBridgeParentStartTimeTicks &&
                ownerValues["shellStartTicks"] == vendorBridgeShellStartTimeTicks &&
                vendorBridgeParentStartTimeTicks == currentParentStart &&
                vendorBridgeShellStartTimeTicks == currentShellStart &&
                ownerValues["heartbeatPath"] == path
        vendorBridgeHeartbeatFileValid =
            owner == "vendor_bridge" &&
                parentPid == Process.myPid() &&
                shellPid > 1 &&
                shellPid == vendorBridgeShellPid &&
                heartbeatParentStart == vendorBridgeParentStartTimeTicks &&
                heartbeatShellStart == vendorBridgeShellStartTimeTicks &&
                ageMs >= 0L &&
                vendorBridgeOwnerLeaseValid
        vendorBridgeHeartbeatFileAgeMs = ageMs
    }

    private fun readProcStartTimeTicks(pid: Int): String? {
        val raw = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        val close = raw.lastIndexOf(')')
        if (close < 0 || close + 1 >= raw.length) return null
        // The first token after ')' is field 3 (state); starttime is field 22.
        return raw.substring(close + 1)
            .trim()
            .split(Regex("\\s+"))
            .getOrNull(19)
            ?.takeIf { it.all(Char::isDigit) }
    }

    private fun isExactLegacyGuardianCommand(cmdline: String): Boolean {
        val tokens = cmdline.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val scriptIndex = tokens.indexOfFirst { token ->
            token.substringAfterLast('/') == "luonnotar-guardian-v2.sh"
        }
        return scriptIndex >= 0 && tokens.getOrNull(scriptIndex + 1) == "daemon"
    }

    private fun readVerifiedLegacyGuardianIdentity(pid: Int): Pair<String, String>? {
        if (pid <= 1) return null
        val startTime = readProcStartTimeTicks(pid) ?: return null
        val cmdline = runCatching {
            File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ')
        }.getOrNull() ?: return null
        if (!isExactLegacyGuardianCommand(cmdline)) return null
        return startTime to cmdline
    }

    private fun legacyGuardianIdentityStillMatches(
        pid: Int,
        startTime: String,
        expectedCmdline: String
    ): Boolean {
        val current = readVerifiedLegacyGuardianIdentity(pid) ?: return false
        return current.first == startTime && current.second == expectedCmdline
    }

    private fun quarantineLegacyUnfreezeShellsLocked() {
        val candidates = linkedSetOf<Int>()
        val pidFile = runner.run("cat", LEGACY_GUARDIAN_PID_PATH, timeoutMs = 1_000L)
        pidFile.stdout.trim().toIntOrNull()?.takeIf { it > 1 }?.let(candidates::add)
        val ps = runner.run("ps", "-A", "-o", "PID,PPID,ARGS", timeoutMs = 3_000L)
        if (ps.success) {
            val pattern = Regex("""^\s*(\d+)\s+\d+\s+(.+)$""")
            ps.stdout.lineSequence().forEach { line ->
                val match = pattern.matchEntire(line) ?: return@forEach
                if (isExactLegacyGuardianCommand(match.groupValues[2])) {
                    match.groupValues[1].toIntOrNull()?.takeIf { it > 1 }?.let(candidates::add)
                }
            }
        }
        if (candidates.isEmpty()) {
            legacyGuardianLastResult = "none"
            return
        }
        candidates.forEach { pid ->
            val identity = readVerifiedLegacyGuardianIdentity(pid)
            if (identity == null) {
                legacyGuardianLastResult = "ignored_unverified_or_reused_pid:$pid"
                eventLocked("legacy_unfreeze_shell_ignored", "pid=$pid identity_not_exact")
                return@forEach
            }
            legacyGuardianDetectedCount += 1
            val (startTime, cmdline) = identity
            // Revalidate immediately before every signal. A PID file alone is
            // never authority because Android may have already reused the PID.
            if (!legacyGuardianIdentityStillMatches(pid, startTime, cmdline)) {
                legacyGuardianLastResult = "identity_changed_before_term:$pid"
                eventLocked("legacy_unfreeze_shell_ignored", legacyGuardianLastResult)
                return@forEach
            }
            val term = runner.run("kill", "-TERM", pid.toString(), timeoutMs = 1_000L)
            SystemClock.sleep(150L)
            val sameProcessAlive = legacyGuardianIdentityStillMatches(pid, startTime, cmdline)
            val kill = if (sameProcessAlive) {
                runner.run("kill", "-KILL", pid.toString(), timeoutMs = 1_000L)
            } else {
                null
            }
            val stopped = !legacyGuardianIdentityStillMatches(pid, startTime, cmdline)
            if (stopped) legacyGuardianStoppedCount += 1
            legacyGuardianLastResult =
                "pid=$pid start=$startTime stopped=$stopped term=${term.exitCode} kill=${kill?.exitCode ?: 125}"
            eventLocked(
                if (stopped) "legacy_unfreeze_shell_quarantined" else "legacy_unfreeze_shell_stop_failed",
                legacyGuardianLastResult
            )
        }
    }

    private fun scheduleVendorBridgeLockEscalation(signalElapsed: Long, sequence: Long) {
        runCatching {
            executor.schedule(
                {
                    synchronized(lock) {
                        if (!running || !vendorBridgeOwnsGmsCommandsLocked()) return@synchronized
                        if (
                            sequence != vendorBridgeDefenseLastSequence ||
                            vendorBridgeDefenseLastPhase !in setOf("escalating", "expired")
                        ) {
                            eventLocked(
                                "vendor_bridge_lock_escalation_stale",
                                "seq=$sequence currentSeq=$vendorBridgeDefenseLastSequence " +
                                    "phase=$vendorBridgeDefenseLastPhase"
                            )
                            return@synchronized
                        }
                        val frozen = listGmsProcessesLocked().filter {
                            readFreezeState(it.pid).frozen == true
                        }
                        val probe = probeGmsTransportLocked()
                        applyGmsTransportProbeLocked(
                            probe = probe,
                            now = SystemClock.elapsedRealtime(),
                            persistentRunning = listGmsProcessesLocked().any {
                                it.name == "$GMS_PACKAGE.persistent"
                            },
                            source = "vendor_lock"
                        )
                        if (frozen.isNotEmpty() && probe.observable && !probe.healthy &&
                            !gmsRecoveryInProgress
                        ) {
                            if (vendorDefenseOwnsGmsRecoveryLocked()) {
                                vendorBridgeDefenseRecoverySuppressionCount += 1
                                eventLocked(
                                    "vendor_bridge_lock_escalation_suppressed_defense_owner",
                                    "seq=$sequence signalElapsed=$signalElapsed " +
                                        "ownerSeq=$vendorBridgeDefenseOwnershipSequence " +
                                        "phase=$vendorBridgeDefenseOwnershipPhase " +
                                        "frozen=${frozen.map { "${it.name}:${it.pid}" }}"
                                )
                            } else {
                                vendorBridgeLockEscalationCount += 1
                                eventLocked(
                                    "vendor_bridge_lock_escalated",
                                    "seq=$sequence signalElapsed=$signalElapsed frozen=${frozen.map { "${it.name}:${it.pid}" }}"
                                )
                                recoverGmsLocked(
                                    trigger = "vendor_bridge_refreeze_lock",
                                    manual = false,
                                    automaticEvidenceReason = "vendor_bridge_refreeze_lock",
                                    emergency = true
                                )
                            }
                        }
                    }
                },
                VENDOR_BRIDGE_LOCK_ESCALATION_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
        }.onFailure { error ->
            synchronized(lock) {
                errorCount += 1
                eventLocked(
                    "vendor_bridge_lock_escalation_schedule_failed",
                    "seq=$sequence ${error.javaClass.simpleName}:${error.message.orEmpty()}"
                )
            }
        }
    }

    private fun isVendorBridgeTargetedLocked(): Boolean =
        vendorBridgeTargetsLocked().isNotEmpty()

    private fun vendorBridgeTargetsLocked(): Set<String> {
        val family = vendorBridgeFamilyLocked()
        if (
            family != BackgroundPolicyVendorFamily.VIVO &&
            family != BackgroundPolicyVendorFamily.XIAOMI
        ) {
            return emptySet()
        }
        return buildSet {
            // GMS adopt-release remains an OriginOS-specific response to the
            // proven PEM/framework bookkeeping split.
            if (
                family == BackgroundPolicyVendorFamily.VIVO &&
                isPackageTargetedLocked(GMS_PACKAGE)
            ) {
                add(GMS_PACKAGE)
            }
            // Xiaomi tablets have shown the same physical-freeze symptom on
            // WhatsApp. Signal follows the same bounded, non-destructive path
            // but intentionally has no separate recovery campaign.
            if (isPackageTargetedLocked(WHATSAPP_PACKAGE)) add(WHATSAPP_PACKAGE)
            if (isPackageTargetedLocked(SIGNAL_PACKAGE)) add(SIGNAL_PACKAGE)
        }
    }

    private fun vendorBridgeOwnsGmsLocked(): Boolean =
        GMS_PACKAGE in vendorBridgeTargetsLocked()

    private fun vendorBridgeOwnsPackageLocked(packageName: String?): Boolean =
        packageName != null && packageName in vendorBridgeTargetsLocked()

    private fun vendorBridgeFamilyLocked(): BackgroundPolicyVendorFamily {
        vendorBridgeDeviceFamily?.let { return it }
        return currentVendorFamilyLocked().also { vendorBridgeDeviceFamily = it }
    }

    private fun isPackageTargetedLocked(packageName: String): Boolean =
        packageName in config.packageTargets ||
            config.processTargets.any { processName ->
                processName == packageName || processName.startsWith("$packageName:")
            }

    private fun isGmsFastLaneTargetedLocked(): Boolean =
        isPackageTargetedLocked(GMS_PACKAGE)

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
        eventWatcherRestartFuture?.cancel(false)
        eventWatcherRestartFuture = null
        eventFastLaneReadyTimeoutFuture?.cancel(false)
        eventFastLaneReadyTimeoutFuture = null
        eventWatcherAlive = false
        eventFastLaneReady = false
        eventFastLaneBackend = "none"
        eventFastLaneTimeoutSupported = false
        eventFastLaneStickyConfigured = false
        eventFastLaneTargetEnabled = false
        eventWatcherMode = "none"
        val process = eventWatcherProcess
        eventWatcherProcess = null
        process?.destroy()
        eventWatcherThread?.interrupt()
        eventWatcherThread = null
        eventFastLaneSignalElapsedBySequence.clear()
        eventFastLaneProbeHandledSequences.clear()
        eventFastLaneVerifiedSequences.clear()
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
        if (isVendorBridgeTargetedLocked() &&
            (lastLegacyGuardianAuditElapsed <= 0L || now < lastLegacyGuardianAuditElapsed ||
                now - lastLegacyGuardianAuditElapsed >= LEGACY_GUARDIAN_AUDIT_INTERVAL_MS)
        ) {
            lastLegacyGuardianAuditElapsed = now
            quarantineLegacyUnfreezeShellsLocked()
        }
        if (isVendorBridgeTargetedLocked()) {
            updateVendorBridgeFileHeartbeatLocked(now)
            val protocolAgeMs = if (vendorBridgeLastHeartbeatElapsed > 0L &&
                now >= vendorBridgeLastHeartbeatElapsed
            ) now - vendorBridgeLastHeartbeatElapsed else Long.MAX_VALUE
            val protocolStale = vendorBridgeReady &&
                protocolAgeMs > VENDOR_BRIDGE_STALE_HEARTBEAT_MS
            val fileStale = !vendorBridgeHeartbeatFileValid ||
                vendorBridgeHeartbeatFileAgeMs > VENDOR_BRIDGE_STALE_HEARTBEAT_MS
            val vendorBridgeHeartbeatStale = protocolStale && fileStale
            if (!vendorBridgeAlive || vendorBridgeHeartbeatStale) {
                if (vendorBridgeHeartbeatStale) {
                    eventLocked(
                        "vendor_bridge_heartbeat_stale",
                        "protocolAgeMs=$protocolAgeMs fileAgeMs=$vendorBridgeHeartbeatFileAgeMs " +
                            "fileValid=$vendorBridgeHeartbeatFileValid processAlive=${vendorBridgeProcess?.isAlive == true}"
                    )
                    stopVendorBridgeLocked()
                }
                startVendorBridgeLocked()
            } else if (protocolStale && vendorBridgeHeartbeatFileValid) {
                eventLocked(
                    "vendor_bridge_protocol_heartbeat_delayed",
                    "protocolAgeMs=$protocolAgeMs fileAgeMs=$vendorBridgeHeartbeatFileAgeMs"
                )
            }
        } else if (vendorBridgeAlive || vendorBridgeProcess != null) {
            stopVendorBridgeLocked()
        }
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
            // A bridge-owned package must have exactly one freezer command
            // owner. Concurrent poller unfreezes can split the bounded
            // adopt -> release transaction and recreate the stale-state race.
            val vendorBridgeOwnsPackage =
                vendorBridgeOwnsPackageLocked(guardedPackage)
            val shouldAct = (force || policyDue) && !vendorBridgeOwnsPackage
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
                    val unfreezeBackend = if (supportsDirectActivityUnfreeze) {
                        "cmd_activity"
                    } else {
                        "am"
                    }
                    action = if (config.stickyUnfreeze && supportsStickyUnfreeze) {
                        "${unfreezeBackend}_unfreeze_sticky"
                    } else {
                        "${unfreezeBackend}_unfreeze"
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
                    enabled = canDirectCgroupThawLocked(),
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
        if (rawLine.contains("fast_freezer", ignoreCase = true)) {
            gmsVivoFastFreezerEvents.addLast(now)
        }
        eventLocked(
            "gms_freeze_evidence",
            "count=${gmsFreezeEvents.size} fastFreezerCount=${gmsVivoFastFreezerEvents.size} " +
                rawLine.take(220)
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
            gmsTransportMissingEpisodePids = emptySet()
            gmsVivoFastFreezerEvents.clear()
        } else if (probe.healthy) {
            gmsTransportHealthyCount += 1
            lastGmsTransportHealthyElapsed = now
            gmsTransportConsecutiveMissing = 0
            gmsTransportMissingSinceElapsed = 0L
            gmsTransportMissingEpisodePids = emptySet()
            gmsVivoFastFreezerEvents.clear()
        } else {
            if (gmsTransportMissingSinceElapsed <= 0L || gmsTransportMissingSinceElapsed > now) {
                gmsTransportMissingSinceElapsed = now
                gmsTransportMissingEpisodePids =
                    listGmsProcessesLocked().mapTo(linkedSetOf()) { it.pid }
                gmsRecentFreezerEvidenceLatchedMissingEpisodeElapsed = 0L
            } else if (gmsTransportMissingEpisodePids.isEmpty()) {
                gmsTransportMissingEpisodePids =
                    listGmsProcessesLocked().mapTo(linkedSetOf()) { it.pid }
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

    private fun clearVendorDefenseRecoveryOwnershipLocked(reason: String) {
        if (vendorBridgeDefenseOwnershipSequence > 0L) {
            eventLocked(
                "vendor_defense_recovery_owner_released",
                "seq=$vendorBridgeDefenseOwnershipSequence phase=$vendorBridgeDefenseOwnershipPhase reason=$reason"
            )
        }
        vendorBridgeDefenseOwnershipSequence = 0L
        vendorBridgeDefenseOwnershipUntilElapsed = 0L
        vendorBridgeDefenseOwnershipPhase = reason
    }

    private fun vendorDefenseOwnsGmsRecoveryLocked(
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): Boolean {
        if (!vendorBridgeReady || vendorBridgeDefenseOwnershipSequence <= 0L) return false
        if (vendorBridgeDefenseOwnershipUntilElapsed <= nowElapsed) {
            clearVendorDefenseRecoveryOwnershipLocked("ttl_expired")
            return false
        }
        return true
    }

    private fun gmsPostSuccessProtectionActiveLocked(nowElapsed: Long): Boolean =
        gmsPostSuccessProtectionUntilElapsed > nowElapsed

    private fun gmsVerifiedOutageDeadlineLocked(nowElapsed: Long): Long =
        RecoveryCampaignPolicy.gmsVerifiedOutageDeadlineMs(
            postSuccessProtectionActive = gmsPostSuccessProtectionActiveLocked(nowElapsed)
        )

    private fun recentVivoFastFreezerEvidenceLocked(
        nowElapsed: Long,
        vendorFamily: BackgroundPolicyVendorFamily,
        currentPids: Set<Int>,
        logActivation: Boolean
    ): Boolean {
        val eligible = RecoveryCampaignPolicy.shouldUseRecentVivoFastFreezerEvidence(
            vendorFamily = vendorFamily,
            nowElapsed = nowElapsed,
            transportMissingSinceElapsed = gmsTransportMissingSinceElapsed,
            consecutiveMissing = gmsTransportConsecutiveMissing,
            transportHealthy = lastGmsTransportProbe.healthy,
            requiredOutageMs = gmsVerifiedOutageDeadlineLocked(nowElapsed),
            missingEpisodePids = gmsTransportMissingEpisodePids,
            currentPids = currentPids,
            recentFastFreezerEventElapsed = gmsVivoFastFreezerEvents.toList()
        )
        if (
            eligible &&
            logActivation &&
            gmsRecentFreezerEvidenceLatchedMissingEpisodeElapsed !=
                gmsTransportMissingSinceElapsed
        ) {
            gmsRecentFreezerEvidenceLatchedMissingEpisodeElapsed =
                gmsTransportMissingSinceElapsed
            gmsRecentFreezerEvidenceLatchCount += 1
            val recentCutoff =
                (nowElapsed -
                    RecoveryCampaignPolicy.GMS_VIVO_RECENT_FAST_FREEZER_EVIDENCE_WINDOW_MS)
                    .coerceAtLeast(0L)
            val recentCount = gmsVivoFastFreezerEvents.count {
                it in maxOf(recentCutoff, gmsTransportMissingSinceElapsed)..nowElapsed
            }
            eventLocked(
                "gms_recent_vivo_fast_freezer_evidence_latched",
                "missingSince=$gmsTransportMissingSinceElapsed " +
                    "outageAgeMs=" +
                    (nowElapsed - gmsTransportMissingSinceElapsed).coerceAtLeast(0L) +
                    " missing=$gmsTransportConsecutiveMissing events=$recentCount " +
                    "windowMs=" +
                    RecoveryCampaignPolicy.GMS_VIVO_RECENT_FAST_FREEZER_EVIDENCE_WINDOW_MS +
                    " pids=${currentPids.sorted()}"
            )
        }
        return eligible
    }

    private fun gmsCooldownBypassEligibleLocked(
        nowElapsed: Long,
        vendorFamily: BackgroundPolicyVendorFamily,
        strongEvidence: Boolean
    ): Boolean = RecoveryCampaignPolicy.shouldBypassGmsAdaptiveCooldown(
        vendorFamily = vendorFamily,
        strongEvidence = strongEvidence,
        nowElapsed = nowElapsed,
        transportMissingSinceElapsed = gmsTransportMissingSinceElapsed,
        lastBypassedMissingEpisodeElapsed = gmsCooldownBypassMissingEpisodeElapsed,
        postSuccessProtectionActive = gmsPostSuccessProtectionActiveLocked(nowElapsed)
    )

    private fun maybeStartVerifiedGmsCampaignLocked() {
        if (gmsRecoveryInProgress || !config.vendorEmergencyRecoveryEnabled) return
        if (!lastGmsTransportProbe.observable || lastGmsTransportProbe.healthy) return
        if (gmsTransportConsecutiveMissing < 3) return
        val now = SystemClock.elapsedRealtime()
        val gmsProcesses = listGmsProcessesLocked()
        val currentPids = gmsProcesses.mapTo(linkedSetOf()) { it.pid }
        val frozen = gmsProcesses.filter { process -> readFreezeState(process.pid).frozen == true }
        val vendorFamily = currentVendorFamilyLocked()
        val recentFreezerEvidence =
            frozen.isEmpty() &&
                recentVivoFastFreezerEvidenceLocked(
                    nowElapsed = now,
                    vendorFamily = vendorFamily,
                    currentPids = currentPids,
                    logActivation = true
                )
        if (frozen.isEmpty() && !recentFreezerEvidence) return
        val evidenceReason = if (frozen.isNotEmpty()) {
            "verified_cgroup_frozen_mcs_missing"
        } else {
            "verified_recent_vivo_fast_freezer_mcs_missing"
        }
        eventLocked(
            "gms_verified_outage_detected",
            "frozen=${frozen.joinToString { "${it.name}:${it.pid}" }} " +
                "recentFastFreezer=$recentFreezerEvidence " +
                "missing=$gmsTransportConsecutiveMissing " +
                "ports=${lastGmsTransportProbe.establishedPorts.sorted()}"
        )
        recoverGmsLocked(
            trigger = evidenceReason,
            manual = false,
            automaticEvidenceReason = evidenceReason,
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
        val currentGmsPids = gmsProcesses.mapTo(linkedSetOf()) { it.pid }
        val anyGmsFrozen = gmsProcesses.any { process -> readFreezeState(process.pid).frozen == true }
        val vendorFamily = currentVendorFamilyLocked()
        val recentFreezerEvidence =
            !anyGmsFrozen &&
                recentVivoFastFreezerEvidenceLocked(
                    nowElapsed = now,
                    vendorFamily = vendorFamily,
                    currentPids = currentGmsPids,
                    logActivation = true
                )
        val strongEvidence =
            (anyGmsFrozen || recentFreezerEvidence) &&
                lastGmsTransportProbe.observable &&
                !lastGmsTransportProbe.healthy &&
                gmsTransportConsecutiveMissing >= 3
        val outageAgeMs = if (
            gmsTransportMissingSinceElapsed > 0L &&
            gmsTransportMissingSinceElapsed <= now
        ) {
            now - gmsTransportMissingSinceElapsed
        } else {
            0L
        }
        val verifiedOutageDeadlineReached = gmsCooldownBypassEligibleLocked(
            nowElapsed = now,
            vendorFamily = vendorFamily,
            strongEvidence = strongEvidence
        )
        val vendorDefenseOwnsRecovery = vendorDefenseOwnsGmsRecoveryLocked(now)
        val vendorDefenseOwnerDeadlineOverride =
            !manual &&
                vendorFamily == BackgroundPolicyVendorFamily.VIVO &&
                strongEvidence &&
                verifiedOutageDeadlineReached
        if (vendorDefenseOwnsRecovery && !vendorDefenseOwnerDeadlineOverride) {
            vendorBridgeDefenseRecoverySuppressionCount += 1
            eventLocked(
                "gms_recovery_suppressed_vendor_defense_owner",
                "source=$trigger seq=$vendorBridgeDefenseOwnershipSequence " +
                    "phase=$vendorBridgeDefenseOwnershipPhase"
            )
            lastGmsRecoveryOutcome = GmsRecoveryOutcome(
                trigger = trigger,
                result = "suppressed:vendor_defense_owner",
                startedElapsed = now,
                completedElapsed = now
            )
            return statusJsonLocked()
        } else if (vendorDefenseOwnsRecovery && vendorDefenseOwnerDeadlineOverride) {
            eventLocked(
                "gms_recovery_vendor_defense_owner_overridden_verified_outage",
                "source=$trigger seq=$vendorBridgeDefenseOwnershipSequence " +
                    "phase=$vendorBridgeDefenseOwnershipPhase outageAgeMs=$outageAgeMs " +
                    "deadlineMs=${gmsVerifiedOutageDeadlineLocked(now)}"
            )
        }
        val campaignDecision = RecoveryCampaignPolicy.decideGmsCampaign(
            nowElapsed = now,
            lastCampaignCompletedElapsed = lastGmsRecoveryCompletedElapsed,
            campaignHistory = gmsRecoveryHistory.toList(),
            manual = manual,
            strongEvidence = strongEvidence,
            consecutiveFailureCount = gmsConsecutiveCampaignFailures,
            verifiedOutageDeadlineReached = verifiedOutageDeadlineReached
        )
        if (!campaignDecision.allowed) {
            eventLocked(
                "gms_recovery_blocked",
                "manual=$manual reason=${campaignDecision.reason} strongEvidence=$strongEvidence " +
                    "frozen=$anyGmsFrozen recentFastFreezer=$recentFreezerEvidence " +
                    "transport=${lastGmsTransportProbe.healthy} " +
                    "missing=$gmsTransportConsecutiveMissing outageAgeMs=$outageAgeMs " +
                    "deadlineMs=${gmsVerifiedOutageDeadlineLocked(now)}"
            )
            lastGmsRecoveryOutcome = GmsRecoveryOutcome(
                trigger = trigger,
                result = "blocked:${campaignDecision.reason}",
                startedElapsed = now,
                completedElapsed = now
            )
            return statusJsonLocked()
        }

        val deadlineRescue = campaignDecision.reason == "verified_outage_deadline_rescue"
        if (deadlineRescue) {
            gmsCooldownBypassMissingEpisodeElapsed = gmsTransportMissingSinceElapsed
            gmsCooldownBypassCount += 1
            eventLocked(
                "gms_recovery_cooldown_bypassed_verified_outage",
                "missingSince=${gmsTransportMissingSinceElapsed} outageAgeMs=$outageAgeMs " +
                    "deadlineMs=${gmsVerifiedOutageDeadlineLocked(now)} " +
                    "consecutiveFailures=$gmsConsecutiveCampaignFailures"
            )
        }

        val generation = gmsRecoveryGeneration + 1L
        gmsRecoveryGeneration = generation
        val oldPids = gmsProcesses.mapTo(linkedSetOf()) { it.pid }
        resetGmsImportanceFenceCampaignMetricsLocked(gmsProcesses)
        val campaign = GmsRecoveryCampaign(
            trigger = trigger,
            manual = manual,
            generation = generation,
            startedElapsed = now,
            deadlineElapsed = now + RecoveryCampaignPolicy.GMS_CAMPAIGN_DURATION_MS,
            initialPids = oldPids,
            nextResetEligibleElapsed = if (deadlineRescue) {
                now
            } else {
                now + RecoveryCampaignPolicy.gmsInitialResetDelayMs(vendorFamily)
            }
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
                "strongEvidence=$strongEvidence frozenNow=$anyGmsFrozen " +
                "recentFastFreezer=$recentFreezerEvidence emergency=$emergency vendor=$vendorFamily " +
                "decision=${campaignDecision.reason} evidence=${automaticEvidenceReason.orEmpty()} " +
                "outageAgeMs=$outageAgeMs deadlineRescue=$deadlineRescue " +
                "nextResetEligibleElapsed=${campaign.nextResetEligibleElapsed}"
        )
        ensureGmsPreconnectionLeaseLocked(
            campaign = campaign,
            nowElapsed = now,
            reason = "campaign_started",
            force = true
        )
        scheduleGmsFastThaw(signalElapsed = now)
        probeGmsImportanceFenceLocked(now, force = true)
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
        probeGmsImportanceFenceLocked(now, force = false)
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
            campaign.stabilizationGraceDeadlineElapsed = 0L
            campaign.stabilizationLeaseRequestedElapsed = 0L
            gmsFastThawAwaitingReconnectSinceElapsed = 0L
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
            campaign.lastRefreezeElapsed = now
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
        if (campaign.resetCount > 0) {
            if (probe.healthy) {
                campaign.postResetTransportMissingSinceElapsed = 0L
                campaign.inCampaignOutageDeadlineReportedForResetCount = -1
            } else if (
                campaign.postResetTransportMissingSinceElapsed <= 0L ||
                campaign.postResetTransportMissingSinceElapsed > now
            ) {
                campaign.postResetTransportMissingSinceElapsed = now
            }
        } else {
            campaign.postResetTransportMissingSinceElapsed = 0L
        }

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
            campaign.stabilizationGraceDeadlineElapsed = 0L
            if (campaign.stableSinceElapsed <= 0L || campaign.stableSinceElapsed > now) {
                campaign.stableSinceElapsed = now
                eventLocked(
                    "gms_recovery_stability_window_started",
                    "generation=$generation ports=${probe.establishedPorts.sorted()} " +
                        "requiredMs=${RecoveryCampaignPolicy.GMS_CAMPAIGN_STABLE_MS}"
                )
            }
            val continuousHealthyMs =
                (now - campaign.stableSinceElapsed).coerceAtLeast(0L)
            campaign.longestContinuousTransportMs = maxOf(
                campaign.longestContinuousTransportMs,
                continuousHealthyMs
            )
            campaign.phaseLongestContinuousTransportMs = maxOf(
                campaign.phaseLongestContinuousTransportMs,
                continuousHealthyMs
            )
            gmsTransportLongestContinuousMs = maxOf(
                gmsTransportLongestContinuousMs,
                continuousHealthyMs
            )
            if (gmsFastThawAwaitingReconnectSinceElapsed > 0L) {
                val reconnectLatency =
                    (now - gmsFastThawAwaitingReconnectSinceElapsed).coerceAtLeast(0L)
                gmsFastThawPostReconnectCount += 1
                gmsFastThawLastPostReconnectLatencyMs = reconnectLatency
                gmsFastThawMaxPostReconnectLatencyMs = maxOf(
                    gmsFastThawMaxPostReconnectLatencyMs,
                    reconnectLatency
                )
                eventLocked(
                    "gms_fast_thaw_transport_reconnected",
                    "postThawReconnectLatencyMs=$reconnectLatency " +
                        "ports=${probe.establishedPorts.sorted()}"
                )
                gmsFastThawAwaitingReconnectSinceElapsed = 0L
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
            if (campaign.stableSinceElapsed > 0L && campaign.stableSinceElapsed <= now) {
                val continuousHealthyMs =
                    (now - campaign.stableSinceElapsed).coerceAtLeast(0L)
                campaign.longestContinuousTransportMs = maxOf(
                    campaign.longestContinuousTransportMs,
                    continuousHealthyMs
                )
                campaign.phaseLongestContinuousTransportMs = maxOf(
                    campaign.phaseLongestContinuousTransportMs,
                    continuousHealthyMs
                )
                gmsTransportLongestContinuousMs = maxOf(
                    gmsTransportLongestContinuousMs,
                    continuousHealthyMs
                )
                campaign.transportCollapseCount += 1
                gmsTransportCollapseCount += 1
                if (
                    campaign.collapseWindowStartedElapsed <= 0L ||
                    campaign.collapseWindowStartedElapsed > now ||
                    now - campaign.collapseWindowStartedElapsed >
                        RecoveryCampaignPolicy.GMS_TRANSPORT_COLLAPSE_WINDOW_MS
                ) {
                    campaign.collapseWindowStartedElapsed = now
                    campaign.collapseCountInWindow = 1
                } else {
                    campaign.collapseCountInWindow += 1
                }
                eventLocked(
                    "gms_recovery_transport_collapsed",
                    "generation=$generation continuousHealthyMs=$continuousHealthyMs " +
                        "total=${campaign.transportCollapseCount} " +
                        "windowCount=${campaign.collapseCountInWindow}"
                )
            }
            campaign.stableSinceElapsed = 0L
            if (campaign.stabilizationStartedElapsed > 0L) {
                if (
                    campaign.stabilizationDegradedSinceElapsed <= 0L ||
                    campaign.stabilizationDegradedSinceElapsed > now
                ) {
                    campaign.stabilizationDegradedSinceElapsed = now
                    if (!campaign.stabilizationGraceConsumed) {
                        campaign.stabilizationGraceConsumed = true
                        campaign.stabilizationGraceDeadlineElapsed =
                            now + RecoveryCampaignPolicy.GMS_STABILIZATION_DEGRADED_GRACE_MS
                        eventLocked(
                            "gms_recovery_stabilization_grace_consumed",
                            "generation=$generation deadlineElapsed=" +
                                campaign.stabilizationGraceDeadlineElapsed
                        )
                    }
                    eventLocked(
                        "gms_recovery_stabilization_degraded",
                        "generation=$generation frozen=$anyFrozen ports=${probe.establishedPorts.sorted()}"
                    )
                }
            }
        }

        val flappingEscalation =
            RecoveryCampaignPolicy.shouldEscalateGmsTransportFlapping(
                nowElapsed = now,
                phaseStartedElapsed = campaign.phaseStartedElapsed,
                longestContinuousTransportMs =
                    campaign.phaseLongestContinuousTransportMs,
                collapseWindowStartedElapsed =
                    campaign.collapseWindowStartedElapsed,
                collapseCountInWindow = campaign.collapseCountInWindow
            )
        if (flappingEscalation && !campaign.flappingEscalationReported) {
            campaign.flappingEscalationReported = true
            eventLocked(
                "gms_recovery_transport_flapping_escalation",
                "generation=$generation phaseElapsedMs=" +
                    (now - campaign.phaseStartedElapsed).coerceAtLeast(0L) +
                    " longestContinuousMs=${campaign.phaseLongestContinuousTransportMs} " +
                    "collapseWindowCount=${campaign.collapseCountInWindow}"
            )
        }
        val hardResetRequested = campaign.hardResetRequested
        val vendorFamily = currentVendorFamilyLocked()
        val maxResetCount = RecoveryCampaignPolicy.gmsMaxResetsPerCampaign(vendorFamily)
        val inCampaignOutageDeadlineReached =
            RecoveryCampaignPolicy.shouldOverrideGmsInCampaignRecoveryGuards(
                vendorFamily = vendorFamily,
                nowElapsed = now,
                resetCount = campaign.resetCount,
                maxResetCount = maxResetCount,
                lastResetElapsed = campaign.lastResetElapsed,
                transportMissingSinceElapsed =
                    campaign.postResetTransportMissingSinceElapsed,
                transportHealthy = probe.healthy,
                lastPostResetRefreezeElapsed = campaign.lastRefreezeElapsed
            )
        val stabilizationGraceActive =
            !hardResetRequested &&
                !flappingEscalation &&
                !inCampaignOutageDeadlineReached &&
                campaign.stabilizationGraceDeadlineElapsed > now

        val resetEligible =
            if (hardResetRequested) {
                now >= campaign.nextResetEligibleElapsed
            } else {
                !campaign.anchorOnlyAfterForceStopGate &&
                    (
                        now >= campaign.nextResetEligibleElapsed ||
                            flappingEscalation ||
                            inCampaignOutageDeadlineReached
                    )
            }
        val resetPolicyAllows =
            hardResetRequested ||
                RecoveryCampaignPolicy.shouldResetGmsAgain(
                    nowElapsed = now,
                    lastResetElapsed = campaign.lastResetElapsed,
                    resetCount = campaign.resetCount,
                    anyGmsFrozen = anyFrozen,
                    transportHealthy = probe.healthy,
                    maxResetCount = maxResetCount
                )
        val vendorDefenseOwnsRecovery = vendorDefenseOwnsGmsRecoveryLocked(now)
        if (
            inCampaignOutageDeadlineReached &&
            campaign.inCampaignOutageDeadlineReportedForResetCount != campaign.resetCount
        ) {
            campaign.inCampaignOutageDeadlineReportedForResetCount = campaign.resetCount
            val outageAgeMs =
                (now - campaign.postResetTransportMissingSinceElapsed).coerceAtLeast(0L)
            val resetAgeMs = (now - campaign.lastResetElapsed).coerceAtLeast(0L)
            val refreezeAgeMs = (now - campaign.lastRefreezeElapsed).coerceAtLeast(0L)
            eventLocked(
                "gms_recovery_in_campaign_outage_deadline_reached",
                "generation=$generation reset=${campaign.resetCount} " +
                    "outageAgeMs=$outageAgeMs resetAgeMs=$resetAgeMs " +
                    "refreezeAgeMs=$refreezeAgeMs owner=$vendorDefenseOwnsRecovery " +
                    "phase=$vendorBridgeDefenseOwnershipPhase " +
                    "nextResetEligibleElapsed=${campaign.nextResetEligibleElapsed}"
            )
        }
        if (
            vendorDefenseOwnsRecovery &&
            !probe.healthy &&
            !inCampaignOutageDeadlineReached
        ) {
            if (campaign.resetWaitReportedForCount != Int.MIN_VALUE) {
                campaign.resetWaitReportedForCount = Int.MIN_VALUE
                vendorBridgeDefenseRecoverySuppressionCount += 1
                eventLocked(
                    "gms_recovery_reset_suppressed_vendor_defense_owner",
                    "generation=$generation seq=$vendorBridgeDefenseOwnershipSequence " +
                        "phase=$vendorBridgeDefenseOwnershipPhase frozen=$anyFrozen " +
                        "ports=${probe.establishedPorts.sorted()}"
                )
            }
        } else if (
            !stabilizationGraceActive &&
            resetEligible &&
            resetPolicyAllows
        ) {
            if (vendorDefenseOwnsRecovery && inCampaignOutageDeadlineReached) {
                eventLocked(
                    "gms_recovery_reset_vendor_defense_owner_overridden_outage_deadline",
                    "generation=$generation reset=${campaign.resetCount} " +
                        "seq=$vendorBridgeDefenseOwnershipSequence " +
                        "phase=$vendorBridgeDefenseOwnershipPhase"
                )
            }
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
            if (campaign.anchorOnlyAfterForceStopGate) {
                eventLocked(
                    "gms_recovery_anchor_only_active",
                    "generation=$generation resetCount=${campaign.resetCount} " +
                        "reason=force_stop_gate_closed frozen=$anyFrozen " +
                        "ports=${probe.establishedPorts.sorted()}"
                )
            } else {
                val waitRemaining = if (campaign.nextResetEligibleElapsed == Long.MAX_VALUE) {
                    -1L
                } else {
                    (campaign.nextResetEligibleElapsed - now).coerceAtLeast(0L)
                }
                eventLocked(
                    "gms_recovery_reset_deferred_preconnection_lease",
                    "generation=$generation resetCount=${campaign.resetCount} " +
                        "waitRemainingMs=$waitRemaining frozen=$anyFrozen " +
                        "ports=${probe.establishedPorts.sorted()}"
                )
            }
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
        val vendorFamily = currentVendorFamilyLocked()
        val nextResetCount = campaign.resetCount + 1
        val hardResetRequested = campaign.hardResetRequested
        val hardResetBudgetAvailable =
            campaign.forceStopCount <
                RecoveryCampaignPolicy.gmsMaxForceStopsPerCampaign(vendorFamily)
        val normalForceStopRequested =
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = vendorFamily,
                resetCount = nextResetCount,
                refreezeCount = campaign.refreezeCount,
                forceStopCount = campaign.forceStopCount
            )
        val campaignForceStopAllowed =
            normalForceStopRequested ||
                (hardResetRequested && hardResetBudgetAvailable)
        val forceStopDecision = RecoveryCampaignPolicy.decideGmsForceStop(
            nowElapsed = now,
            forceStopHistory = gmsForceStopHistory.toList()
        )

        if (hardResetRequested && !hardResetBudgetAvailable) {
            campaign.hardResetRequested = false
            eventLocked(
                "gms_recovery_hard_reset_rejected",
                "generation=${campaign.generation} reason=campaign_force_stop_budget " +
                    "forceStops=${campaign.forceStopCount}/" +
                    RecoveryCampaignPolicy.gmsMaxForceStopsPerCampaign(vendorFamily)
            )
            return
        } else if (hardResetRequested && !forceStopDecision.allowed) {
            campaign.nextResetEligibleElapsed =
                now + GMS_MCS_HARD_RESET_RETRY_MS
            campaign.resetWaitReportedForCount = -1
            eventLocked(
                "gms_recovery_hard_reset_deferred",
                "generation=${campaign.generation} reason=${forceStopDecision.reason} " +
                    "retryAt=${campaign.nextResetEligibleElapsed}"
            )
            return
        }

        if (
            !hardResetRequested &&
            RecoveryCampaignPolicy.shouldHoldAnchorInsteadOfFallbackStopApp(
                vendorFamily = vendorFamily,
                nextResetCount = nextResetCount,
                forceStopWanted = campaignForceStopAllowed,
                forceStopAllowed = forceStopDecision.allowed
            )
        ) {
            campaign.nextResetEligibleElapsed = Long.MAX_VALUE
            campaign.resetWaitReportedForCount = campaign.resetCount
            campaign.anchorOnlyAfterForceStopGate = true
            ensureGmsPreconnectionLeaseLocked(
                campaign = campaign,
                nowElapsed = now,
                reason = "force_stop_gate_anchor_only",
                force = true
            )
            eventLocked(
                "gms_recovery_reset_deferred_force_stop_gate_anchor_only",
                "generation=${campaign.generation} nextReset=$nextResetCount " +
                    "vendor=$vendorFamily reason=${forceStopDecision.reason} " +
                    "ports=${transportProbe.establishedPorts.sorted()} " +
                    "frozen=${frozenProcesses.map { "${it.name}:${it.pid}" }}"
            )
            return
        }

        campaign.resetCount = nextResetCount
        campaign.lastResetElapsed = now
        campaign.phaseStartedElapsed = now
        campaign.phaseLongestContinuousTransportMs = 0L
        campaign.collapseWindowStartedElapsed = 0L
        campaign.collapseCountInWindow = 0
        campaign.stabilizationGraceConsumed = false
        campaign.stabilizationGraceDeadlineElapsed = 0L
        campaign.flappingEscalationReported = false
        campaign.anchorOnlyAfterForceStopGate = false
        campaign.postResetTransportMissingSinceElapsed = 0L
        campaign.inCampaignOutageDeadlineReportedForResetCount = -1
        gmsFastThawAwaitingReconnectSinceElapsed = 0L
        gmsRecoveryResetCount += 1
        val oldPids = currentProcesses.mapTo(linkedSetOf()) { it.pid }
        val details = mutableListOf<String>()
        val forceStopAllowed = campaignForceStopAllowed && forceStopDecision.allowed
        val forceStopBlockReason = when {
            hardResetRequested && forceStopAllowed -> campaign.hardResetReason
            !campaignForceStopAllowed -> "campaign_escalation_or_budget"
            !forceStopDecision.allowed -> forceStopDecision.reason
            else -> forceStopDecision.reason
        }
        eventLocked(
            "gms_recovery_reset_started",
            "generation=${campaign.generation} reset=${campaign.resetCount} oldPids=${oldPids.sorted()} " +
                "frozen=${frozenProcesses.map { "${it.name}:${it.pid}" }} " +
                "transport=${transportProbe.healthy} vendor=$vendorFamily " +
                "strategy=${when {
                    hardResetRequested && forceStopAllowed -> "hard_reset_force_stop"
                    forceStopAllowed -> "force_stop_budget_available"
                    else -> "non_destructive_or_stop_app"
                }} " +
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
            val destructiveReason = if (hardResetRequested) {
                campaign.hardResetReason.ifBlank { "mcs_kick_exhausted" }
            } else {
                "bounded_vendor_or_refreeze_escalation"
            }
            if (hardResetRequested) {
                campaign.hardResetRequested = false
                eventLocked(
                    "gms_recovery_hard_reset_consumed",
                    "generation=${campaign.generation} reset=${campaign.resetCount} " +
                        "reason=$destructiveReason requestedElapsed=" +
                        campaign.hardResetRequestedElapsed
                )
            }
            remainingOldPids = forceStopAndUnstopGmsLocked(
                campaign = campaign,
                oldPids = oldPids,
                details = details,
                reason = destructiveReason
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
        scheduleGmsFastThaw(signalElapsed = resetCompletedElapsed)
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
        reason: String,
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

    private fun resetGmsImportanceFenceCampaignMetricsLocked(
        processes: List<GuardianProcess>
    ) {
        gmsImportanceFenceProbeCount = 0L
        gmsImportanceFenceStatusFailureCount = 0L
        gmsImportanceFenceActive = false
        gmsImportanceFenceAnyConnected = false
        gmsImportanceFenceBothConnected = false
        gmsImportanceFenceGeneration = 0L
        gmsImportanceFenceMainState = "waiting"
        gmsImportanceFenceMainAction = ""
        gmsImportanceFenceMainComponent = ""
        gmsImportanceFencePersistentState = "waiting"
        gmsImportanceFencePersistentAction = ""
        gmsImportanceFencePersistentComponent = ""
        gmsImportanceFenceLastProbeElapsed = 0L
        gmsImportanceFenceFreezeWhileAnyConnectedCount = 0L
        gmsImportanceFenceFreezeWhileBothConnectedCount = 0L
        gmsImportanceFenceUid = packageUid(GMS_PACKAGE) ?: -1
        gmsImportanceFenceUidState = "unobserved"
        gmsImportanceFenceLastRawStatus = ""
        gmsImportanceFenceBaselineOomAdj.clear()
        gmsImportanceFenceLastOomAdj.clear()
        gmsImportanceFenceLowestOomAdj.clear()
        gmsImportanceFenceHighestOomAdj.clear()
        readGmsOomScoreAdjLocked(processes).forEach { (name, value) ->
            gmsImportanceFenceBaselineOomAdj[name] = value
            gmsImportanceFenceLastOomAdj[name] = value
            gmsImportanceFenceLowestOomAdj[name] = value
            gmsImportanceFenceHighestOomAdj[name] = value
        }
        gmsImportanceFenceUidState = readGmsUidStateLocked()
        eventLocked(
            "gms_importance_fence_baseline_captured",
            "oomAdj=$gmsImportanceFenceBaselineOomAdj uidState=$gmsImportanceFenceUidState"
        )
    }

    private fun probeGmsImportanceFenceLocked(now: Long, force: Boolean) {
        if (
            !force &&
            gmsImportanceFenceLastProbeElapsed > 0L &&
            now >= gmsImportanceFenceLastProbeElapsed &&
            now - gmsImportanceFenceLastProbeElapsed <
                GMS_IMPORTANCE_FENCE_STATUS_PROBE_INTERVAL_MS
        ) {
            return
        }
        val result = runner.run(
            "am", "broadcast", "--user", "0", "--receiver-foreground",
            "-a", GMS_IMPORTANCE_FENCE_STATUS_ACTION,
            "-n", GMS_BINDER_PULSE_COMPONENT,
            timeoutMs = GMS_IMPORTANCE_FENCE_STATUS_TIMEOUT_MS
        )
        gmsImportanceFenceProbeCount += 1
        gmsImportanceFenceLastProbeElapsed = now
        actionCount += 1
        if (!result.success) commandFailureCount += 1
        val parsed = GmsImportanceFenceStatusParser.parseCommandOutput(result.stdout)
        if (parsed == null) {
            gmsImportanceFenceStatusFailureCount += 1
            eventLocked(
                "gms_importance_fence_status_unavailable",
                result.summary()
            )
            return
        }
        val changed =
            parsed.active != gmsImportanceFenceActive ||
                parsed.anyConnected != gmsImportanceFenceAnyConnected ||
                parsed.bothConnected != gmsImportanceFenceBothConnected ||
                parsed.mainState != gmsImportanceFenceMainState ||
                parsed.persistentState != gmsImportanceFencePersistentState ||
                parsed.mainComponent != gmsImportanceFenceMainComponent ||
                parsed.persistentComponent != gmsImportanceFencePersistentComponent
        gmsImportanceFenceActive = parsed.active
        gmsImportanceFenceAnyConnected = parsed.anyConnected
        gmsImportanceFenceBothConnected = parsed.bothConnected
        gmsImportanceFenceGeneration = parsed.generation
        gmsImportanceFenceMainState = parsed.mainState
        gmsImportanceFenceMainAction = parsed.mainAction
        gmsImportanceFenceMainComponent = parsed.mainComponent
        gmsImportanceFencePersistentState = parsed.persistentState
        gmsImportanceFencePersistentAction = parsed.persistentAction
        gmsImportanceFencePersistentComponent = parsed.persistentComponent
        gmsImportanceFenceLastRawStatus = parsed.rawData.take(2_000)

        val importance = if (parsed.active || force) {
            readGmsOomScoreAdjLocked(listGmsProcessesLocked())
        } else {
            emptyMap()
        }
        importance.forEach { (name, value) ->
            gmsImportanceFenceLastOomAdj[name] = value
            gmsImportanceFenceLowestOomAdj[name] = minOf(
                gmsImportanceFenceLowestOomAdj[name] ?: value,
                value
            )
            gmsImportanceFenceHighestOomAdj[name] = maxOf(
                gmsImportanceFenceHighestOomAdj[name] ?: value,
                value
            )
        }
        if (parsed.active || force) {
            gmsImportanceFenceUidState = readGmsUidStateLocked()
        }
        if (changed || force) {
            eventLocked(
                "gms_importance_fence_status",
                "active=${parsed.active} anyConnected=${parsed.anyConnected} " +
                    "bothConnected=${parsed.bothConnected} " +
                    "main=${parsed.mainState}:${parsed.mainComponent} " +
                    "persistent=${parsed.persistentState}:${parsed.persistentComponent} " +
                    "oomAdj=$importance uidState=$gmsImportanceFenceUidState"
            )
        }
    }

    private fun readGmsOomScoreAdjLocked(
        processes: List<GuardianProcess>
    ): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        processes.forEach { process ->
            val value = runner.run(
                "cat", "/proc/${process.pid}/oom_score_adj",
                timeoutMs = GMS_IMPORTANCE_FENCE_PROC_TIMEOUT_MS
            ).stdout.trim().toIntOrNull()
            if (value != null) result[process.name] = value
        }
        return result
    }

    private fun readGmsUidStateLocked(): String {
        val uid = if (gmsImportanceFenceUid >= 0) {
            gmsImportanceFenceUid
        } else {
            packageUid(GMS_PACKAGE)?.also { gmsImportanceFenceUid = it }
                ?: return "uid_unavailable"
        }
        val result = runner.run(
            "cmd", "activity", "get-uid-state", uid.toString(),
            timeoutMs = GMS_IMPORTANCE_FENCE_PROC_TIMEOUT_MS
        )
        return if (result.success) {
            result.stdout.replace('\n', ' ').trim().take(240).ifBlank { "empty" }
        } else {
            "unobservable:${result.summary(160)}"
        }
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
            gmsCooldownBypassMissingEpisodeElapsed = 0L
            gmsPostSuccessProtectionUntilElapsed =
                now + RecoveryCampaignPolicy.GMS_VIVO_POST_SUCCESS_PROTECTION_MS
            eventLocked(
                "gms_recovery_post_success_protection_started",
                "generation=$generation untilElapsed=$gmsPostSuccessProtectionUntilElapsed " +
                    "durationMs=${RecoveryCampaignPolicy.GMS_VIVO_POST_SUCCESS_PROTECTION_MS}"
            )
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
        val recentFastFreezerCutoff =
            (now - RecoveryCampaignPolicy.GMS_VIVO_RECENT_FAST_FREEZER_EVIDENCE_WINDOW_MS)
                .coerceAtLeast(0L)
        while (
            gmsVivoFastFreezerEvents.firstOrNull()?.let {
                it < recentFastFreezerCutoff || it > now
            } == true
        ) {
            gmsVivoFastFreezerEvents.removeFirst()
        }
        val historyCutoff = (now - RECOVERY_HISTORY_WINDOW_MS).coerceAtLeast(0L)
        while (gmsRecoveryHistory.firstOrNull()?.let { it < historyCutoff || it > now } == true) {
            gmsRecoveryHistory.removeFirst()
        }
        while (gmsForceStopHistory.firstOrNull()?.let { it < historyCutoff || it > now } == true)
            gmsForceStopHistory.removeFirst()
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
        vendorBridgeOwnerPackageLocked(processName)?.let { ownerPackage ->
            recordVendorBridgeSuppressedUnfreezeLocked(ownerPackage, "kotlin_unfreeze:$processName")
            return notApplicableUnfreezeResult(processName, "delegated_vendor_bridge_owner")
        }
        val sticky = config.stickyUnfreeze && supportsStickyUnfreeze
        if (supportsDirectActivityUnfreeze) {
            val direct = runner.run(
                ActivityManagerUnfreezeCommand.build(
                    processName = processName,
                    sticky = sticky,
                    backend = ActivityManagerUnfreezeCommand.Backend.CMD_ACTIVITY
                ),
                timeoutMs = 8_000L
            )
            if (direct.success) return direct
            commandFailureCount += 1
            eventLocked(
                "direct_activity_unfreeze_fallback",
                "process=$processName result=${direct.summary()}"
            )
        }
        return runner.run(
            ActivityManagerUnfreezeCommand.build(
                processName = processName,
                sticky = sticky,
                backend = ActivityManagerUnfreezeCommand.Backend.AM
            ),
            timeoutMs = 8_000L
        )
    }

    private fun isUnfreezeAccepted(result: GuardianCommandResult): Boolean {
        if (!result.success) return false
        // A configured vendor bridge is the accepted owner of this action.
        // No duplicate command was issued; physical success is still decided by
        // the caller's cgroup verification, never by this delegated marker.
        if (result.stdout.contains("delegated_vendor_bridge_owner")) return true
        val directBackend = result.command.take(2) == listOf("cmd", "activity")
        return directBackend ||
            result.stdout.contains("Unfreezing process", ignoreCase = true) ||
            result.stdout.contains("already unfrozen", ignoreCase = true)
    }

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
        return Regex("""(?:^|\s)userId=(\d+)(?:\s|$)""")
            .find(fallback.stdout)
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun ensureCapabilitiesLocked() {
        if (capabilityChecked) return
        val id = runner.run("id")
        identity = id.stdout.ifBlank { "uid=${Process.myUid()} ${id.stderr}" }.take(300)
        val amHelp = runner.run("am", "help", timeoutMs = 10_000L)
        val activityHelp = runner.run("cmd", "activity", "help", timeoutMs = 10_000L)
        supportsDirectActivityUnfreeze = activityHelp.success &&
            activityHelp.stdout.lineSequence().any { line ->
                line.contains("unfreeze")
            }
        supportsStickyUnfreeze = amHelp.stdout.contains("unfreeze [--sticky]") ||
            amHelp.stdout.contains("unfreeze --sticky") ||
            activityHelp.stdout.contains("unfreeze [--sticky]") ||
            activityHelp.stdout.contains("unfreeze --sticky")
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
            "identity=$identity sticky=$supportsStickyUnfreeze " +
                "directActivityUnfreeze=$supportsDirectActivityUnfreeze stopApp=$supportsStopApp " +
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
                    "cgroup1.state" ->
                        text.equals("FROZEN", ignoreCase = true) ||
                            text.equals("FREEZING", ignoreCase = true)
                    else -> false
                }
                return FreezeEvidence(frozen, "$kind:$text", file, kind)
            }
        }
        return FreezeEvidence(null, "freeze_control_not_visible", null, null)
    }

    private fun canDirectCgroupThawLocked(): Boolean =
        config.rootCgroupThaw && Process.myUid() == 0

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
        val snapshot = statusJsonLocked()
        // Socket clients use this immutable snapshot. Updating it is cheap and
        // must not be coupled to the much slower diagnostic-file write cadence.
        cachedStatusJson = snapshot
        if (
            !force &&
            lastDiagnosticStatusWriteElapsed > 0L &&
            now >= lastDiagnosticStatusWriteElapsed &&
            now - lastDiagnosticStatusWriteElapsed < DIAGNOSTIC_STATUS_INTERVAL_MS
        ) {
            return
        }
        if (!diagnosticStore.writeStatus(snapshot)) {
            diagnosticWriteErrorCount += 1
        } else {
            lastDiagnosticStatusWriteElapsed = now
        }
    }

    private fun statusJsonLocked(): String = JSONObject()
        .put("schema", STATUS_SCHEMA)
        .put("engine", "PrivilegedGuardianEngine")
        .put("snapshotElapsed", SystemClock.elapsedRealtime())
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
        .put("supportsDirectActivityUnfreeze", supportsDirectActivityUnfreeze)
        .put("supportsSecondaryProcessUnfreeze", supportsSecondaryProcessUnfreeze)
        .put("supportsStopApp", supportsStopApp)
        .put("supportsPackageUnstop", supportsPackageUnstop)
        .put("supportsAppHibernation", supportsAppHibernation)
        .put("eventWatcherAlive", eventWatcherAlive)
        .put("eventWatcherMode", eventWatcherMode)
        .put("gmsFreezerFastLane", JSONObject()
            .put("ready", eventFastLaneReady)
            .put("backend", eventFastLaneBackend)
            .put("timeoutSupported", eventFastLaneTimeoutSupported)
            .put("stickyConfigured", eventFastLaneStickyConfigured)
            .put("targetEnabled", eventFastLaneTargetEnabled)
            .put("startCount", eventFastLaneStartCount)
            .put("failureCount", eventFastLaneFailureCount)
            .put("signalCount", eventFastLaneSignalCount)
            .put("immediateAttemptCount", eventFastLaneImmediateAttemptCount)
            .put("immediateAcceptedCount", eventFastLaneImmediateAcceptedCount)
            .put("immediateSkippedCount", eventFastLaneImmediateSkippedCount)
            .put("shieldCompletionCount", eventFastLaneShieldCompletionCount)
            .put("shieldCommandCount", eventFastLaneShieldCommandCount)
            .put("shieldAcceptedCount", eventFastLaneShieldAcceptedCount)
            .put("frozenPollCount", eventFastLaneFrozenPollCount)
            .put("verifiedThawCount", eventFastLaneVerifiedThawCount)
            .put("blindReassertCount", eventFastLaneBlindReassertCount)
            .put("exhaustedCount", eventFastLaneExhaustedCount)
            .put("lastEpisode", eventFastLaneLastEpisode)
            .put("lastState", eventFastLaneLastState)
            .put("lastSignalElapsed", eventFastLaneLastSignalElapsed)
            .put("lastImmediateLatencyMs", eventFastLaneLastImmediateLatencyMs)
            .put("maxImmediateLatencyMs", eventFastLaneMaxImmediateLatencyMs)
            .put("postRecoveryCount", eventFastLanePostRecoveryCount)
            .put("lastPostRecoveryElapsed", eventFastLaneLastPostRecoveryElapsed)
        )
        .put("gmsVendorFreezeBridge", JSONObject()
            .put("targetEnabled", isVendorBridgeTargetedLocked())
            .put("targets", JSONArray(vendorBridgeTargetsLocked().toList().sorted()))
            .put("vendorFamily", vendorBridgeFamilyLocked().name)
            .put("alive", vendorBridgeAlive)
            .put("ready", vendorBridgeReady)
            .put("strategy", GmsVendorDefensePolicy.STRATEGY)
            .put("timeoutSupported", vendorBridgeTimeoutSupported)
            .put("stickyConfigured", vendorBridgeStickyConfigured)
            .put("startCount", vendorBridgeStartCount)
            .put("failureCount", vendorBridgeFailureCount)
            .put("heartbeatCount", vendorBridgeHeartbeatCount)
            .put("lastHeartbeatElapsed", vendorBridgeLastHeartbeatElapsed)
            .put("heartbeatAgeMs", if (vendorBridgeLastHeartbeatElapsed > 0L) {
                (SystemClock.elapsedRealtime() - vendorBridgeLastHeartbeatElapsed).coerceAtLeast(0L)
            } else {
                -1L
            })
            .put("shellPid", vendorBridgeShellPid)
            .put("parentStartTimeTicks", vendorBridgeParentStartTimeTicks)
            .put("shellStartTimeTicks", vendorBridgeShellStartTimeTicks)
            .put("heartbeatPath", vendorBridgeHeartbeatPath)
            .put("heartbeatFileValid", vendorBridgeHeartbeatFileValid)
            .put("heartbeatFileAgeMs", vendorBridgeHeartbeatFileAgeMs)
            .put("ownerLeaseValid", vendorBridgeOwnerLeaseValid)
            .put("ownerPath", vendorBridgeOwnerPath)
            .put("commandOwnerPolicy", "vendor_bridge")
            .put("ownershipPolicyActive", running && isVendorBridgeTargetedLocked())
            .put("gmsFallbackSuppressed", vendorBridgeOwnsGmsCommandsLocked())
            .put("frozenCount", vendorBridgeFrozenCount)
            .put("recoveryAttemptCount", vendorBridgeRecoveryAttemptCount)
            .put("plainRecoveryCount", vendorBridgePlainRecoveryCount)
            .put("adoptReleaseCount", vendorBridgeAdoptReleaseCount)
            .put("adoptUnconfirmedCount", vendorBridgeAdoptUnconfirmedCount)
            .put("frameworkLedgerRetryCount", vendorBridgeFrameworkLedgerRetryCount)
            .put("releaseRetryCount", vendorBridgeReleaseRetryCount)
            .put("adoptObservedCount", vendorBridgeAdoptObservedCount)
            .put("groupRecoveryCount", vendorBridgeGroupRecoveryCount)
            .put("suppressedInternalUnfreezeCount", vendorBridgeSuppressedInternalUnfreezeCount)
            .put("lockEscalationCount", vendorBridgeLockEscalationCount)
            .put("defenseEpisodeCount", vendorBridgeDefenseEpisodeCount)
            .put("defensePulseCount", vendorBridgeDefensePulseCount)
            .put("defenseStableCount", vendorBridgeDefenseStableCount)
            .put("defenseRefreezeCount", vendorBridgeDefenseRefreezeCount)
            .put("defenseEscalationCount", vendorBridgeDefenseEscalationCount)
            .put("defensePidChangeCount", vendorBridgeDefensePidChangeCount)
            .put("defenseExpiredCount", vendorBridgeDefenseExpiredCount)
            .put("defenseLastSequence", vendorBridgeDefenseLastSequence)
            .put("defenseLastPhase", vendorBridgeDefenseLastPhase)
            .put("defenseLastElapsedMs", vendorBridgeDefenseLastElapsedMs)
            .put("defenseLastStableMs", vendorBridgeDefenseLastStableMs)
            .put("defenseLastAttempts", vendorBridgeDefenseLastAttempts)
            .put("defenseLastCommandCount", vendorBridgeDefenseLastCommandCount)
            .put("defenseCommandCount", vendorBridgeDefenseCommandCount)
            .put("defenseStableRequiredMs", GmsVendorDefensePolicy.STABLE_REQUIRED_MILLISECONDS)
            .put("defenseStableHoldMs", GmsVendorDefensePolicy.STABLE_HOLD_MILLISECONDS)
            .put("defensePulseRequiredMs", GmsVendorDefensePolicy.PULSE_REQUIRED_MILLISECONDS)
            .put("defenseRecoveryOwnerActive", vendorDefenseOwnsGmsRecoveryLocked())
            .put("defenseRecoveryOwnerSequence", vendorBridgeDefenseOwnershipSequence)
            .put("defenseRecoveryOwnerPhase", vendorBridgeDefenseOwnershipPhase)
            .put(
                "defenseRecoveryOwnerRemainingMs",
                (vendorBridgeDefenseOwnershipUntilElapsed - SystemClock.elapsedRealtime())
                    .coerceAtLeast(0L)
            )
            .put("defenseRecoverySuppressionCount", vendorBridgeDefenseRecoverySuppressionCount)
            .put("legacyGuardianDetectedCount", legacyGuardianDetectedCount)
            .put("legacyGuardianStoppedCount", legacyGuardianStoppedCount)
            .put("legacyGuardianLastResult", legacyGuardianLastResult)
            .put("legacyGuardianLastAuditElapsed", lastLegacyGuardianAuditElapsed)
            .put("lastCommandDetail", vendorBridgeLastCommandDetail)
            .put("verifiedRecoveryCount", vendorBridgeVerifiedRecoveryCount)
            .put("failedRecoveryCount", vendorBridgeFailedRecoveryCount)
            .put("vendorLockCount", vendorBridgeLockCount)
            .put("lastLockElapsed", vendorBridgeLastLockElapsed)
            .put("lastTarget", vendorBridgeLastTarget)
            .put("lastMode", vendorBridgeLastMode)
            .put("lastState", vendorBridgeLastState)
            .put("lastPid", vendorBridgeLastPid)
            .put("mainPid", vendorBridgeMainPid)
            .put("mainState", vendorBridgeMainState)
            .put("persistentPid", vendorBridgePersistentPid)
            .put("persistentState", vendorBridgePersistentState)
            .put("whatsappPid", vendorBridgeWhatsappPid)
            .put("whatsappState", vendorBridgeWhatsappState)
            .put("signalPid", vendorBridgeSignalPid)
            .put("signalState", vendorBridgeSignalState)
            .put("lastRecoveryLatencyMs", vendorBridgeLastRecoveryLatencyMs)
            .put("maxRecoveryLatencyMs", vendorBridgeMaxRecoveryLatencyMs)
        )
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
        .put("rootCgroupThawConfigured", config.rootCgroupThaw)
        .put("rootCgroupThawEffective", canDirectCgroupThawLocked())
        .put("gmsRecoveryEnabled", config.gmsRecoveryEnabled)
        .put("vendorEmergencyRecoveryEnabled", config.vendorEmergencyRecoveryEnabled)
        .put("gmsRecoveryInProgress", gmsRecoveryInProgress)
        .put("gmsManualRecoveryQueued", gmsManualRecoveryQueued)
        .put("gmsManualRecoveryRequestId", gmsManualRecoveryRequestId)
        .put("gmsManualRecoveryState", gmsManualRecoveryState)
        .put("gmsFreezeEventsInWindow", gmsFreezeEvents.size)
        .put("gmsVivoFastFreezerEventsInWindow", gmsVivoFastFreezerEvents.size)
        .put("gmsTransportMissingEpisodePids", JSONArray(gmsTransportMissingEpisodePids.toList().sorted()))
        .put("gmsRecentFreezerEvidenceLatchCount", gmsRecentFreezerEvidenceLatchCount)
        .put("gmsRecentFreezerEvidenceLatchedMissingEpisodeElapsed",
            gmsRecentFreezerEvidenceLatchedMissingEpisodeElapsed)
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
        .put("gmsFastThaw", JSONObject()
            .put("workerActive", gmsFastThawWorkerActive.get())
            .put("attemptCount", gmsFastThawAttemptCount)
            .put("successCount", gmsFastThawSuccessCount)
            .put("coalescedCount", gmsFastThawCoalescedCount)
            .put("retryCount", gmsFastThawRetryCount)
            .put("finalVerifiedCount", gmsFastThawFinalVerifiedCount)
            .put("lastLatencyMs", gmsFastThawLastLatencyMs)
            .put("maxLatencyMs", gmsFastThawMaxLatencyMs)
            .put("deadlineOverrunCount", gmsFastThawDeadlineOverrunCount)
            .put("maxDeadlineOverrunMs", gmsFastThawMaxDeadlineOverrunMs)
            .put("lastCompletedElapsed", gmsFastThawLastCompletedElapsed)
            .put("awaitingReconnectSinceElapsed", gmsFastThawAwaitingReconnectSinceElapsed)
            .put("postThawReconnectCount", gmsFastThawPostReconnectCount)
            .put("postThawReconnectLatencyMs", gmsFastThawLastPostReconnectLatencyMs)
            .put("maxPostThawReconnectLatencyMs", gmsFastThawMaxPostReconnectLatencyMs)
        )
        .put("gmsTransportCollapseCount", gmsTransportCollapseCount)
        .put("gmsTransportLongestContinuousMs", gmsTransportLongestContinuousMs)
        .put("gmsImportanceFence", JSONObject()
            .put("probeCount", gmsImportanceFenceProbeCount)
            .put("statusFailureCount", gmsImportanceFenceStatusFailureCount)
            .put("active", gmsImportanceFenceActive)
            .put("anyConnected", gmsImportanceFenceAnyConnected)
            .put("bothConnected", gmsImportanceFenceBothConnected)
            .put("generation", gmsImportanceFenceGeneration)
            .put("mainState", gmsImportanceFenceMainState)
            .put("mainAction", gmsImportanceFenceMainAction)
            .put("mainComponent", gmsImportanceFenceMainComponent)
            .put("persistentState", gmsImportanceFencePersistentState)
            .put("persistentAction", gmsImportanceFencePersistentAction)
            .put("persistentComponent", gmsImportanceFencePersistentComponent)
            .put("lastProbeElapsed", gmsImportanceFenceLastProbeElapsed)
            .put(
                "freezeWhileAnyConnectedCount",
                gmsImportanceFenceFreezeWhileAnyConnectedCount
            )
            .put(
                "freezeWhileBothConnectedCount",
                gmsImportanceFenceFreezeWhileBothConnectedCount
            )
            .put("uid", gmsImportanceFenceUid)
            .put("uidState", gmsImportanceFenceUidState)
            .put("baselineOomScoreAdj", JSONObject().apply {
                gmsImportanceFenceBaselineOomAdj.forEach { (name, value) ->
                    put(name, value)
                }
            })
            .put("lastOomScoreAdj", JSONObject().apply {
                gmsImportanceFenceLastOomAdj.forEach { (name, value) ->
                    put(name, value)
                }
            })
            .put("lowestOomScoreAdj", JSONObject().apply {
                gmsImportanceFenceLowestOomAdj.forEach { (name, value) ->
                    put(name, value)
                }
            })
            .put("highestOomScoreAdj", JSONObject().apply {
                gmsImportanceFenceHighestOomAdj.forEach { (name, value) ->
                    put(name, value)
                }
            })
            .put("rawStatus", gmsImportanceFenceLastRawStatus)
        )
        .put("lastGmsRecoveryElapsed", lastGmsRecoveryElapsed)
        .put("lastGmsRecoveryCompletedElapsed", lastGmsRecoveryCompletedElapsed)
        .put("gmsConsecutiveCampaignFailures", gmsConsecutiveCampaignFailures)
        .put("gmsCooldownBypassCount", gmsCooldownBypassCount)
        .put("gmsCooldownBypassMissingEpisodeElapsed", gmsCooldownBypassMissingEpisodeElapsed)
        .put("gmsPostSuccessProtectionUntilElapsed", gmsPostSuccessProtectionUntilElapsed)
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
        var stabilizationGraceConsumed: Boolean = false,
        var stabilizationGraceDeadlineElapsed: Long = 0L,
        var stabilizationLeaseRequestedElapsed: Long = 0L,
        var transportCollapseCount: Int = 0,
        var longestContinuousTransportMs: Long = 0L,
        var phaseStartedElapsed: Long = startedElapsed,
        var phaseLongestContinuousTransportMs: Long = 0L,
        var collapseWindowStartedElapsed: Long = 0L,
        var collapseCountInWindow: Int = 0,
        var flappingEscalationReported: Boolean = false,
        var anchorOnlyAfterForceStopGate: Boolean = false,
        var hardResetRequested: Boolean = false,
        var hardResetReason: String = "",
        var hardResetRequestedElapsed: Long = 0L,
        var postResetTransportMissingSinceElapsed: Long = 0L,
        var lastRefreezeElapsed: Long = 0L,
        var inCampaignOutageDeadlineReportedForResetCount: Int = -1,
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
            .put("stabilizationGraceConsumed", stabilizationGraceConsumed)
            .put("stabilizationGraceDeadlineElapsed", stabilizationGraceDeadlineElapsed)
            .put("stabilizationLeaseRequestedElapsed", stabilizationLeaseRequestedElapsed)
            .put("transportCollapseCount", transportCollapseCount)
            .put("longestContinuousTransportMs", longestContinuousTransportMs)
            .put("phaseStartedElapsed", phaseStartedElapsed)
            .put("phaseLongestContinuousTransportMs", phaseLongestContinuousTransportMs)
            .put("collapseWindowStartedElapsed", collapseWindowStartedElapsed)
            .put("collapseCountInWindow", collapseCountInWindow)
            .put("flappingEscalationReported", flappingEscalationReported)
            .put("anchorOnlyAfterForceStopGate", anchorOnlyAfterForceStopGate)
            .put("hardResetRequested", hardResetRequested)
            .put("hardResetReason", hardResetReason)
            .put("hardResetRequestedElapsed", hardResetRequestedElapsed)
            .put("postResetTransportMissingSinceElapsed", postResetTransportMissingSinceElapsed)
            .put("lastRefreezeElapsed", lastRefreezeElapsed)
            .put(
                "inCampaignOutageDeadlineReportedForResetCount",
                inCampaignOutageDeadlineReportedForResetCount
            )
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

    private data class FastThawPassResult(
        val success: Boolean,
        val durationMs: Long
    )

    private data class FreezeEvidence(
        val frozen: Boolean?,
        val detail: String,
        val controlFile: File?,
        val controlKind: String?
    )

    companion object {
        private const val STATUS_SCHEMA = 25
        private const val GMS_PACKAGE = "com.google.android.gms"
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val SIGNAL_PACKAGE = "org.thoughtcrime.securesms"
        private const val EVENT_WATCHER_RESTART_DELAY_MS = 1_000L
        private const val FAST_LANE_READY_TIMEOUT_MS = 3_000L
        private const val VENDOR_BRIDGE_RESTART_DELAY_MS = 1_000L
        private const val VENDOR_BRIDGE_READY_TIMEOUT_MS = 3_000L
        private const val VENDOR_BRIDGE_STALE_HEARTBEAT_MS = 30_000L
        private const val VENDOR_BRIDGE_LOCK_ESCALATION_DELAY_MS = 750L
        private const val VENDOR_DEFENSE_OWNER_ACTIVE_TTL_MS = 45_000L
        private const val VENDOR_DEFENSE_OWNER_GRACE_MS = 5_000L
        private const val VENDOR_DEFENSE_PENDING_OWNER_TTL_MS = 2_000L
        private const val VENDOR_DEFENSE_ESCALATION_HANDOFF_MS = 500L
        private const val DELEGATED_UNFREEZE_LOG_INTERVAL_MS = 5_000L
        private const val LEGACY_GUARDIAN_PID_PATH = "/data/local/tmp/luonnotar2/guardian.pid"
        private const val LEGACY_GUARDIAN_AUDIT_INTERVAL_MS = 60_000L
        private const val FAST_LANE_POST_RECOVERY_COOLDOWN_MS = 3_000L
        private const val MAX_FAST_LANE_SEQUENCE_HISTORY = 128
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
        private const val GMS_GCM_RECONNECT_ACTION =
            "com.google.android.intent.action.GCM_RECONNECT"

        private const val GMS_MCS_KICK_MAX_ROUNDS = 3
        private const val GMS_MCS_KICK_GUARD_MS = 3_000L
        private const val GMS_MCS_KICK_POLL_MS = 250L
        private const val GMS_MCS_KICK_BROADCAST_TIMEOUT_MS = 1_500L
        private const val GMS_MCS_KICK_SOCKET_TIMEOUT_MS = 1_000L
        private const val GMS_MCS_KICK_THAW_TIMEOUT_MS = 700L
        private const val GMS_BINDER_PULSE_ACTION =
            "com.yubegreen.luonnotar.action.ADB_GMS_BINDER_PULSE_TEST"
        private const val GMS_BINDER_STABILIZATION_ACTION =
            "com.yubegreen.luonnotar.action.ADB_GMS_BINDER_STABILIZATION_LEASE"
        private const val GMS_BINDER_PULSE_COMPONENT =
            "com.yubegreen.luonnotar/.receiver.AdbGmsBinderPulseReceiver"
        private const val GMS_IMPORTANCE_FENCE_STATUS_ACTION =
            "com.yubegreen.luonnotar.action.ADB_GMS_IMPORTANCE_FENCE_STATUS"
        private const val GMS_IMPORTANCE_FENCE_STATUS_PROBE_INTERVAL_MS = 2_000L
        private const val GMS_IMPORTANCE_FENCE_STATUS_TIMEOUT_MS = 3_000L
        private const val GMS_IMPORTANCE_FENCE_PROC_TIMEOUT_MS = 1_000L
        private const val GMS_FREEZE_EVENT_DEBOUNCE_MS = 5_000L
        private const val GMS_FAST_THAW_BURST_DURATION_MS = 1_500L
        private const val GMS_FAST_THAW_COMMAND_TIMEOUT_MS = 700L
        private const val GMS_FAST_THAW_PROCESS_SCAN_TIMEOUT_MS = 350L
        private const val GMS_FAST_THAW_VERIFY_DELAY_MS = 80L
        private const val GMS_FAST_THAW_MAX_PASSES = 3
        private val GMS_FAST_THAW_RETRY_DELAYS_MS = longArrayOf(200L, 500L)
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
        private const val GMS_MCS_KICK_EXHAUSTION_THRESHOLD = 2
        private const val GMS_MCS_KICK_EMERGENCY_COOLDOWN_MS = 30_000L
        private const val GMS_MCS_HARD_RESET_RETRY_MS = 15_000L
        private val DELIVERY_PACKAGE_TARGETS =
            setOf("com.whatsapp", "com.whatsapp.w4b")
        private val BACKGROUND_LAUNCH_WAKE_TARGETS =
            setOf("com.whatsapp", "com.whatsapp.w4b", "com.termux")
        private val MANAGED_LIVENESS_PACKAGES = setOf("com.termux")
        private val SAFE_CGROUP_PATH = Regex("^/[A-Za-z0-9_./:@-]+$")

    }
}
