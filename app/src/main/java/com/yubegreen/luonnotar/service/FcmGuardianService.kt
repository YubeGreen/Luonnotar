package com.yubegreen.luonnotar.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.ActionActivity
import com.yubegreen.luonnotar.MainActivity
import com.yubegreen.luonnotar.R
import com.yubegreen.luonnotar.monitor.GuardianState
import com.yubegreen.luonnotar.monitor.GuardianStateReducer
import com.yubegreen.luonnotar.monitor.KeepaliveCadencePolicy
import com.yubegreen.luonnotar.monitor.KeepaliveAlertPolicy
import com.yubegreen.luonnotar.monitor.AdbVpnEvidencePolicy
import com.yubegreen.luonnotar.monitor.MtalkPathProbe
import com.yubegreen.luonnotar.monitor.NetworkEvidence
import com.yubegreen.luonnotar.monitor.NetworkStateMonitor
import com.yubegreen.luonnotar.monitor.SupportedVpnProvider
import com.yubegreen.luonnotar.monitor.TargetRoutingPolicy
import com.yubegreen.luonnotar.monitor.TargetRoutingSnapshot
import com.yubegreen.luonnotar.monitor.TailscaleDnsProbe
import com.yubegreen.luonnotar.monitor.TailscaleGuardianStatePolicy
import com.yubegreen.luonnotar.monitor.TailscaleNetworkEvidence
import com.yubegreen.luonnotar.monitor.TailscaleNetworkMonitor
import com.yubegreen.luonnotar.monitor.VpnConnectivityMonitor
import com.yubegreen.luonnotar.monitor.VpnDnsProbe
import com.yubegreen.luonnotar.monitor.VpnEvidence
import com.yubegreen.luonnotar.monitor.VpnGuardianStatePolicy
import com.yubegreen.luonnotar.monitor.VpnOnlyRoutingPolicy
import com.yubegreen.luonnotar.monitor.VpnRouteState
import com.yubegreen.luonnotar.monitor.WifiUnderlayHistory
import com.yubegreen.luonnotar.monitor.WifiUnderlayLockPolicy
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.notification.ControlledPushDeliveryState
import com.yubegreen.luonnotar.notification.PushTestDeliveryPolicy
import com.yubegreen.luonnotar.notification.GmsBinderAnchorCoordinator
import com.yubegreen.luonnotar.notification.GmsBinderPulseCoordinator
import com.yubegreen.luonnotar.notification.GmsImportanceFenceCoordinator
import com.yubegreen.luonnotar.notification.NotificationListenerRecoveryCoordinator
import com.yubegreen.luonnotar.receiver.GuardianCleanupReceiver
import com.yubegreen.luonnotar.receiver.LabAlarmScheduler
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import java.net.HttpURLConnection
import java.net.DatagramSocket
import java.net.Socket
import java.net.URL
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import kotlin.math.max

class FcmGuardianService : Service() {
    companion object {
        const val ACTION_START = "com.yubegreen.luonnotar.action.START"
        const val ACTION_PAUSE = "com.yubegreen.luonnotar.action.PAUSE"
        const val ACTION_RESUME = "com.yubegreen.luonnotar.action.RESUME"
        const val ACTION_STOP = "com.yubegreen.luonnotar.action.STOP"
        const val ACTION_CHECK = "com.yubegreen.luonnotar.action.CHECK"
        const val ACTION_RECOVER = "com.yubegreen.luonnotar.action.RECOVER"
        const val ACTION_PROFILE_CHANGED =
            "com.yubegreen.luonnotar.action.PROFILE_CHANGED"
        const val ACTION_GMS_BINDER_ANCHOR_CHANGED =
            "com.yubegreen.luonnotar.action.GMS_BINDER_ANCHOR_CHANGED"
        const val ACTION_GMS_BINDER_ANCHOR_RETRY =
            "com.yubegreen.luonnotar.action.GMS_BINDER_ANCHOR_RETRY"
        const val ACTION_GMS_BINDER_PULSE_TEST =
            "com.yubegreen.luonnotar.action.GMS_BINDER_PULSE_TEST"
        const val ACTION_GMS_BINDER_STABILIZATION_LEASE =
            "com.yubegreen.luonnotar.action.GMS_BINDER_STABILIZATION_LEASE"
        const val EXTRA_START_REASON = "start_reason"
        const val KEEPALIVE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        private const val TICK_SECONDS = 5L
        private const val LOCK_CHECK_MS = 30_000L
        private const val RECOVERY_PROBE_COOLDOWN_MS = 15_000L
        private const val PROBE_TIMEOUT_MS =
            GuardianProfilePolicy.WHOLE_PROBE_DEADLINE_MS
        private const val PROBE_HARD_TIMEOUT_MS = 45_000L
        private const val ALERT_COOLDOWN_MS = 10 * 60_000L
        private const val LEGACY_LAST_RTT_KEY = "last_rtt_ms"
        private val PROCESS_ACTUAL_PROBE_PERMIT = ActualProbePermit()
        private val SCREEN_ACTIONS = setOf(
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT
        )

        @Volatile
        private var activeInstance: WeakReference<FcmGuardianService>? = null

        enum class InProcessReloadResult {
            APPLIED,
            DEFERRED,
            FAILED
        }

        enum class InProcessProbeResult {
            DISPATCHED,
            SERVICE_NOT_RUNNING,
            GUARDIAN_INACTIVE,
            VPN_NOT_USABLE,
            INVALID_PLAN,
            FAILED
        }

        fun requestInProcessRuntimeConfigReload(
            reason: String
        ): InProcessReloadResult {
            val service = activeInstance?.get()
                ?: return InProcessReloadResult.DEFERRED
            return if (service.applyRuntimeConfigSynchronously(reason)) {
                InProcessReloadResult.APPLIED
            } else {
                InProcessReloadResult.FAILED
            }
        }

        fun requestInProcessProbe(plan: String): InProcessProbeResult {
            val service = activeInstance?.get()
                ?: return InProcessProbeResult.SERVICE_NOT_RUNNING
            return service.dispatchProbeSynchronously(plan)
        }
    }

    @Volatile private var scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var probeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var scheduled: ScheduledFuture<*>? = null
    @Volatile private var startupProbeFuture: ScheduledFuture<*>? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var scopedWakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var vpnMonitor: VpnConnectivityMonitor
    private lateinit var networkMonitor: NetworkStateMonitor
    private lateinit var tailscaleMonitor: TailscaleNetworkMonitor
    private lateinit var persistentNetworkLease: PersistentNetworkLease
    private lateinit var persistentHeartbeatSocketLease: PersistentHeartbeatSocketLease
    @Volatile private var vpnEvidence = VpnEvidence(false, false, null, -1)
    @Volatile private var networkEvidence = NetworkEvidence(false, false, "NONE", false)
    @Volatile
    private var tailscaleEvidence = TailscaleNetworkEvidence()
    private var startedElapsed = 0L
    private var passiveWindowStartedElapsed = 0L
    private var activeMonitoringStartedElapsed = 0L
    @Volatile private var heartbeatElapsed = 0L
    private var lastHeartbeatPersistedElapsed = 0L
    private var lastTickElapsedMemory = 0L
    private var lastTickUptimeMemory = 0L
    private var maxTimerDriftMemory = 0L
    @Volatile private var quietWindowUntilElapsed = 0L
    @Volatile private var pendingNetworkEvidenceFlush = false
    @Volatile private var pendingTailscaleEvidenceFlush = false
    private var startupStabilizationUntilElapsed = 0L
    private val startupProbeScheduled = AtomicBoolean(false)
    private var lastExpectedTickElapsed = 0L
    private var lastLockCheckElapsed = 0L
    private val lastKeepaliveAttemptElapsed = AtomicLong(0L)
    private val lastVpnDnsAttemptElapsed = AtomicLong(0L)
    private val lastTailscaleDnsAttemptElapsed = AtomicLong(0L)
    private val lastMtalkAttemptElapsed = AtomicLong(0L)
    private val recoveryEpoch = AtomicLong(0L)
    private val probeRequestGate = ProbeRequestGate(recoveryEpoch.get())
    private val probeLifecycleLock = Any()
    private val destroyed = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val latestStartId = AtomicInteger(0)
    private val authoritativeStopPending = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processProbeRetryScheduled = AtomicBoolean(false)
    private val hardProbeRestartRequested = AtomicBoolean(false)
    @Volatile private var pendingForcedPlan = ProbePlan.DNS
    private var serviceGeneration = 0L
    @Volatile private var activeConnection: HttpsURLConnection? = null
    @Volatile private var activeDnsSocket: DatagramSocket? = null
    @Volatile
    private var activeDnsCancellation: CancellationSignal? = null
    @Volatile private var activeMtalkSocket: Socket? = null
    @Volatile private var activeProbeStage = ProbeStage.IDLE
    @Volatile private var activeProbeStageStartedElapsed = 0L
    private var notificationLargeIcon: Bitmap? = null
    private var lastNotificationFingerprint = ""
    private var lastNotificationPostedElapsed = 0L
    private var wifiUnderlayHistory = WifiUnderlayHistory()
    private var lastUnderlayDiagnosticElapsed = 0L
    private var lastDrainingActualDiagnosticElapsed = 0L
    private var screenReceiverRegistered = false
    private enum class ProbePlan {
        DNS,
        HTTPS,
        MTALK,
        MANUAL_DIAGNOSTIC
    }

    private enum class ProbeTriggerClass {
        PERIODIC,
        SCREEN_EVENT,
        STARTUP,
        STRUCTURAL_RECOVERY,
        USER_ACTION
    }

    private enum class ProbeStage {
        IDLE,
        VPN_DNS,
        TAILSCALE_DNS,
        MTALK,
        HTTPS
    }

    private val screenEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action !in SCREEN_ACTIONS || destroyed.get() || stopping.get()) return
            val timelineEvent = when (action) {
                Intent.ACTION_SCREEN_OFF -> "screen_off"
                Intent.ACTION_SCREEN_ON -> "screen_on"
                Intent.ACTION_USER_PRESENT -> "user_present"
                else -> return
            }
            LogManager.timeline(this@FcmGuardianService, timelineEvent)
            val prefs = LuonnotarPreferences.deviceProtected(this@FcmGuardianService)
            val runtime = GuardianProfilePolicy.read(
                this@FcmGuardianService,
                prefs
            )
            if (
                action == Intent.ACTION_SCREEN_OFF &&
                runtime.cooperative
            ) {
                quietWindowUntilElapsed =
                    SystemClock.elapsedRealtime() +
                        GuardianProfilePolicy.SCREEN_OFF_QUIET_WINDOW_MS
                LogManager.timeline(
                    this@FcmGuardianService,
                    "iqoo_quiet_window_started",
                    mapOf(
                        "quietUntilElapsed" to quietWindowUntilElapsed,
                        "durationMs" to
                            GuardianProfilePolicy.SCREEN_OFF_QUIET_WINDOW_MS
                    )
                )
            } else if (action == Intent.ACTION_SCREEN_OFF) {
                quietWindowUntilElapsed = 0L
            } else if (
                action == Intent.ACTION_SCREEN_ON ||
                action == Intent.ACTION_USER_PRESENT
            ) {
                quietWindowUntilElapsed = 0L
                GmsBinderPulseCoordinator.stop(
                    this@FcmGuardianService,
                    "screen_interactive"
                )
                flushPendingEvidence()
            }
            reconcileCpuLockPolicy(
                reason = timelineEvent,
                screenInteractiveOverride =
                    action != Intent.ACTION_SCREEN_OFF
            )
            if (
                runtime.experiments.screenEventProbe &&
                isActivelyEnabled(prefs)
            ) {
                requestRecoveryProbe(
                    reason = "${timelineEvent}_experiment",
                    plan = ProbePlan.DNS,
                    triggerClass = ProbeTriggerClass.SCREEN_EVENT
                )
            }
        }
    }
    private val bootId: String by lazy {
        runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrDefault("unavailable")
    }

    override fun onCreate() {
        super.onCreate()
        persistentNetworkLease = PersistentNetworkLease(this)
        persistentHeartbeatSocketLease = PersistentHeartbeatSocketLease(this)
        startedElapsed = SystemClock.elapsedRealtime()
        startupStabilizationUntilElapsed =
            startedElapsed + GuardianProfilePolicy.STARTUP_STABILIZATION_MS
        showForeground(GuardianState.STARTING, "初始化系统证据…")
        val initialPrefs = LuonnotarPreferences.deviceProtected(this)
        GuardianProfilePolicy.ensureDefaults(this, initialPrefs)
        if (GuardianProfilePolicy.read(this, initialPrefs).passive) {
            passiveWindowStartedElapsed = startedElapsed
        }
        if (
            GuardianProfilePolicy.read(this, initialPrefs).cooperative &&
            !getSystemService(PowerManager::class.java).isInteractive
        ) {
            quietWindowUntilElapsed =
                startedElapsed + GuardianProfilePolicy.SCREEN_OFF_QUIET_WINDOW_MS
        }
        initialPrefs.edit()
            .remove(LEGACY_LAST_RTT_KEY)
            .remove(LuonnotarPreferences.KEY_PROBE_STARTED_ELAPSED)
            .remove(LuonnotarPreferences.KEY_PROBE_DEADLINE_ELAPSED)
            .remove(LuonnotarPreferences.KEY_SERVICE_DESTROYED_ELAPSED)
            .putBoolean(LuonnotarPreferences.KEY_PROBE_IN_FLIGHT, false)
            .putString(LuonnotarPreferences.KEY_STATE, GuardianState.STARTING.name)
            .apply()
        bumpProcessSequence()
        val generationPrefs = LuonnotarPreferences.deviceProtected(this)
        serviceGeneration =
            generationPrefs.getLong(LuonnotarPreferences.KEY_SERVICE_GENERATION, 0L) + 1L
        generationPrefs.edit()
            .putLong(LuonnotarPreferences.KEY_SERVICE_GENERATION, serviceGeneration)
            .apply()
        wifiUnderlayHistory = restoreWifiUnderlayHistory(generationPrefs)
        createLocks()
        registerScreenEventReceiver()
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val active = prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) &&
            !prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        if (active) {
            activeMonitoringStartedElapsed = startedElapsed
        }
        GmsBinderAnchorCoordinator.reconcile(this, active)
        observeVpnPolicySettings()
        vpnMonitor = VpnConnectivityMonitor(this) vpnCallback@{
            if (!isCurrentServiceInstance()) return@vpnCallback
            val previous = vpnEvidence
            vpnEvidence = it
            if (::tailscaleMonitor.isInitialized) {
                tailscaleMonitor.setExpectedTailscaleHandle(
                    tailscaleExpectedHandle(it)
                )
            }
            persistNetworkEvidence()
            persistTailscaleEvidence()
            if (!isCurrentServiceInstance()) return@vpnCallback
            val handleChanged =
                it.present && previous.present && it.networkHandle != previous.networkHandle
            val recovered = it.present && !previous.present
            val validationRegained =
                it.present && it.validated && !previous.validated
            val routeChanged =
                it.internetRouted != previous.internetRouted ||
                    it.ipv4DefaultRoute != previous.ipv4DefaultRoute ||
                    it.ipv6DefaultRoute != previous.ipv6DefaultRoute
            val providerChanged = it.providerPackage != previous.providerPackage
            val sessionChanged =
                it.sessionFingerprint.isNotBlank() &&
                    previous.sessionFingerprint.isNotBlank() &&
                    it.sessionFingerprint != previous.sessionFingerprint
            if (handleChanged || sessionChanged) {
                disconnectActiveConnection()
            }
            if (
                it.present != previous.present ||
                it.networkHandle != previous.networkHandle ||
                it.validated != previous.validated ||
                routeChanged ||
                providerChanged ||
                sessionChanged
            ) {
                LogManager.timeline(
                    this,
                    "vpn_network_changed",
                    mapOf(
                        "previousNetworkHandle" to previous.networkHandle,
                        "currentNetworkHandle" to it.networkHandle,
                        "previousVpnPresent" to previous.present,
                        "currentVpnPresent" to it.present,
                        "previousValidated" to previous.validated,
                        "currentValidated" to it.validated,
                        "previousInternetRouted" to previous.internetRouted,
                        "currentInternetRouted" to it.internetRouted,
                        "previousProvider" to previous.providerPackage,
                        "currentProvider" to it.providerPackage,
                        "previousSessionFingerprint" to
                            previous.sessionFingerprint,
                        "sessionFingerprint" to it.sessionFingerprint,
                        "sessionChanged" to sessionChanged
                    )
                )
            }
            if (
                it.usable &&
                !startupStabilizing() &&
                (
                    recovered ||
                        handleChanged ||
                        validationRegained ||
                        routeChanged ||
                        providerChanged ||
                        sessionChanged
                    )
            ) {
                flushPendingEvidence()
                requestRecoveryProbe(
                    when {
                        handleChanged -> "vpn_handle_changed"
                        recovered -> "vpn_recovered"
                        routeChanged -> "vpn_default_route_changed"
                        providerChanged -> "vpn_provider_changed"
                        sessionChanged -> "vpn_session_rebuilt"
                        else -> "vpn_validated"
                    },
                    plan = ProbePlan.DNS,
                    triggerClass = ProbeTriggerClass.STRUCTURAL_RECOVERY
                )
            }
        }
        networkMonitor = NetworkStateMonitor(this) networkCallback@{
            if (!isCurrentServiceInstance()) return@networkCallback
            val previous = networkEvidence
            networkEvidence = it
            persistNetworkEvidence()
            if (!isCurrentServiceInstance()) return@networkCallback
            reconcileWifiLock()
            reconcilePersistentTransports()
            val handleChanged = previous.networkHandle != it.networkHandle
            if (handleChanged) {
                disconnectActiveConnection()
            }
            if (handleChanged) {
                LogManager.timeline(
                    this,
                    "default_network_handle_changed",
                    mapOf(
                        "previousNetworkHandle" to previous.networkHandle,
                        "currentNetworkHandle" to it.networkHandle
                    )
                )
            }
            if (previous.validated != it.validated) {
                LogManager.timeline(
                    this,
                    "network_validation_changed",
                    mapOf(
                        "previousValidated" to previous.validated,
                        "currentValidated" to it.validated
                    )
                )
            }
            if (
                previous.transport != it.transport ||
                previous.underlaySource != it.underlaySource
            ) {
                LogManager.timeline(
                    this,
                    "wifi_underlay_changed",
                    mapOf(
                        "previousUnderlay" to previous.transport,
                        "currentUnderlay" to it.transport,
                        "previousUnderlaySource" to previous.underlaySource,
                        "underlaySource" to it.underlaySource
                    )
                )
                if (isCurrentTailscaleNetwork() && !startupStabilizing()) {
                    requestRecoveryProbe(
                        "tailscale_underlay_transport_changed",
                        plan = ProbePlan.DNS,
                        triggerClass = ProbeTriggerClass.STRUCTURAL_RECOVERY
                    )
                }
            }
            if (
                handleChanged &&
                vpnEvidence.present &&
                !startupStabilizing()
            ) {
                requestRecoveryProbe(
                    "default_network_handle_changed",
                    plan = ProbePlan.DNS,
                    triggerClass = ProbeTriggerClass.STRUCTURAL_RECOVERY
                )
            }
            if (
                it.validated &&
                !previous.validated &&
                vpnEvidence.present &&
                !startupStabilizing()
            ) {
                requestRecoveryProbe(
                    "network_validated",
                    plan = ProbePlan.DNS,
                    triggerClass = ProbeTriggerClass.STRUCTURAL_RECOVERY
                )
            }
        }
        tailscaleMonitor = TailscaleNetworkMonitor(this) tailscaleCallback@{
            if (!isCurrentServiceInstance()) return@tailscaleCallback
            val previous = tailscaleEvidence
            tailscaleEvidence = it
            persistTailscaleEvidence()
            if (!isCurrentServiceInstance()) return@tailscaleCallback
            reconcilePersistentTransports()
            val handleChanged =
                it.present &&
                    previous.present &&
                    it.networkHandle != previous.networkHandle
            val blockedRecovered =
                previous.blockedKnown &&
                    previous.blocked &&
                    it.blockedKnown &&
                    !it.blocked
            val suspensionRecovered =
                previous.suspended && !it.suspended
            val underlayChanged =
                it.present &&
                    previous.present &&
                    it.underlyingNetworkHandles !=
                    previous.underlyingNetworkHandles
            val becameUsable = it.usable && !previous.usable
            if (handleChanged) {
                disconnectActiveConnection()
            }
            if (
                it.usable &&
                !startupStabilizing() &&
                (
                    handleChanged ||
                        blockedRecovered ||
                        suspensionRecovered ||
                        underlayChanged ||
                        becameUsable
                    )
            ) {
                flushPendingEvidence()
                requestRecoveryProbe(
                    when {
                        handleChanged -> "tailscale_handle_changed"
                        blockedRecovered -> "tailscale_unblocked"
                        suspensionRecovered -> "tailscale_not_suspended"
                        underlayChanged -> "tailscale_underlay_changed"
                        else -> "tailscale_became_usable"
                    },
                    plan = ProbePlan.DNS,
                    triggerClass = ProbeTriggerClass.STRUCTURAL_RECOVERY
                )
            }
            updateStateAndAlerts()
            updateNotification()
        }
        runCatching {
            tailscaleMonitor.setExpectedTailscaleHandle(
                tailscaleExpectedHandle(vpnEvidence)
            )
            vpnMonitor.start()
            networkMonitor.start()
            tailscaleMonitor.start()
        }.onFailure {
            runCatching { vpnMonitor.stop() }
            runCatching { networkMonitor.stop() }
            runCatching { tailscaleMonitor.stop() }
            unregisterScreenEventReceiver()
            releaseLocks("monitor_start_failed")
            ServiceCompat.stopForeground(
                this,
                ServiceCompat.STOP_FOREGROUND_REMOVE
            )
            releaseNotificationLargeIcon()
            scheduler.shutdownNow()
            probeExecutor.shutdownNow()
            LogManager.event(
                this,
                "guardian_monitor_start_failed",
                mapOf("error" to it.toString())
            )
            throw it
        }
        vpnEvidence = vpnMonitor.current()
        networkEvidence = networkMonitor.current()
        tailscaleEvidence = tailscaleMonitor.current()
        persistNetworkEvidence()
        persistTailscaleEvidence()
        reconcileCpuLockPolicy("service_create")
        reconcileWifiLock()
        if (active) scheduleTicks()
        scheduleStartupProbe()
        reconcilePersistentTransports()
        activeInstance = WeakReference(this)
        LogManager.event(this, "guardian_service_created")
    }

    private fun applyRuntimeConfigSynchronously(reason: String): Boolean =
        runOnMainThreadSynchronously {
            applyRuntimeConfigChange(reason)
        }

    private fun dispatchProbeSynchronously(planName: String): InProcessProbeResult {
        val normalized = planName.trim().uppercase()
        val plan = when (normalized) {
            "DNS" -> ProbePlan.DNS
            "HTTPS" -> ProbePlan.HTTPS
            "MTALK" -> ProbePlan.MTALK
            "ALL", "MANUAL_DIAGNOSTIC" -> ProbePlan.MANUAL_DIAGNOSTIC
            else -> return InProcessProbeResult.INVALID_PLAN
        }
        if (!isActivelyEnabled()) return InProcessProbeResult.GUARDIAN_INACTIVE
        if (!vpnEvidence.usable) return InProcessProbeResult.VPN_NOT_USABLE
        val accepted = runOnMainThreadSynchronously {
            val inFlightBefore = probeRequestGate.snapshot().anyInFlight
            requestRecoveryProbe(
                reason = "adb_provider_probe_now_${normalized.lowercase()}",
                force = true,
                plan = plan,
                quietWindowBypass = true,
                triggerClass = ProbeTriggerClass.USER_ACTION
            )
            inFlightBefore || probeRequestGate.snapshot().anyInFlight
        }
        return if (accepted) {
            InProcessProbeResult.DISPATCHED
        } else {
            InProcessProbeResult.FAILED
        }
    }

    private fun runOnMainThreadSynchronously(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrDefault(false)
        }
        val result = AtomicReference(false)
        val latch = CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                result.set(runCatching(block).getOrDefault(false))
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return false
        return runCatching {
            latch.await(3L, TimeUnit.SECONDS) && result.get()
        }.getOrDefault(false)
    }

    private fun applyRuntimeConfigChange(reason: String): Boolean {
        if (destroyed.get() || stopping.get()) return false
        val prefs = LuonnotarPreferences.deviceProtected(this)
        if (
            !prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) ||
            prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        ) return false
        releaseLocks("profile_changed")
        val runtime = runtimeSettings()
        passiveWindowStartedElapsed = if (runtime.passive) {
            SystemClock.elapsedRealtime()
        } else {
            0L
        }
        quietWindowUntilElapsed =
            if (
                runtime.cooperative &&
                !getSystemService(PowerManager::class.java).isInteractive
            ) {
                SystemClock.elapsedRealtime() +
                    GuardianProfilePolicy.SCREEN_OFF_QUIET_WINDOW_MS
            } else {
                0L
            }
        reconcileCpuLockPolicy("profile_changed")
        reconcileWifiLock()
        reconcilePersistentTransports()
        GmsBinderAnchorCoordinator.reconcile(this, isActivelyEnabled())
        synchronized(probeLifecycleLock) {
            scheduled?.cancel(true)
            scheduled = null
        }
        scheduleTicks()
        if (runtime.passive) {
            LabAlarmScheduler.cancel(this)
            sendBroadcast(
                Intent(
                    this,
                    GuardianCleanupReceiver::class.java
                ).setAction(
                    GuardianCleanupReceiver.ACTION_CANCEL_PAUSED
                )
            )
        }
        LogManager.timeline(
            this,
            "guardian_profile_applied",
            mapOf(
                "profile" to runtimeSettings().profile.name,
                "applyReason" to reason
            )
        )
        LogManager.event(
            this,
            "guardian_runtime_config_reloaded_in_process",
            mapOf("reason" to reason)
        )
        updateNotification()
        return true
    }

    private fun reconcilePersistentTransports() {
        if (
            !::persistentNetworkLease.isInitialized ||
            !::persistentHeartbeatSocketLease.isInitialized
        ) return
        val active = isActivelyEnabled()
        val experiments = runtimeSettings().experiments
        val connectivity =
            getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val vpnNetwork =
            if (
                active &&
                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_VPN
                ) == true
            ) {
                activeNetwork
            } else {
                null
            }
        persistentNetworkLease.reconcile(
            active && experiments.persistentNetworkLease
        )
        persistentHeartbeatSocketLease.reconcile(
            active && experiments.persistentHeartbeatSocket,
            vpnNetwork
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId.set(startId)
        val reason = intent?.getStringExtra(EXTRA_START_REASON)
            ?: if (intent == null) "START_STICKY_REDELIVERY" else "unspecified"
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val action = intent?.action ?: ACTION_START
        if (action != ACTION_STOP) {
            showForeground(
                GuardianState.STARTING,
                "正在确认权威守护状态…"
            )
        }
        if (
            action != ACTION_STOP &&
            !prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
        ) {
            stopping.set(true)
            prefs.edit()
                .putInt(LuonnotarPreferences.KEY_PID, 0)
                .remove(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
                .putString(LuonnotarPreferences.KEY_STATE, GuardianState.DISABLED.name)
                .commit()
            LogManager.event(
                this,
                "guardian_start_ignored_disabled",
                mapOf("reason" to reason, "action" to action, "nullIntent" to (intent == null))
            )
            quiesceGuardianExecution("disabled_start_ignored")
            stopServiceIfStillLatest(startId, "disabled_start_ignored")
            return START_NOT_STICKY
        }
        if (
            prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false) &&
            action != ACTION_RESUME &&
            action != ACTION_STOP
        ) {
            prefs.edit()
                .putInt(LuonnotarPreferences.KEY_PID, 0)
                .remove(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
                .putString(LuonnotarPreferences.KEY_STATE, GuardianState.PAUSED.name)
                .commit()
            LogManager.event(
                this,
                "guardian_start_ignored_paused",
                mapOf("reason" to reason, "action" to action)
            )
            stopServiceIfStillLatest(startId, "paused_start_ignored")
            return START_NOT_STICKY
        }
        if (action != ACTION_STOP) {
            stopping.set(false)
        }
        when (action) {
            ACTION_STOP -> {
                stopping.set(true)
                disconnectActiveConnection()
                val stopped = prefs.edit()
                    .putBoolean(LuonnotarPreferences.KEY_ENABLED, false)
                    .putBoolean(LuonnotarPreferences.KEY_PAUSED, false)
                    .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
                    .putBoolean(
                        LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                        false
                    )
                    .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
                    .putInt(LuonnotarPreferences.KEY_PID, 0)
                    .remove(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
                    .remove(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED)
                    .remove(LuonnotarPreferences.KEY_LAST_ALERTED_STATE)
                    .remove(LuonnotarPreferences.KEY_LAST_ALERT_ELAPSED)
                    .putString(
                        LuonnotarPreferences.KEY_STATE,
                        GuardianState.DISABLED.name
                    )
                    .putString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "user_stop")
                    .commit()
                if (!stopped) {
                    stopping.set(false)
                    LogManager.event(this, "guardian_stop_commit_failed")
                    persistError("stop:SharedPreferencesCommitFailed")
                    updateNotification()
                    return START_STICKY
                }
                quiesceGuardianExecution("user_stop")
                LogManager.event(this, "guardian_stopped_by_user")
                LabAlarmScheduler.cancel(this)
                cancelTransientNotifications()
                sendBroadcast(
                    Intent(this, GuardianCleanupReceiver::class.java)
                        .setAction(GuardianCleanupReceiver.ACTION_CLEANUP_DISABLED)
                )
                stopServiceIfStillLatest(startId, "user_stop")
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                return if (pauseGuardian(startId)) {
                    START_NOT_STICKY
                } else {
                    START_STICKY
                }
            }
            ACTION_RESUME -> {
                if (!resumeGuardian()) {
                    stopServiceIfStillLatest(startId, "resume_commit_failed")
                    return START_NOT_STICKY
                }
            }
            ACTION_CHECK -> {
                reconcileCpuLockPolicy("manual_check")
                reconcileWifiLock()
                reconcilePersistentTransports()
                GmsBinderAnchorCoordinator.reconcile(this, isActivelyEnabled())
                scheduleTicks()
                submitManualCheck()
            }
            ACTION_RECOVER -> {
                recoverInternalSchedulers(reason)
                GmsBinderAnchorCoordinator.reconcile(this, isActivelyEnabled())
            }
            ACTION_GMS_BINDER_ANCHOR_CHANGED -> {
                GmsBinderAnchorCoordinator.reconcile(this, isActivelyEnabled())
                LogManager.event(this, "gms_binder_anchor_config_reconciled")
            }
            ACTION_GMS_BINDER_ANCHOR_RETRY -> {
                GmsBinderAnchorCoordinator.manualRetry(
                    this,
                    isActivelyEnabled()
                )
                LogManager.event(this, "gms_binder_anchor_manual_retry")
            }
            ACTION_GMS_BINDER_PULSE_TEST -> {
                val active = isActivelyEnabled()
                val started =
                    active && GmsBinderPulseCoordinator.start(this)
                LogManager.event(
                    this,
                    "gms_binder_pulse_test_requested",
                    mapOf(
                        "guardianActive" to active,
                        "started" to started,
                        "durationMs" to
                            GmsBinderPulseCoordinator.TEST_DURATION_MS
                    )
                )
            }
            ACTION_GMS_BINDER_STABILIZATION_LEASE -> {
                val active = isActivelyEnabled()
                val leaseReason = reason.ifBlank { "privileged_gms_recovery" }
                val started = active && GmsBinderPulseCoordinator.startStabilization(
                    this,
                    leaseReason
                )
                val importanceFenceStarted =
                    active && GmsImportanceFenceCoordinator.startOrExtend(
                        this,
                        leaseReason
                    )
                LogManager.event(
                    this,
                    "gms_binder_stabilization_lease_requested",
                    mapOf(
                        "guardianActive" to active,
                        "started" to started,
                        "importanceFenceStarted" to importanceFenceStarted,
                        "durationMs" to
                            GmsBinderPulseCoordinator.STABILIZATION_DURATION_MS
                    )
                )
            }
            ACTION_PROFILE_CHANGED -> {
                applyRuntimeConfigChange(reason)
            }
            else -> {
                prefs.edit()
                    .putString(LuonnotarPreferences.KEY_LAST_START_REASON, reason)
                    .putString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "")
                    .apply()
                if (!prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)) {
                    if (activeMonitoringStartedElapsed == 0L) {
                        activeMonitoringStartedElapsed = SystemClock.elapsedRealtime()
                    }
                    reconcileCpuLockPolicy("start_command")
                    reconcileWifiLock()
                    reconcilePersistentTransports()
                    GmsBinderAnchorCoordinator.reconcile(this, isActivelyEnabled())
                    scheduleTicks()
                }
            }
        }
        LogManager.event(this, "guardian_start_command", mapOf("reason" to reason, "action" to intent?.action))
        updateNotification()
        return if (
            prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false) ||
            runtimeSettings().passive
        ) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    override fun onDestroy() {
        stopping.set(true)
        destroyed.set(true)
        if (activeInstance?.get() === this) activeInstance = null
        if (::persistentNetworkLease.isInitialized) {
            persistentNetworkLease.release("service_destroyed")
        }
        if (::persistentHeartbeatSocketLease.isInitialized) {
            persistentHeartbeatSocketLease.shutdown()
        }
        GmsBinderAnchorCoordinator.stop(this, "service_destroyed")
        GmsBinderPulseCoordinator.stop(this, "service_destroyed")
        GmsImportanceFenceCoordinator.stop(this, "service_destroyed")
        var connectionToDisconnect: HttpsURLConnection? = null
        var dnsSocketToClose: DatagramSocket? = null
        var dnsCancellationToCancel: CancellationSignal? = null
        var mtalkSocketToClose: Socket? = null
        val oldExecutors = synchronized(probeLifecycleLock) {
            probeRequestGate.advanceGeneration(
                recoveryEpoch.incrementAndGet()
            )
            pendingForcedPlan = ProbePlan.DNS
            scheduled?.cancel(true)
            scheduled = null
            startupProbeFuture?.cancel(true)
            startupProbeFuture = null
            startupProbeScheduled.set(false)
            connectionToDisconnect = activeConnection
            activeConnection = null
            dnsSocketToClose = activeDnsSocket
            activeDnsSocket = null
            dnsCancellationToCancel = activeDnsCancellation
            activeDnsCancellation = null
            mtalkSocketToClose = activeMtalkSocket
            activeMtalkSocket = null
            activeProbeStage = ProbeStage.IDLE
            activeProbeStageStartedElapsed = 0L
            processProbeRetryScheduled.set(false)
            scheduler to probeExecutor
        }
        connectionToDisconnect?.disconnect()
        dnsSocketToClose?.close()
        dnsCancellationToCancel?.cancel()
        runCatching { mtalkSocketToClose?.close() }
        if (::vpnMonitor.isInitialized) vpnMonitor.stop()
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        if (::tailscaleMonitor.isInitialized) tailscaleMonitor.stop()
        unregisterScreenEventReceiver()
        releaseLocks("service_destroyed")
        releaseNotificationLargeIcon()
        oldExecutors.first.shutdownNow()
        oldExecutors.second.shutdownNow()
        val schedulerTerminated = runCatching {
            oldExecutors.first.awaitTermination(1L, TimeUnit.SECONDS)
        }.getOrDefault(false)
        val probeExecutorTerminated = runCatching {
            oldExecutors.second.awaitTermination(1L, TimeUnit.SECONDS)
        }.getOrDefault(false)
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val enabled = prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
        val paused = prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        val editor = prefs.edit()
            .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
            .putBoolean(
                LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                false
            )
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
            .putBoolean(LuonnotarPreferences.KEY_PROBE_IN_FLIGHT, false)
            .remove(LuonnotarPreferences.KEY_PROBE_STARTED_ELAPSED)
            .remove(LuonnotarPreferences.KEY_PROBE_DEADLINE_ELAPSED)
            .putLong(
                LuonnotarPreferences.KEY_SERVICE_DESTROYED_ELAPSED,
                SystemClock.elapsedRealtime()
            )
                    .putInt(LuonnotarPreferences.KEY_PID, 0)
                    .remove(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
                    .remove(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED)
                    .remove(LuonnotarPreferences.KEY_LAST_ALERTED_STATE)
                    .remove(LuonnotarPreferences.KEY_LAST_ALERT_ELAPSED)
                    .putString(
                LuonnotarPreferences.KEY_STATE,
                when {
                    !enabled -> GuardianState.DISABLED.name
                    paused -> GuardianState.PAUSED.name
                    else -> GuardianState.STARTING.name
                }
            )
        if (
            prefs.getString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "") !in
            setOf("user_stop", "paused")
        ) {
            editor.putString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "onDestroy")
        }
        editor.commit()
        LogManager.event(
            this,
            "guardian_service_destroyed",
            mapOf(
                "schedulerTerminated" to schedulerTerminated,
                "probeExecutorTerminated" to probeExecutorTerminated
            )
        )
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        LogManager.event(this, "launcher_task_removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerScreenEventReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            SCREEN_ACTIONS.forEach(::addAction)
        }
        ContextCompat.registerReceiver(
            this,
            screenEventReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenEventReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenEventReceiver) }
        screenReceiverRegistered = false
    }

    private fun runtimeSettings(): GuardianRuntimeSettings {
        val prefs = LuonnotarPreferences.deviceProtected(this)
        GuardianProfilePolicy.ensureDefaults(this, prefs)
        return GuardianProfilePolicy.read(this, prefs)
    }

    private fun quietWindowActive(
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): Boolean =
        GuardianProfilePolicy.quietWindowActive(
            nowElapsed,
            quietWindowUntilElapsed
        )

    private fun startupStabilizing(
        nowElapsed: Long = SystemClock.elapsedRealtime()
    ): Boolean =
        nowElapsed < startupStabilizationUntilElapsed

    private fun scheduleStartupProbe() {
        if (!startupProbeScheduled.compareAndSet(false, true)) return
        val delay = (
            startupStabilizationUntilElapsed -
                SystemClock.elapsedRealtime()
            ).coerceAtLeast(0L)
        val expectedEpoch = recoveryEpoch.get()
        startupProbeFuture = runCatching {
            scheduler.schedule({
                if (
                    isEpochCurrent(expectedEpoch) &&
                    isActivelyEnabled() &&
                    vpnEvidence.usable
                ) {
                    requestRecoveryProbe(
                        reason = "startup_stabilized",
                        plan = ProbePlan.DNS,
                        triggerClass = ProbeTriggerClass.STARTUP
                    )
                }
            }, delay, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun restartStartupAggregation() {
        synchronized(probeLifecycleLock) {
            startupProbeFuture?.cancel(true)
            startupProbeFuture = null
            startupProbeScheduled.set(false)
            startupStabilizationUntilElapsed =
                SystemClock.elapsedRealtime() +
                    GuardianProfilePolicy.STARTUP_STABILIZATION_MS
        }
        scheduleStartupProbe()
    }

    private inline fun <T> runProbeStage(
        stage: ProbeStage,
        block: () -> T
    ): T {
        activeProbeStage = stage
        activeProbeStageStartedElapsed = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            if (activeProbeStage == stage) {
                activeProbeStage = ProbeStage.IDLE
                activeProbeStageStartedElapsed = 0L
            }
        }
    }

    private fun submitManualCheck() {
        val submission = synchronized(probeLifecycleLock) {
            recoveryEpoch.get() to scheduler
        }
        runCatching {
            submission.second.execute {
                if (!isEpochCurrent(submission.first)) return@execute
                vpnEvidence = vpnMonitor.current()
                networkEvidence = networkMonitor.current()
                if (!isEpochCurrent(submission.first)) return@execute
                val prefs = LuonnotarPreferences.deviceProtected(this)
                val paused = prefs.getBoolean(
                    LuonnotarPreferences.KEY_PAUSED,
                    false
                )
                if (
                    VpnOnlyRoutingPolicy.maySendHttps(
                        vpnEvidence.present,
                        paused
                    )
                ) {
                    requestRecoveryProbe(
                        reason = "manual_check",
                        force = true,
                        plan = ProbePlan.MANUAL_DIAGNOSTIC,
                        quietWindowBypass = true,
                        triggerClass = ProbeTriggerClass.USER_ACTION
                    )
                } else {
                    LogManager.event(
                        this,
                        "manual_check_blocked",
                        mapOf(
                            "vpn" to vpnEvidence.present,
                            "paused" to paused
                        )
                    )
                }
                if (!isEpochCurrent(submission.first)) return@execute
                updateStateAndAlerts()
                updateNotification()
            }
        }.onFailure {
            if (isEpochCurrent(submission.first)) {
                persistError(
                    "manual_check_submit:" +
                        "${it.javaClass.simpleName}:${it.message}"
                )
                LogManager.event(
                    this,
                    "manual_check_submit_failed",
                    mapOf("error" to it.toString())
                )
            }
        }
    }

    private fun scheduleTicks() {
        synchronized(probeLifecycleLock) {
            if (
                scheduled?.isCancelled == false &&
                scheduled?.isDone == false
            ) return
            lastExpectedTickElapsed = SystemClock.elapsedRealtime()
            val scheduledEpoch = recoveryEpoch.get()
            val executor = scheduler
            val tickSeconds = if (runtimeSettings().cooperative) {
                GuardianPowerPolicy.IQOO_TICK_SECONDS
            } else {
                TICK_SECONDS
            }
            scheduled = executor.scheduleWithFixedDelay({
                runCatching {
                    if (isEpochCurrent(scheduledEpoch)) {
                        tick(scheduledEpoch)
                    }
                }.onFailure {
                    if (isEpochCurrent(scheduledEpoch)) {
                        persistError(
                            "tick:${it.javaClass.simpleName}:${it.message}"
                        )
                        LogManager.event(
                            this,
                            "guardian_tick_failed",
                            mapOf("error" to it.toString())
                        )
                    }
                }
                if (isEpochCurrent(scheduledEpoch)) {
                    lastExpectedTickElapsed =
                        SystemClock.elapsedRealtime() + tickSeconds * 1000
                }
            }, 0, tickSeconds, TimeUnit.SECONDS)
        }
    }

    private fun tick(expectedEpoch: Long) {
        if (!isEpochCurrent(expectedEpoch)) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (
            !quietWindowActive(nowElapsed) &&
            (pendingNetworkEvidenceFlush || pendingTailscaleEvidenceFlush)
        ) {
            flushPendingEvidence()
        }
        val nowUptime = SystemClock.uptimeMillis()
        val drift = max(0L, nowElapsed - lastExpectedTickElapsed)
        val prefs = LuonnotarPreferences.deviceProtected(this)
        if (!prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)) {
            stopForAuthoritativeDisable()
            return
        }
        if (
            runtimeSettings().passive &&
            GuardianPassiveWindowPolicy.shouldClose(
                passiveWindowStartedElapsed,
                nowElapsed
            )
        ) {
            stopForPassiveMode()
            return
        }
        if (prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)) return
        val previousElapsed = lastTickElapsedMemory.takeIf { it > 0L }
            ?: prefs.getLong(
                LuonnotarPreferences.KEY_LAST_TICK_ELAPSED,
                0L
            )
        val previousUptime = lastTickUptimeMemory.takeIf { it > 0L }
            ?: prefs.getLong(
                LuonnotarPreferences.KEY_LAST_TICK_UPTIME,
                0L
            )
        val suspendGap = if (previousElapsed > 0 && previousUptime > 0) {
            (nowElapsed - previousElapsed) - (nowUptime - previousUptime)
        } else 0
        maxTimerDriftMemory = max(
            maxTimerDriftMemory,
            max(
                prefs.getLong(
                    LuonnotarPreferences.KEY_MAX_TIMER_DRIFT,
                    0L
                ),
                drift
            )
        )
        heartbeatElapsed = nowElapsed
        lastTickElapsedMemory = nowElapsed
        lastTickUptimeMemory = nowUptime
        if (
            lastHeartbeatPersistedElapsed == 0L ||
            nowElapsed - lastHeartbeatPersistedElapsed >=
            GuardianProfilePolicy.HEARTBEAT_PERSIST_INTERVAL_MS
        ) {
            lastHeartbeatPersistedElapsed = nowElapsed
            prefs.edit()
                .putLong(
                    LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED,
                    nowElapsed
                )
                .putLong(
                    LuonnotarPreferences.KEY_LAST_TICK_ELAPSED,
                    nowElapsed
                )
                .putLong(
                    LuonnotarPreferences.KEY_LAST_TICK_UPTIME,
                    nowUptime
                )
                .putLong(
                    LuonnotarPreferences.KEY_MAX_TIMER_DRIFT,
                    maxTimerDriftMemory
                )
                .putLong(
                    LuonnotarPreferences.KEY_LAST_TIMER_DRIFT,
                    drift
                )
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT)
                .remove(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT_ELAPSED
                )
                .apply()
        }
        if (!isEpochCurrent(expectedEpoch)) return
        synchronized(probeLifecycleLock) {
            if (
                isEpochCurrent(expectedEpoch) &&
                prefs.getBoolean(
                    LuonnotarPreferences.KEY_RECOVERY_CONFIRMATION_PENDING,
                    false
                ) &&
                prefs.getInt(LuonnotarPreferences.KEY_LAST_HTTP_CODE, -1) ==
                    HttpURLConnection.HTTP_NO_CONTENT &&
                prefs.getInt(
                    LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES,
                    0
                ) == 0 &&
                prefs.getLong(
                    LuonnotarPreferences.KEY_SUCCESS_EVIDENCE_GENERATION,
                    -1L
                ) == serviceGeneration
            ) {
                confirmRecoveryIfPending(
                    prefs,
                    prefs.getLong(
                        LuonnotarPreferences.KEY_LAST_SUCCESS_ELAPSED,
                        0L
                    )
                )
            }
        }
        val probeSnapshot = probeRequestGate.snapshot()
        val processPermitSnapshot = PROCESS_ACTUAL_PROBE_PERMIT.snapshot()
        val probeStarted = probeSnapshot.effectiveStartedElapsed
        val probeAge = if (probeStarted > 0L) {
            nowElapsed - probeStarted
        } else {
            0L
        }
        val watchdogAction = ProbeWatchdogPolicy.action(
            logicalInFlight = probeSnapshot.inFlight,
            actualInFlight = probeSnapshot.actualInFlight,
            ageMs = probeAge,
            softTimeoutMs = PROBE_TIMEOUT_MS,
            hardTimeoutMs = PROBE_HARD_TIMEOUT_MS
        )
        val processPermitAge = if (
            processPermitSnapshot.isHeld &&
            processPermitSnapshot.acquiredElapsed > 0L
        ) {
            nowElapsed - processPermitSnapshot.acquiredElapsed
        } else {
            0L
        }
        if (
            processPermitSnapshot.isHeld &&
            processPermitSnapshot.stage == ProbeStage.HTTPS.name &&
            processPermitAge >= PROBE_HARD_TIMEOUT_MS
        ) {
            if (
                restartKeeperForHardProbeTimeout(
                    probeAgeMs = processPermitAge,
                    expectedPermit = processPermitSnapshot,
                    expectedVpnHandle = vpnEvidence.networkHandle
                )
            ) return
        }
        if (
            probeStarted > 0L &&
            watchdogAction == ProbeWatchdogAction.REBUILD_EXECUTOR
        ) {
            prefs.edit()
                .putString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE,
                    "探测任务超过 ${PROBE_TIMEOUT_MS / 1000} 秒总期限，正在取消并重建执行器"
                )
                .putLong(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                    nowElapsed
                )
                .apply()
            LogManager.event(
                this,
                "probe_watchdog_timeout",
                mapOf("ageMs" to probeAge)
            )
            LogManager.timeline(
                this,
                "probe_pipeline_timeout",
                mapOf(
                    "probeAgeMs" to probeAge,
                    "probeStage" to activeProbeStage.name,
                    "stageAgeMs" to (
                        nowElapsed - activeProbeStageStartedElapsed
                        ).coerceAtLeast(0L)
                )
            )
            recoverInternalSchedulers(
                "probe_watchdog_timeout",
                expectedEpoch
            )
            return
        }
        if (
            processPermitSnapshot.isHeld &&
            !probeSnapshot.inFlight &&
            processPermitAge >= PROBE_TIMEOUT_MS &&
            (
                lastDrainingActualDiagnosticElapsed == 0L ||
                    nowElapsed - lastDrainingActualDiagnosticElapsed >=
                    30_000L
                )
        ) {
            lastDrainingActualDiagnosticElapsed = nowElapsed
            disconnectActiveConnection()
            LogManager.timeline(
                this,
                "probe_old_actual_draining",
                mapOf(
                    "probeAgeMs" to processPermitAge,
                    "probeStage" to activeProbeStage.name,
                    "actualOwnerGeneration" to
                        processPermitSnapshot.owner?.generation
                )
            )
        } else if (!processPermitSnapshot.isHeld) {
            lastDrainingActualDiagnosticElapsed = 0L
        }
        if (!isEpochCurrent(expectedEpoch)) return
        if (drift > 2_000 || suspendGap > 2_000) {
            LogManager.event(
                this,
                "timer_drift",
                mapOf("driftMs" to drift, "cpuSuspendEstimateMs" to suspendGap)
            )
            LogManager.timeline(
                this,
                "guardian_timer_drift",
                mapOf(
                    "timerDriftMs" to drift,
                    "cpuSuspendEstimateMs" to suspendGap
                )
            )
        }
        if (nowElapsed - lastLockCheckElapsed >= LOCK_CHECK_MS) {
            lastLockCheckElapsed = nowElapsed
            vpnEvidence = vpnMonitor.current()
            networkEvidence = networkMonitor.current()
            observeVpnPolicySettings()
            reconcileCpuLockPolicy("periodic_lock_check")
            reconcileWifiLock()
            reconcilePersistentTransports()
            NotificationListenerRecoveryCoordinator.reconcile(this)
        }
        if (!isEpochCurrent(expectedEpoch)) return
        // 1.7.9: repeated 15-second Binder pulses reached GMS successfully
        // without restoring MCS/WhatsApp delivery. Keep the manual laboratory
        // action, but do not spend battery on an automatic production loop.
        val experiments = runtimeSettings().experiments
        val plan = when (
            PeriodicProbeSchedulePolicy.selectMostOverdue(
                nowElapsed = nowElapsed,
                intervalMs = KeepaliveCadencePolicy.NORMAL_INTERVAL_MS,
                lastDnsAttemptElapsed = lastDnsAttemptForCurrentTransport(),
                lastHttpsAttemptElapsed = lastKeepaliveAttemptElapsed.get(),
                lastMtalkAttemptElapsed = lastMtalkAttemptElapsed.get(),
                dnsEnabled = experiments.periodicDns,
                httpsEnabled = experiments.periodicHttps,
                mtalkEnabled = experiments.automaticMtalk
            )
        ) {
            PeriodicProbeKind.DNS -> ProbePlan.DNS
            PeriodicProbeKind.HTTPS -> ProbePlan.HTTPS
            PeriodicProbeKind.MTALK -> ProbePlan.MTALK
            null -> null
        }
        if (
            KeepaliveCadencePolicy.mayProbe(
                vpnPresent = vpnEvidence.usable,
                paused = prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
            ) &&
            plan != null &&
            !quietWindowActive()
        ) {
            if (isEpochCurrent(expectedEpoch)) {
                requestRecoveryProbe(
                    reason = "periodic_${plan.name.lowercase()}",
                    plan = plan
                )
            }
        }
        if (!isEpochCurrent(expectedEpoch)) return
        updateStateAndAlerts()
        updateNotification()
    }

    private fun executeKeepalive(
        ownerToken: ProbeOwnerToken,
        expectedEpoch: Long,
        reason: String,
        plan: ProbePlan
    ) {
        if (!isActivelyEnabled() || expectedEpoch != recoveryEpoch.get()) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return
        val capturedHandle = network.networkHandle
        val capturedSessionFingerprint = vpnEvidence.sessionFingerprint
        val before = connectivity.getNetworkCapabilities(network)
        if (
            before?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true ||
            capturedSessionFingerprint.isBlank()
        ) return
        val tailscaleCurrent =
            tailscaleEvidence.present &&
                tailscaleEvidence.networkHandle == capturedHandle &&
                isCurrentTailscaleNetwork()
        if (
            vpnEvidence.providerPackage ==
                SupportedVpnProvider.TAILSCALE.packageName &&
            tailscaleEvidence.present &&
            !tailscaleCurrent
        ) {
            LogManager.timeline(
                this,
                "tailscale_self_excluded",
                mapOf(
                    "defaultNetworkHandle" to capturedHandle,
                    "tailscaleNetworkHandle" to tailscaleEvidence.networkHandle
                )
            )
        }
        val suiteStarted = SystemClock.elapsedRealtime()
        val suiteDeadline =
            suiteStarted + GuardianProfilePolicy.WHOLE_PROBE_DEADLINE_MS
        if (plan == ProbePlan.DNS || plan == ProbePlan.MANUAL_DIAGNOSTIC) {
            if (tailscaleCurrent) {
                runProbeStage(ProbeStage.TAILSCALE_DNS) {
                    executeTailscaleDnsProbe(
                        network = network,
                        capturedHandle = capturedHandle,
                        ownerToken = ownerToken,
                        expectedEpoch = expectedEpoch,
                        reason = reason,
                        capturedSessionFingerprint =
                            capturedSessionFingerprint
                    )
                }
            } else {
                runProbeStage(ProbeStage.VPN_DNS) {
                    executeVpnDnsProbe(
                        network = network,
                        capturedHandle = capturedHandle,
                        ownerToken = ownerToken,
                        expectedEpoch = expectedEpoch,
                        reason = reason,
                        capturedSessionFingerprint =
                            capturedSessionFingerprint
                    )
                }
            }
            if (plan == ProbePlan.DNS) return
        }
        if (
            plan == ProbePlan.MTALK ||
            plan == ProbePlan.MANUAL_DIAGNOSTIC
        ) {
            if (SystemClock.elapsedRealtime() >= suiteDeadline) return
            runProbeStage(ProbeStage.MTALK) {
                executeMtalkPathProbe(
                    network = network,
                    capturedHandle = capturedHandle,
                    ownerToken = ownerToken,
                    expectedEpoch = expectedEpoch,
                    reason = reason,
                    capturedSessionFingerprint =
                        capturedSessionFingerprint
                )
            }
            if (plan == ProbePlan.MTALK) return
        }
        if (
            plan != ProbePlan.HTTPS &&
            plan != ProbePlan.MANUAL_DIAGNOSTIC
        ) return
        if (SystemClock.elapsedRealtime() >= suiteDeadline) return
        if (!PROCESS_ACTUAL_PROBE_PERMIT.updateStage(
                ownerToken,
                ProbeStage.HTTPS.name
            )
        ) return
        val start = SystemClock.elapsedRealtime()
        val mayStart = synchronized(probeLifecycleLock) {
            if (
                !probeRequestGate.owns(ownerToken) ||
                expectedEpoch != recoveryEpoch.get() ||
                !isActivelyEnabled() ||
                !probeSessionIsCurrent(
                    connectivity,
                    capturedHandle,
                    capturedSessionFingerprint
                )
            ) {
                false
            } else {
                lastKeepaliveAttemptElapsed.set(start)
                true
            }
        }
        if (!mayStart) return
        LogManager.timeline(
            this,
            "https_probe_started",
            mapOf(
                "probeReason" to reason,
                "capturedNetworkHandle" to capturedHandle
            )
        )
        var connection: HttpsURLConnection? = null
        runProbeStage(ProbeStage.HTTPS) {
        try {
            if (!isActivelyEnabled() || expectedEpoch != recoveryEpoch.get()) return
            val freshUrl = "$KEEPALIVE_URL?t=$start&g=$serviceGeneration"
            connection = network.openConnection(URL(freshUrl)) as HttpsURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 2_000
            connection.useCaches = false
            connection.defaultUseCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("Connection", "close")
            connection.requestMethod = "GET"
            val prepared = synchronized(probeLifecycleLock) {
                if (
                    !probeRequestGate.owns(ownerToken) ||
                    !isActivelyEnabled() ||
                    expectedEpoch != recoveryEpoch.get() ||
                    !probeSessionIsCurrent(
                        connectivity,
                        capturedHandle,
                        capturedSessionFingerprint
                    )
                ) {
                    false
                } else {
                    activeConnection = connection
                    true
                }
            }
            if (!prepared) return
            connection.connect()
            val stillCurrent = synchronized(probeLifecycleLock) {
                activeConnection === connection &&
                    probeRequestGate.owns(ownerToken) &&
                    isActivelyEnabled() &&
                    expectedEpoch == recoveryEpoch.get() &&
                    probeSessionIsCurrent(
                        connectivity,
                        capturedHandle,
                        capturedSessionFingerprint
                    )
            }
            if (!stillCurrent) {
                logDiscardedProbeResult(
                    reason,
                    capturedHandle,
                    "stale_after_connect"
                )
                return
            }
            val code = connection.responseCode
            val rtt = SystemClock.elapsedRealtime() - start
            val prefs = LuonnotarPreferences.deviceProtected(this)
            if (code == HttpURLConnection.HTTP_NO_CONTENT) {
                val completedElapsed = SystemClock.elapsedRealtime()
                val accepted = synchronized(probeLifecycleLock) {
                    if (
                        !probeRequestGate.owns(ownerToken) ||
                        !isActivelyEnabled(prefs) ||
                        expectedEpoch != recoveryEpoch.get() ||
                        !isCurrentServiceInstance() ||
                        !probeSessionIsCurrent(
                            connectivity,
                            capturedHandle,
                            capturedSessionFingerprint
                        )
                    ) {
                        false
                    } else {
                        val committed = prefs.edit()
                            .putLong(
                                LuonnotarPreferences.KEY_SUCCESS_EVIDENCE_GENERATION,
                                serviceGeneration
                            )
                            .putLong(
                                LuonnotarPreferences.KEY_ATTEMPT_EVIDENCE_GENERATION,
                                serviceGeneration
                            )
                            .putLong(LuonnotarPreferences.KEY_LAST_ATTEMPT_RTT, rtt)
                            .putLong(
                                LuonnotarPreferences.KEY_LAST_ATTEMPT_ELAPSED,
                                completedElapsed
                            )
                            .putLong(
                                LuonnotarPreferences.KEY_LAST_ATTEMPT_NETWORK_HANDLE,
                                capturedHandle
                            )
                            .putString(
                                LuonnotarPreferences.KEY_LAST_ATTEMPT_SESSION_FINGERPRINT,
                                capturedSessionFingerprint
                            )
                            .putLong(LuonnotarPreferences.KEY_LAST_SUCCESS_RTT, rtt)
                            .putInt(LuonnotarPreferences.KEY_LAST_HTTP_CODE, code)
                            .putLong(
                                LuonnotarPreferences.KEY_LAST_SUCCESS_ELAPSED,
                                completedElapsed
                            )
                            .putLong(
                                LuonnotarPreferences.KEY_LAST_SUCCESS_NETWORK_HANDLE,
                                capturedHandle
                            )
                            .putString(
                                LuonnotarPreferences.KEY_LAST_SUCCESS_SESSION_FINGERPRINT,
                                capturedSessionFingerprint
                            )
                            .putInt(
                                LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES,
                                0
                            )
                            .putString(LuonnotarPreferences.KEY_LAST_ERROR, "")
                            .commit()
                        if (committed) {
                            confirmRecoveryIfPending(prefs, completedElapsed)
                        }
                        committed
                    }
                }
                if (!accepted) {
                    logDiscardedProbeResult(
                        reason,
                        capturedHandle,
                        "stale_owner_epoch_or_vpn"
                    )
                    return
                }
                LogManager.event(this, "https_keepalive_ok", mapOf("rttMs" to rtt, "code" to code, "networkHandle" to capturedHandle))
                LogManager.timeline(
                    this,
                    "https_probe_succeeded",
                    mapOf(
                        "probeReason" to reason,
                        "rttMs" to rtt,
                        "httpCode" to code,
                        "capturedNetworkHandle" to capturedHandle
                    )
                )
            } else {
                recordKeepaliveFailure(
                    "HTTP_$code",
                    rtt,
                    code,
                    capturedHandle,
                    expectedEpoch,
                    reason,
                    ownerToken,
                    capturedSessionFingerprint
                )
            }
        } catch (error: Exception) {
            if (
                !isActivelyEnabled() ||
                expectedEpoch != recoveryEpoch.get() ||
                !probeRequestGate.owns(ownerToken) ||
                !probeSessionIsCurrent(
                    connectivity,
                    capturedHandle,
                    capturedSessionFingerprint
                )
            ) {
                logDiscardedProbeResult(
                    reason,
                    capturedHandle,
                    "guardian_inactive_owner_epoch_or_vpn_changed"
                )
            } else {
                recordKeepaliveFailure(
                    error.javaClass.simpleName,
                    SystemClock.elapsedRealtime() - start,
                    -1,
                    capturedHandle,
                    expectedEpoch,
                    reason,
                    ownerToken,
                    capturedSessionFingerprint
                )
            }
        } finally {
            connection?.disconnect()
            synchronized(probeLifecycleLock) {
                if (activeConnection === connection) activeConnection = null
            }
        }
    }
    }

    private fun executeVpnDnsProbe(
        network: android.net.Network,
        capturedHandle: Long,
        ownerToken: ProbeOwnerToken,
        expectedEpoch: Long,
        reason: String,
        capturedSessionFingerprint: String
    ) {
        val connectivity =
            getSystemService(ConnectivityManager::class.java)
        if (
            !isActivelyEnabled() ||
            expectedEpoch != recoveryEpoch.get() ||
            !probeRequestGate.owns(ownerToken) ||
            !probeSessionIsCurrent(
                connectivity,
                capturedHandle,
                capturedSessionFingerprint
            )
        ) return
        lastVpnDnsAttemptElapsed.set(SystemClock.elapsedRealtime())
        var registeredCancellation: CancellationSignal? = null
        LogManager.timeline(
            this,
            "vpn_dns_probe_started",
            mapOf(
                "probeReason" to reason,
                "capturedNetworkHandle" to capturedHandle,
                "sessionFingerprint" to capturedSessionFingerprint
            )
        )
        val result = VpnDnsProbe.probe(network) { cancellation ->
            synchronized(probeLifecycleLock) {
                if (cancellation != null) {
                    registeredCancellation = cancellation
                    if (
                        probeRequestGate.owns(ownerToken) &&
                        expectedEpoch == recoveryEpoch.get() &&
                        isActivelyEnabled()
                    ) {
                        activeDnsCancellation = cancellation
                    } else {
                        cancellation.cancel()
                    }
                } else if (
                    activeDnsCancellation === registeredCancellation
                ) {
                    activeDnsCancellation = null
                }
            }
        }
        val completedElapsed = SystemClock.elapsedRealtime()
        val accepted = synchronized(probeLifecycleLock) {
            probeRequestGate.owns(ownerToken) &&
                expectedEpoch == recoveryEpoch.get() &&
                isActivelyEnabled() &&
                isCurrentServiceInstance() &&
                probeSessionIsCurrent(
                    connectivity,
                    capturedHandle,
                    capturedSessionFingerprint
                )
        }
        if (!accepted) {
            LogManager.timeline(
                this,
                "vpn_dns_probe_discarded",
                mapOf(
                    "probeReason" to reason,
                    "capturedNetworkHandle" to capturedHandle,
                    "error" to result.error
                )
            )
            return
        }
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val previousFailures =
            prefs.getInt(LuonnotarPreferences.KEY_VPN_DNS_FAILURES, 0)
        val editor = prefs.edit()
            .putLong(
                LuonnotarPreferences.KEY_VPN_DNS_LAST_ATTEMPT_ELAPSED,
                completedElapsed
            )
            .putLong(
                LuonnotarPreferences.KEY_VPN_DNS_LAST_RTT,
                result.rttMs
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_DNS_LAST_ERROR,
                result.error
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_DNS_EVIDENCE_SESSION_FINGERPRINT,
                capturedSessionFingerprint
            )
        if (result.succeeded) {
            editor
                .putLong(
                    LuonnotarPreferences.KEY_VPN_DNS_LAST_SUCCESS_ELAPSED,
                    completedElapsed
                )
                .putInt(
                    LuonnotarPreferences.KEY_VPN_DNS_FAILURES,
                    0
                )
        } else {
            editor.putInt(
                LuonnotarPreferences.KEY_VPN_DNS_FAILURES,
                previousFailures + 1
            )
        }
        editor.commit()
        LogManager.timeline(
            this,
            if (result.succeeded) {
                "vpn_dns_probe_succeeded"
            } else {
                "vpn_dns_probe_failed"
            },
            mapOf(
                "probeReason" to reason,
                "supported" to result.supported,
                "answerCount" to result.answerCount,
                "responseCode" to result.responseCode,
                "rttMs" to result.rttMs,
                "error" to result.error,
                "capturedNetworkHandle" to capturedHandle,
                "sessionFingerprint" to capturedSessionFingerprint
            )
        )
    }

    private fun executeTailscaleDnsProbe(
        network: android.net.Network,
        capturedHandle: Long,
        ownerToken: ProbeOwnerToken,
        expectedEpoch: Long,
        reason: String,
        capturedSessionFingerprint: String
    ) {
        if (
            !isActivelyEnabled() ||
            expectedEpoch != recoveryEpoch.get() ||
            !probeRequestGate.owns(ownerToken) ||
            !probeSessionIsCurrent(
                getSystemService(ConnectivityManager::class.java),
                capturedHandle,
                capturedSessionFingerprint
            )
        ) return
        val attemptElapsed = SystemClock.elapsedRealtime()
        lastTailscaleDnsAttemptElapsed.set(attemptElapsed)
        var registeredSocket: DatagramSocket? = null
        LogManager.timeline(
            this,
            "tailscale_dns_probe_started",
            mapOf(
                "probeReason" to reason,
                "capturedNetworkHandle" to capturedHandle
            )
        )
        val result = TailscaleDnsProbe.probe(
            network = network,
            dnsServers = tailscaleEvidence.dnsServers
        ) { socket ->
            synchronized(probeLifecycleLock) {
                if (socket != null) {
                    registeredSocket = socket
                    if (
                        probeRequestGate.owns(ownerToken) &&
                        expectedEpoch == recoveryEpoch.get() &&
                        isActivelyEnabled()
                    ) {
                        activeDnsSocket = socket
                    } else {
                        socket.close()
                    }
                } else if (activeDnsSocket === registeredSocket) {
                    activeDnsSocket = null
                }
            }
        }
        val completedElapsed = SystemClock.elapsedRealtime()
        val connectivity =
            getSystemService(ConnectivityManager::class.java)
        val accepted = synchronized(probeLifecycleLock) {
            probeRequestGate.owns(ownerToken) &&
                expectedEpoch == recoveryEpoch.get() &&
                isActivelyEnabled() &&
                isCurrentServiceInstance() &&
                probeSessionIsCurrent(
                    connectivity,
                    capturedHandle,
                    capturedSessionFingerprint
                )
        }
        if (!accepted) {
            LogManager.timeline(
                this,
                "tailscale_dns_probe_discarded",
                mapOf(
                    "probeReason" to reason,
                    "capturedNetworkHandle" to capturedHandle,
                    "error" to result.error
                )
            )
            return
        }
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val previousFailures = prefs.getInt(
            LuonnotarPreferences.KEY_TAILSCALE_DNS_FAILURES,
            0
        )
        val editor = prefs.edit()
            .putLong(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_LAST_ATTEMPT_ELAPSED,
                completedElapsed
            )
            .putLong(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_LAST_RTT,
                result.rttMs
            )
            .putString(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_LAST_ERROR,
                result.error
            )
            .putString(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_V4,
                result.ipv4Succeeded?.toString()?.uppercase()
                    ?: "UNKNOWN"
            )
            .putString(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_V6,
                result.ipv6Succeeded?.toString()?.uppercase()
                    ?: "UNKNOWN"
            )
            .putLong(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_EVIDENCE_GENERATION,
                serviceGeneration
            )
            .putLong(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_EVIDENCE_NETWORK_HANDLE,
                capturedHandle
            )
        if (result.succeeded) {
            editor
                .putLong(
                    LuonnotarPreferences.KEY_TAILSCALE_DNS_LAST_SUCCESS_ELAPSED,
                    completedElapsed
                )
                .putInt(
                    LuonnotarPreferences.KEY_TAILSCALE_DNS_FAILURES,
                    0
                )
        } else {
            editor.putInt(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_FAILURES,
                previousFailures + 1
            )
        }
        editor.commit()
        LogManager.timeline(
            this,
            if (result.succeeded) {
                "tailscale_dns_probe_succeeded"
            } else {
                "tailscale_dns_probe_failed"
            },
            mapOf(
                "probeReason" to reason,
                "attempted" to result.attempted,
                "rttMs" to result.rttMs,
                "dnsServer" to result.server,
                "error" to result.error,
                "capturedNetworkHandle" to capturedHandle
            )
        )
    }

    private fun executeMtalkPathProbe(
        network: android.net.Network,
        capturedHandle: Long,
        ownerToken: ProbeOwnerToken,
        expectedEpoch: Long,
        reason: String,
        capturedSessionFingerprint: String
    ) {
        val connectivity =
            getSystemService(ConnectivityManager::class.java)
        if (
            !isActivelyEnabled() ||
            expectedEpoch != recoveryEpoch.get() ||
            !probeRequestGate.owns(ownerToken) ||
            !probeSessionIsCurrent(
                connectivity,
                capturedHandle,
                capturedSessionFingerprint
            )
        ) return
        lastMtalkAttemptElapsed.set(SystemClock.elapsedRealtime())
        var registeredCancellation: CancellationSignal? = null
        var registeredSocket: Socket? = null
        LogManager.timeline(
            this,
            "mtalk_path_probe_started",
            mapOf(
                "probeReason" to reason,
                "networkHandle" to capturedHandle,
                "sessionFingerprint" to capturedSessionFingerprint
            )
        )
        val result = MtalkPathProbe.probe(
            network = network,
            onCancellationChanged = { cancellation ->
                synchronized(probeLifecycleLock) {
                    if (cancellation != null) {
                        registeredCancellation = cancellation
                        if (
                            probeRequestGate.owns(ownerToken) &&
                            expectedEpoch == recoveryEpoch.get() &&
                            isActivelyEnabled()
                        ) {
                            activeDnsCancellation = cancellation
                        } else {
                            cancellation.cancel()
                        }
                    } else if (
                        activeDnsCancellation === registeredCancellation
                    ) {
                        activeDnsCancellation = null
                    }
                }
            },
            onSocketChanged = { socket ->
                synchronized(probeLifecycleLock) {
                    if (socket != null) {
                        registeredSocket = socket
                        if (
                            probeRequestGate.owns(ownerToken) &&
                            expectedEpoch == recoveryEpoch.get() &&
                            isActivelyEnabled()
                        ) {
                            activeMtalkSocket = socket
                        } else {
                            runCatching { socket.close() }
                        }
                    } else if (activeMtalkSocket === registeredSocket) {
                        activeMtalkSocket = null
                    }
                }
            }
        )
        val accepted = synchronized(probeLifecycleLock) {
            probeRequestGate.owns(ownerToken) &&
                expectedEpoch == recoveryEpoch.get() &&
                isActivelyEnabled() &&
                isCurrentServiceInstance() &&
                probeSessionIsCurrent(
                    connectivity,
                    capturedHandle,
                    capturedSessionFingerprint
                )
        }
        if (!accepted) {
            LogManager.timeline(
                this,
                "mtalk_path_probe_discarded",
                mapOf(
                    "probeReason" to reason,
                    "networkHandle" to capturedHandle
                )
            )
            return
        }
        val summary = result.tcpResults.joinToString(";") {
            "${it.family}:${it.port}=${
                if (it.succeeded) "OK" else it.error.ifBlank { "FAIL" }
            }"
        }
        LuonnotarPreferences.deviceProtected(this).edit()
            .putLong(
                LuonnotarPreferences.KEY_MTALK_LAST_ATTEMPT_ELAPSED,
                SystemClock.elapsedRealtime()
            )
            .putString(
                LuonnotarPreferences.KEY_MTALK_LAST_SESSION_FINGERPRINT,
                capturedSessionFingerprint
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV4_DNS,
                result.ipv4Dns
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV6_DNS,
                result.ipv6Dns
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5228,
                result.portSucceeded("IPV4", 5228)
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5229,
                result.portSucceeded("IPV4", 5229)
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5230,
                result.portSucceeded("IPV4", 5230)
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV4_TCP_443,
                result.portSucceeded("IPV4", 443)
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5228,
                result.portSucceeded("IPV6", 5228)
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5229,
                result.portSucceeded("IPV6", 5229)
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5230,
                result.portSucceeded("IPV6", 5230)
            )
            .putBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV6_TCP_443,
                result.portSucceeded("IPV6", 443)
            )
            .putString(
                LuonnotarPreferences.KEY_MTALK_RESULT_SUMMARY,
                summary
            )
            .commit()
        LogManager.timeline(
            this,
            "mtalk_path_probe_completed",
            mapOf(
                "probeReason" to reason,
                "supported" to result.supported,
                "ipv4Dns" to result.ipv4Dns,
                "ipv6Dns" to result.ipv6Dns,
                "tcpResults" to summary,
                "elapsedMs" to result.elapsedMs,
                "networkHandle" to capturedHandle,
                "mcsSocketState" to "UNKNOWN"
            )
        )
    }

    private fun disconnectActiveConnection() {
        var dnsSocket: DatagramSocket? = null
        var dnsCancellation: CancellationSignal? = null
        var mtalkSocket: Socket? = null
        val connection = synchronized(probeLifecycleLock) {
            dnsSocket = activeDnsSocket
            activeDnsSocket = null
            dnsCancellation = activeDnsCancellation
            activeDnsCancellation = null
            mtalkSocket = activeMtalkSocket
            activeMtalkSocket = null
            activeConnection.also { activeConnection = null }
        }
        dnsCancellation?.cancel()
        runCatching { mtalkSocket?.close() }
        dnsSocket?.close()
        connection?.disconnect()
    }

    private fun isCurrentTailscaleNetwork(): Boolean =
        tailscaleEvidence.present &&
            tailscaleEvidence.networkHandle >= 0L &&
            tailscaleEvidence.networkHandle == vpnEvidence.networkHandle &&
            (
                vpnEvidence.providerPackage.isNullOrBlank() ||
                    vpnEvidence.providerPackage ==
                    SupportedVpnProvider.TAILSCALE.packageName
                )

    private fun tailscaleExpectedHandle(evidence: VpnEvidence): Long? {
        if (!evidence.present || evidence.networkHandle < 0L) return null
        if (
            evidence.providerPackage ==
            SupportedVpnProvider.TAILSCALE.packageName
        ) return evidence.networkHandle
        val prefs = LuonnotarPreferences.deviceProtected(this)
        return evidence.networkHandle.takeIf {
            prefs.getString(
                LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE,
                ""
            ) == SupportedVpnProvider.TAILSCALE.packageName &&
                prefs.getLong(
                    LuonnotarPreferences.KEY_ADB_NETWORK_HANDLE,
                    -1L
                ) == evidence.networkHandle
        }
    }

    private fun persistTailscaleEvidence(ignoreQuietWindow: Boolean = false) {
        if (!isCurrentServiceInstance()) return
        val evidence = tailscaleEvidence
        if (
            !ignoreQuietWindow &&
            quietWindowActive() &&
            evidence.present &&
            !(evidence.blockedKnown && evidence.blocked)
        ) {
            pendingTailscaleEvidenceFlush = true
            return
        }
        LuonnotarPreferences.deviceProtected(this).edit()
            .putBoolean(
                LuonnotarPreferences.KEY_TAILSCALE_PRESENT,
                evidence.present
            )
            .putLong(
                LuonnotarPreferences.KEY_TAILSCALE_NETWORK_HANDLE,
                evidence.networkHandle
            )
            .putBoolean(
                LuonnotarPreferences.KEY_TAILSCALE_COMPLETE,
                evidence.complete
            )
            .putBoolean(
                LuonnotarPreferences.KEY_TAILSCALE_VALIDATED,
                evidence.validated
            )
            .putBoolean(
                LuonnotarPreferences.KEY_TAILSCALE_BLOCKED,
                evidence.blocked
            )
            .putBoolean(
                LuonnotarPreferences.KEY_TAILSCALE_BLOCKED_KNOWN,
                evidence.blockedKnown
            )
            .putBoolean(
                LuonnotarPreferences.KEY_TAILSCALE_SUSPENDED,
                evidence.suspended
            )
            .putBoolean(
                LuonnotarPreferences.KEY_TAILSCALE_SELF_EXCLUDED,
                evidence.present && !isCurrentTailscaleNetwork()
            )
            .putString(
                LuonnotarPreferences.KEY_TAILSCALE_ROUTE_STATE,
                evidence.routeState.name
            )
            .putString(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_SERVERS,
                evidence.dnsServers.joinToString(",") { it.hostAddress.orEmpty() }
            )
            .putString(
                LuonnotarPreferences.KEY_TAILSCALE_UNDERLYING_HANDLES,
                evidence.underlyingNetworkHandles.sorted().joinToString(",")
            )
            .apply()
        pendingTailscaleEvidenceFlush = false
    }

    private fun flushPendingEvidence() {
        if (!isCurrentServiceInstance()) return
        if (pendingNetworkEvidenceFlush) {
            persistNetworkEvidence(ignoreQuietWindow = true)
        }
        if (pendingTailscaleEvidenceFlush) {
            persistTailscaleEvidence(ignoreQuietWindow = true)
        }
    }

    private fun restartKeeperForHardProbeTimeout(
        probeAgeMs: Long,
        expectedPermit: ActualProbePermitSnapshot,
        expectedVpnHandle: Long
    ): Boolean {
        val before = PROCESS_ACTUAL_PROBE_PERMIT.snapshot()
        val now = SystemClock.elapsedRealtime()
        val localActualOwner = probeRequestGate.snapshot().actualOwner
        if (
            !ProbeHardRestartPolicy.leaseStillEligible(
                expected = expectedPermit,
                current = before,
                nowElapsed = now,
                hardTimeoutMs = PROBE_HARD_TIMEOUT_MS,
                expectedVpnHandle = expectedVpnHandle,
                currentVpnHandle = vpnEvidence.networkHandle
            ) ||
            (localActualOwner != null && localActualOwner !== before.owner) ||
            !isActivelyEnabled()
        ) {
            LogManager.timeline(
                this,
                "https_probe_hard_restart_cancelled",
                mapOf("reason" to "owner_age_epoch_or_vpn_changed")
            )
            return false
        }
        if (!hardProbeRestartRequested.compareAndSet(false, true)) return false
        val preferences = LuonnotarPreferences.deviceProtected(this)
        val insuranceScheduled = runCatching {
            LabAlarmScheduler.scheduleNext(this)
        }.getOrDefault(false)
        val restartNonce = java.util.UUID.randomUUID().toString()
        val nearRecoveryScheduled = runCatching {
            LabAlarmScheduler.scheduleHardRestart(
                context = this,
                restartNonce = restartNonce,
                expectedOldPid = Process.myPid(),
                expectedGeneration = serviceGeneration,
                expectedPermitOwner =
                    expectedPermit.owner?.value ?: -1L
            )
        }.getOrDefault(false)
        if (!nearRecoveryScheduled) {
            hardProbeRestartRequested.set(false)
            disconnectActiveConnection()
            preferences.edit()
                .putString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE,
                    "HTTPS 探测已卡死，但系统无法可靠安排近端恢复；已取消 Keeper 自杀"
                )
                .putLong(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                    now
                )
                .commit()
            LogManager.timeline(
                this,
                "https_probe_hard_restart_not_scheduled",
                mapOf(
                    "probeAgeMs" to probeAgeMs,
                    "insuranceScheduled" to insuranceScheduled
                )
            )
            showAlert(
                "无法安全重启 Keeper",
                "HTTPS/DNS 探测已卡死，但精确近端恢复不可用；努昂诺塔已取消进程自杀，请打开应用检查精确闹钟与恢复设置。"
            )
            return false
        }
        val confirmed = PROCESS_ACTUAL_PROBE_PERMIT.snapshot()
        if (
            !ProbeHardRestartPolicy.leaseStillEligible(
                expected = expectedPermit,
                current = confirmed,
                nowElapsed = SystemClock.elapsedRealtime(),
                hardTimeoutMs = PROBE_HARD_TIMEOUT_MS,
                expectedVpnHandle = expectedVpnHandle,
                currentVpnHandle = vpnEvidence.networkHandle
            ) ||
            !isActivelyEnabled()
        ) {
            LabAlarmScheduler.cancelHardRestart(this)
            hardProbeRestartRequested.set(false)
            LogManager.timeline(
                this,
                "https_probe_hard_restart_cancelled",
                mapOf("reason" to "lease_changed_after_near_alarm")
            )
            return false
        }
        preferences.edit()
            .putString(
                LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE,
                "HTTPS 探测已卡死 ${probeAgeMs / 1000} 秒；Keeper 正在受控重启"
            )
            .putLong(
                LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                now
            )
            .putBoolean(
                LuonnotarPreferences.KEY_RECOVERY_CONFIRMATION_PENDING,
                true
            )
            .putLong(
                LuonnotarPreferences.KEY_RECOVERY_REQUESTED_ELAPSED,
                now
            )
            .putString(
                LuonnotarPreferences.KEY_LAST_SERVICE_EXIT,
                "probe_hard_timeout_restart"
            )
            .commit()
        LogManager.timeline(
            this,
            "https_probe_hard_timeout_process_restart",
            mapOf(
                "probeAgeMs" to probeAgeMs,
                "actualOwnerGeneration" to expectedPermit.owner?.generation,
                "actualNetworkHandle" to expectedPermit.networkHandle,
                "insuranceScheduled" to insuranceScheduled,
                "nearRecoveryScheduled" to nearRecoveryScheduled
            )
        )
        disconnectActiveConnection()
        Process.killProcess(Process.myPid())
        return true
    }

    private fun lastDnsAttemptForCurrentTransport(): Long =
        if (isCurrentTailscaleNetwork()) {
            lastTailscaleDnsAttemptElapsed.get()
        } else {
            lastVpnDnsAttemptElapsed.get()
        }

    private fun requestRecoveryProbe(
        reason: String,
        force: Boolean = false,
        plan: ProbePlan = ProbePlan.DNS,
        quietWindowBypass: Boolean = false,
        triggerClass: ProbeTriggerClass = ProbeTriggerClass.PERIODIC
    ) {
        if (!isActivelyEnabled()) return
        val prefs = LuonnotarPreferences.deviceProtected(this)
        if (
            !KeepaliveCadencePolicy.mayProbe(
                vpnPresent = vpnEvidence.usable,
                paused = prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
            )
        ) {
            LogManager.timeline(
                this,
                "https_probe_blocked",
                mapOf(
                    "probeReason" to reason,
                    "blockReason" to "vpn_not_usable"
                )
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val mayBreakQuiet =
            quietWindowBypass ||
                triggerClass == ProbeTriggerClass.STRUCTURAL_RECOVERY ||
                triggerClass == ProbeTriggerClass.USER_ACTION
        if (quietWindowActive(now) && !mayBreakQuiet) {
            LogManager.timeline(
                this,
                "probe_suppressed_quiet_window",
                mapOf(
                    "probeReason" to reason,
                    "probePlan" to plan.name,
                    "triggerClass" to triggerClass.name,
                    "quietRemainingMs" to
                        (quietWindowUntilElapsed - now).coerceAtLeast(0L)
                )
            )
            return
        }
        val lastAttempt = when (plan) {
            ProbePlan.DNS -> lastDnsAttemptForCurrentTransport()
            ProbePlan.HTTPS -> lastKeepaliveAttemptElapsed.get()
            ProbePlan.MTALK -> lastMtalkAttemptElapsed.get()
            ProbePlan.MANUAL_DIAGNOSTIC -> 0L
        }
        if (!force && lastAttempt != 0L && now - lastAttempt < RECOVERY_PROBE_COOLDOWN_MS) return
        val taskEpoch = recoveryEpoch.get()
        if (force && probeRequestGate.snapshot().anyInFlight) {
            mergePendingForcedPlan(plan)
        }
        val submission = synchronized(probeLifecycleLock) {
            val token = probeRequestGate.begin(
                generation = taskEpoch,
                force = force,
                startedElapsed = now
            )
            if (token == null) {
                if (force) mergePendingForcedPlan(plan)
                return
            }
            val committed = persistProbeGateSnapshotLocked(prefs)
            if (!committed) {
                probeRequestGate.reset(token)
                persistError("probe_begin:SharedPreferencesCommitFailed")
                return
            }
            token to probeExecutor
        }
        val ownerToken = submission.first
        val executorSnapshot = submission.second
        runCatching {
            executorSnapshot.execute {
                try {
                    if (
                        !isActivelyEnabled() ||
                        taskEpoch != recoveryEpoch.get() ||
                        !vpnEvidence.present
                    ) return@execute
                    LogManager.event(this, "recovery_probe_requested", mapOf("reason" to reason))
                    if (!probeRequestGate.beginActual(ownerToken)) {
                        LogManager.timeline(
                            this,
                            "https_probe_serialized",
                            mapOf(
                                "probeReason" to reason,
                                "ownerToken" to ownerToken.value,
                                "generation" to ownerToken.generation
                            )
                        )
                        return@execute
                    }
                    if (
                        !PROCESS_ACTUAL_PROBE_PERMIT.tryAcquire(
                            ownerToken,
                            acquiredElapsed = SystemClock.elapsedRealtime(),
                            networkHandle = vpnEvidence.networkHandle,
                            stage = when (plan) {
                                ProbePlan.HTTPS -> ProbeStage.HTTPS.name
                                ProbePlan.DNS -> ProbeStage.VPN_DNS.name
                                ProbePlan.MTALK -> ProbeStage.MTALK.name
                                ProbePlan.MANUAL_DIAGNOSTIC ->
                                    ProbeStage.VPN_DNS.name
                            }
                        )
                    ) {
                        synchronized(probeLifecycleLock) {
                            probeRequestGate.finishActual(ownerToken)
                            if (!destroyed.get() && !stopping.get()) {
                                persistProbeGateSnapshotLocked()
                            }
                        }
                        LogManager.timeline(
                            this,
                            "https_probe_process_serialized",
                            mapOf(
                                "probeReason" to reason,
                                "ownerToken" to ownerToken.value,
                                "generation" to ownerToken.generation
                            )
                        )
                        scheduleProcessProbePermitRetry(
                            plan,
                            triggerClass
                        )
                        return@execute
                    }
                    try {
                        withScopedCpuLock("probe_${plan.name.lowercase()}") {
                            executeKeepalive(
                                ownerToken,
                                taskEpoch,
                                reason,
                                plan
                            )
                        }
                        if (isActivelyEnabled() && taskEpoch == recoveryEpoch.get()) {
                            updateStateAndAlerts()
                            updateNotification()
                        }
                    } finally {
                        PROCESS_ACTUAL_PROBE_PERMIT.release(ownerToken)
                        val actualFinish = synchronized(probeLifecycleLock) {
                            val result =
                                probeRequestGate.finishActual(ownerToken)
                            if (
                                result.accepted &&
                                !destroyed.get() &&
                                !stopping.get()
                            ) {
                                persistProbeGateSnapshotLocked()
                            }
                            result
                        }
                        if (
                            actualFinish.accepted &&
                            actualFinish.runPendingForced &&
                            isActivelyEnabled()
                        ) {
                            requestRecoveryProbe(
                                "serialized_actual_probe_followup",
                                force = true,
                                plan = takePendingForcedPlan(),
                                triggerClass =
                                    ProbeTriggerClass.STRUCTURAL_RECOVERY
                            )
                        }
                    }
                } finally {
                    val finishResult = synchronized(probeLifecycleLock) {
                        val result = probeRequestGate.finish(ownerToken)
                        if (result.accepted) {
                            val cleared = persistProbeGateSnapshotLocked()
                            if (!cleared) {
                                persistError(
                                    "probe_finish:SharedPreferencesCommitFailed"
                                )
                            }
                        }
                        result
                    }
                    if (
                        finishResult.accepted &&
                        finishResult.runPendingForced &&
                        isActivelyEnabled()
                    ) {
                        requestRecoveryProbe(
                            "pending_forced_probe",
                            force = true,
                            plan = takePendingForcedPlan(),
                            triggerClass =
                                ProbeTriggerClass.STRUCTURAL_RECOVERY
                        )
                    }
                }
            }
        }.onFailure {
            val cancelled = synchronized(probeLifecycleLock) {
                val accepted = probeRequestGate.reset(ownerToken)
                if (accepted) {
                    persistProbeGateSnapshotLocked()
                }
                accepted
            }
            if (
                cancelled &&
                !destroyed.get() &&
                !stopping.get()
            ) {
                persistError("probe_submit:${it.javaClass.simpleName}:${it.message}")
                LogManager.event(
                    this,
                    "recovery_probe_submit_failed",
                    mapOf("error" to it.toString())
                )
            }
        }
    }

    private fun persistProbeGateSnapshotLocked(
        prefs: android.content.SharedPreferences =
            LuonnotarPreferences.deviceProtected(this)
    ): Boolean {
        if (
            serviceGeneration <= 0L ||
            prefs.getLong(
                LuonnotarPreferences.KEY_SERVICE_GENERATION,
                -1L
            ) != serviceGeneration
        ) return false
        val snapshot = probeRequestGate.snapshot()
        val editor = prefs.edit()
            .putBoolean(
                LuonnotarPreferences.KEY_PROBE_IN_FLIGHT,
                snapshot.anyInFlight
            )
        if (snapshot.anyInFlight && snapshot.effectiveStartedElapsed > 0L) {
            editor
                .putLong(
                    LuonnotarPreferences.KEY_PROBE_STARTED_ELAPSED,
                    snapshot.effectiveStartedElapsed
                )
                .putLong(
                    LuonnotarPreferences.KEY_PROBE_DEADLINE_ELAPSED,
                    snapshot.effectiveStartedElapsed + PROBE_TIMEOUT_MS
                )
        } else {
            editor
                .remove(LuonnotarPreferences.KEY_PROBE_STARTED_ELAPSED)
                .remove(LuonnotarPreferences.KEY_PROBE_DEADLINE_ELAPSED)
        }
        return editor.commit()
    }

    private fun scheduleProcessProbePermitRetry(
        plan: ProbePlan,
        triggerClass: ProbeTriggerClass
    ) {
        if (!processProbeRetryScheduled.compareAndSet(false, true)) return
        val expectedEpoch = recoveryEpoch.get()
        val executor = scheduler
        runCatching {
            executor.schedule({
                processProbeRetryScheduled.set(false)
                if (
                    expectedEpoch == recoveryEpoch.get() &&
                    isActivelyEnabled()
                ) {
                    requestRecoveryProbe(
                        "process_probe_permit_retry",
                        force = true,
                        plan = plan,
                        triggerClass = triggerClass
                    )
                }
            }, 5L, TimeUnit.SECONDS)
        }.onFailure {
            processProbeRetryScheduled.set(false)
            if (isCurrentServiceInstance()) {
                LogManager.event(
                    this,
                    "process_probe_permit_retry_schedule_failed",
                    mapOf("error" to it.toString())
                )
            }
        }
    }

    private fun higherPriorityPlan(
        current: ProbePlan,
        candidate: ProbePlan
    ): ProbePlan {
        fun priority(plan: ProbePlan): Int = when (plan) {
            ProbePlan.MANUAL_DIAGNOSTIC -> 4
            ProbePlan.HTTPS -> 3
            ProbePlan.MTALK -> 2
            ProbePlan.DNS -> 1
        }
        return if (priority(candidate) > priority(current)) {
            candidate
        } else {
            current
        }
    }

    @Synchronized
    private fun mergePendingForcedPlan(candidate: ProbePlan) {
        pendingForcedPlan = higherPriorityPlan(
            pendingForcedPlan,
            candidate
        )
    }

    @Synchronized
    private fun takePendingForcedPlan(): ProbePlan {
        val result = pendingForcedPlan
        pendingForcedPlan = ProbePlan.DNS
        return result
    }

    private fun recordKeepaliveFailure(
        error: String,
        rtt: Long,
        code: Int,
        capturedHandle: Long,
        expectedEpoch: Long,
        reason: String,
        ownerToken: ProbeOwnerToken,
        capturedSessionFingerprint: String
    ) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val prefs = LuonnotarPreferences.deviceProtected(this)
        var count = 0
        val accepted = synchronized(probeLifecycleLock) {
            if (
                !probeRequestGate.owns(ownerToken) ||
                !isActivelyEnabled(prefs) ||
                expectedEpoch != recoveryEpoch.get() ||
                !isCurrentServiceInstance() ||
                !probeSessionIsCurrent(
                    connectivity,
                    capturedHandle,
                    capturedSessionFingerprint
                )
            ) {
                false
            } else {
                count = prefs.getInt(
                    LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES,
                    0
                ) + 1
                prefs.edit()
                    .putLong(
                        LuonnotarPreferences.KEY_ATTEMPT_EVIDENCE_GENERATION,
                        serviceGeneration
                    )
                    .putInt(
                        LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES,
                        count
                    )
                    .putLong(LuonnotarPreferences.KEY_LAST_ATTEMPT_RTT, rtt)
                    .putLong(
                        LuonnotarPreferences.KEY_LAST_ATTEMPT_ELAPSED,
                        SystemClock.elapsedRealtime()
                    )
                    .putLong(
                        LuonnotarPreferences.KEY_LAST_ATTEMPT_NETWORK_HANDLE,
                        capturedHandle
                    )
                    .putString(
                        LuonnotarPreferences.KEY_LAST_ATTEMPT_SESSION_FINGERPRINT,
                        capturedSessionFingerprint
                    )
                    .putInt(LuonnotarPreferences.KEY_LAST_HTTP_CODE, code)
                    .putString(LuonnotarPreferences.KEY_LAST_ERROR, error)
                    .commit()
            }
        }
        if (!accepted) {
            logDiscardedProbeResult(
                reason,
                capturedHandle,
                "stale_owner_epoch_or_vpn"
            )
            return
        }
        LogManager.event(this, "https_keepalive_failed", mapOf("error" to error, "rttMs" to rtt, "failures" to count))
        LogManager.timeline(
            this,
            if (error.contains("Timeout", ignoreCase = true)) {
                "https_probe_timeout"
            } else {
                "https_probe_failed"
            },
            mapOf(
                "probeReason" to reason,
                "error" to error,
                "rttMs" to rtt,
                "httpCode" to code,
                "capturedNetworkHandle" to capturedHandle,
                "failures" to count
            )
        )
    }

    private fun logDiscardedProbeResult(
        reason: String,
        capturedHandle: Long,
        discardReason: String
    ) {
        LogManager.event(
            this,
            "https_keepalive_cancelled",
            mapOf(
                "reason" to discardReason,
                "networkHandle" to capturedHandle
            )
        )
        LogManager.timeline(
            this,
            "https_probe_result_discarded",
            mapOf(
                "probeReason" to reason,
                "capturedNetworkHandle" to capturedHandle,
                "discardReason" to discardReason
            )
        )
    }

    private fun updateStateAndAlerts() {
        if (destroyed.get() || stopping.get()) return
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val successEvidenceIsCurrent =
            prefs.getLong(
                LuonnotarPreferences.KEY_SUCCESS_EVIDENCE_GENERATION,
                -1L
            ) == serviceGeneration &&
            prefs.getLong(
                LuonnotarPreferences.KEY_LAST_SUCCESS_NETWORK_HANDLE,
                -1L
            ) == vpnEvidence.networkHandle &&
            prefs.getString(
                LuonnotarPreferences.KEY_LAST_SUCCESS_SESSION_FINGERPRINT,
                ""
            ) == vpnEvidence.sessionFingerprint
        val attemptEvidenceIsCurrent =
            prefs.getLong(
                LuonnotarPreferences.KEY_ATTEMPT_EVIDENCE_GENERATION,
                -1L
            ) == serviceGeneration &&
            prefs.getLong(
                LuonnotarPreferences.KEY_LAST_ATTEMPT_NETWORK_HANDLE,
                -1L
            ) == vpnEvidence.networkHandle &&
            prefs.getString(
                LuonnotarPreferences.KEY_LAST_ATTEMPT_SESSION_FINGERPRINT,
                ""
            ) == vpnEvidence.sessionFingerprint
        val lastSuccess = prefs.getLong(LuonnotarPreferences.KEY_LAST_SUCCESS_ELAPSED, 0)
        val lastAttempt = prefs.getLong(LuonnotarPreferences.KEY_LAST_ATTEMPT_ELAPSED, 0)
        val now = SystemClock.elapsedRealtime()
        val hasAnySuccess = successEvidenceIsCurrent && lastSuccess > 0L && lastSuccess <= now
        val currentInterval = KeepaliveCadencePolicy.NORMAL_INTERVAL_MS
        val recentEnough =
            hasAnySuccess && now - lastSuccess <= 2 * currentInterval
        val baseState = GuardianStateReducer.reduce(
            enabled = prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false),
            paused = prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false),
            vpn = vpnEvidence.present,
            validated = vpnEvidence.validated,
            bypassable = effectiveBypassability(prefs, now),
            lastHttpCode = prefs.getInt(LuonnotarPreferences.KEY_LAST_HTTP_CODE, -1),
            failures = prefs.getInt(LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES, 0),
            hasAnySuccess = hasAnySuccess,
            hasRecentSuccess = recentEnough,
            targetRoutingVerified = targetRoutingVerified(prefs, now),
            hasEverObservedVpn = prefs.getBoolean(
                LuonnotarPreferences.KEY_HAS_EVER_OBSERVED_VPN,
                false
            )
        )
        val vpnDnsEvidenceIsCurrent =
            prefs.getString(
                LuonnotarPreferences.KEY_VPN_DNS_EVIDENCE_SESSION_FINGERPRINT,
                ""
            ).orEmpty().isNotBlank() &&
                prefs.getString(
                    LuonnotarPreferences.KEY_VPN_DNS_EVIDENCE_SESSION_FINGERPRINT,
                    ""
                ) == vpnEvidence.sessionFingerprint
        val vpnState = VpnGuardianStatePolicy.overlay(
            base = baseState,
            vpnPresent = vpnEvidence.present,
            complete = vpnEvidence.complete,
            blocked = vpnEvidence.blockedKnown && vpnEvidence.blocked,
            notSuspended = vpnEvidence.notSuspended,
            routeState = vpnEvidence.routeState,
            dnsEvidenceCurrent = vpnDnsEvidenceIsCurrent,
            dnsFailures = prefs.getInt(
                LuonnotarPreferences.KEY_VPN_DNS_FAILURES,
                0
            )
        )
        val tailscaleDnsEvidenceIsCurrent =
            prefs.getLong(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_EVIDENCE_GENERATION,
                -1L
            ) == serviceGeneration &&
                prefs.getLong(
                    LuonnotarPreferences.KEY_TAILSCALE_DNS_EVIDENCE_NETWORK_HANDLE,
                    -1L
                ) == vpnEvidence.networkHandle
        val state = TailscaleGuardianStatePolicy.overlay(
            base = vpnState,
            tailscaleActive = isCurrentTailscaleNetwork(),
            tailscalePresent = tailscaleEvidence.present,
            complete = tailscaleEvidence.complete,
            blocked =
                tailscaleEvidence.blockedKnown && tailscaleEvidence.blocked,
            suspended = tailscaleEvidence.suspended,
            routeState = tailscaleEvidence.routeState,
            dnsEvidenceCurrent = tailscaleDnsEvidenceIsCurrent,
            dnsFailures = prefs.getInt(
                LuonnotarPreferences.KEY_TAILSCALE_DNS_FAILURES,
                0
            )
        )
        val sessionHealth = when {
            !vpnEvidence.present -> "NO_VPN"
            !vpnEvidence.complete -> "INCOMPLETE"
            vpnEvidence.blockedKnown && vpnEvidence.blocked -> "BLOCKED"
            !vpnEvidence.notSuspended -> "SUSPENDED"
            !vpnEvidence.validated -> "UNVALIDATED"
            vpnEvidence.routeState != VpnRouteState.ROUTED ->
                "ROUTE_INCOMPLETE"
            else -> "HEALTHY"
        }
        val vpnDnsFailures = prefs.getInt(
            LuonnotarPreferences.KEY_VPN_DNS_FAILURES,
            0
        )
        val vpnDnsLastSuccess = prefs.getLong(
            LuonnotarPreferences.KEY_VPN_DNS_LAST_SUCCESS_ELAPSED,
            0L
        )
        val dnsFreshInterval = KeepaliveCadencePolicy.NORMAL_INTERVAL_MS
        val dnsHealth = when {
            !vpnDnsEvidenceIsCurrent -> "UNKNOWN"
            vpnDnsFailures >= 2 -> "STALLED"
            vpnDnsLastSuccess <= 0L -> "UNVERIFIED"
            now - vpnDnsLastSuccess > 2 * dnsFreshInterval ->
                "STALE"
            else -> "HEALTHY"
        }
        val httpsHealth = when {
            !successEvidenceIsCurrent -> "UNKNOWN"
            prefs.getInt(
                LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES,
                0
            ) > 0 -> "STALLED"
            !recentEnough -> "STALE"
            prefs.getInt(
                LuonnotarPreferences.KEY_LAST_HTTP_CODE,
                -1
            ) == HttpURLConnection.HTTP_NO_CONTENT -> "HEALTHY"
            else -> "STALLED"
        }
        val mtalkCurrent =
            prefs.getString(
                LuonnotarPreferences.KEY_MTALK_LAST_SESSION_FINGERPRINT,
                ""
            ) == vpnEvidence.sessionFingerprint &&
                vpnEvidence.sessionFingerprint.isNotBlank()
        val mtalkDns =
            prefs.getBoolean(
                LuonnotarPreferences.KEY_MTALK_IPV4_DNS,
                false
            ) ||
                prefs.getBoolean(
                    LuonnotarPreferences.KEY_MTALK_IPV6_DNS,
                    false
                )
        val mtalkTcp = listOf(
            LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5228,
            LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5229,
            LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5230,
            LuonnotarPreferences.KEY_MTALK_IPV4_TCP_443,
            LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5228,
            LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5229,
            LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5230,
            LuonnotarPreferences.KEY_MTALK_IPV6_TCP_443
        ).any { prefs.getBoolean(it, false) }
        val fcmHealth = when {
            !mtalkCurrent -> "UNKNOWN_NOT_MEASURED"
            !mtalkDns || !mtalkTcp -> "MTALK_PATH_STALLED_MCS_UNKNOWN"
            else -> "MTALK_PATH_REACHABLE_MCS_UNKNOWN"
        }
        val stateChanged =
            prefs.getString(LuonnotarPreferences.KEY_STATE, "") != state.name ||
                prefs.getString(
                    LuonnotarPreferences.KEY_VPN_SESSION_HEALTH,
                    ""
                ) != sessionHealth ||
                prefs.getString(
                    LuonnotarPreferences.KEY_VPN_DNS_HEALTH,
                    ""
                ) != dnsHealth ||
                prefs.getString(
                    LuonnotarPreferences.KEY_VPN_HTTPS_HEALTH,
                    ""
                ) != httpsHealth ||
                prefs.getString(
                    LuonnotarPreferences.KEY_FCM_HEALTH,
                    ""
                ) != fcmHealth
        if (stateChanged) {
            val stateHeartbeat =
                heartbeatElapsed.takeIf { it > 0L } ?: now
            prefs.edit()
            .putString(LuonnotarPreferences.KEY_STATE, state.name)
            .putString(
                LuonnotarPreferences.KEY_VPN_SESSION_HEALTH,
                sessionHealth
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_DNS_HEALTH,
                dnsHealth
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_HTTPS_HEALTH,
                httpsHealth
            )
            .putString(
                LuonnotarPreferences.KEY_FCM_HEALTH,
                fcmHealth
            )
            .putString(
                LuonnotarPreferences.KEY_WHATSAPP_DELIVERY_HEALTH,
                when (
                    PushTestDeliveryPolicy.evaluate(
                        nowWall = System.currentTimeMillis(),
                        sequence = prefs.getLong(
                            LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEQUENCE,
                            0L
                        ),
                        senderEpochMs = prefs.getLong(
                            LuonnotarPreferences
                                .KEY_PUSH_TEST_LAST_SENDER_EPOCH_MS,
                            0L
                        ),
                        seenWall = prefs.getLong(
                            LuonnotarPreferences.KEY_PUSH_TEST_LAST_SEEN_WALL,
                            0L
                        )
                    ).state
                ) {
                    ControlledPushDeliveryState.RECENT ->
                        "CONTROLLED_PUSH_TEST_RECENT"
                    ControlledPushDeliveryState.STALE ->
                        "CONTROLLED_PUSH_TEST_STALE"
                    ControlledPushDeliveryState.CLOCK_INVALID ->
                        "CONTROLLED_PUSH_TEST_CLOCK_INVALID"
                    ControlledPushDeliveryState.UNVERIFIED ->
                        "UNKNOWN_NOTIFICATION_EVENTS_ONLY"
                }
            )
            .putLong(
                LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED,
                stateHeartbeat
            )
            .apply()
            lastHeartbeatPersistedElapsed = stateHeartbeat
        }
        val paused = prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        val failures = prefs.getInt(LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES, 0)
        val httpsDegraded = KeepaliveAlertPolicy.shouldAlertHttps(
            paused = paused,
            vpnPresent = vpnEvidence.present,
            validated = vpnEvidence.validated,
            lastAttemptElapsed = lastAttempt,
            attemptEvidenceIsCurrent = attemptEvidenceIsCurrent,
            failures = failures,
            hasAnySuccess = hasAnySuccess,
            hasRecentSuccess = recentEnough
        )
        val alert = when {
            !paused &&
                state == GuardianState.VPN_LOST &&
                activeMonitoringStartedElapsed > 0L &&
                now - activeMonitoringStartedElapsed >= 60_000L ->
                Triple(
                    "VPN_LOST",
                    "VPN 已丢失",
                    "努昂诺塔已停止全部 HTTPS 请求；请打开 Proton VPN 或 Tailscale。"
                )
            state == GuardianState.VPN_BLOCKED ||
                state == GuardianState.TAILSCALE_BLOCKED ->
                Triple(
                    state.name,
                    "VPN 网络被系统阻塞",
                    "Android 报告当前 VPN 为 blocked；努昂诺塔不会回落到直连。"
                )
            state == GuardianState.VPN_SUSPENDED ||
                state == GuardianState.TAILSCALE_SUSPENDED ->
                Triple(
                    state.name,
                    "VPN 网络已挂起",
                    "当前 VPN 缺少 NOT_SUSPENDED；已有 IP 不代表此刻仍能传输。"
                )
            state == GuardianState.TAILSCALE_SELF_EXCLUDED ->
                Triple(
                    state.name,
                    "努昂诺塔未走 Tailscale",
                    "Tailscale VPN 存在，但努昂诺塔默认网络不是该 handle；请检查应用分流设置。"
                )
            state == GuardianState.VPN_DNS_STALLED ||
                state == GuardianState.TAILSCALE_DNS_STALLED ->
                Triple(
                    state.name,
                    "VPN DNS 无响应",
                    "绑定当前 VPN Network 的 NO_CACHE DNS 探测连续失败；未进行任何直连回退。"
                )
            httpsDegraded ->
                Triple(
                    "HTTPS_DEGRADED",
                    "VPN HTTPS 路径已降级",
                    "VPN 仍在线，但连续 HTTPS 探测失败或最后一次 204 已过期；这不代表 FCM 状态。"
                )
            else -> null
        }
        val lastAlertedState = prefs.getString(
            LuonnotarPreferences.KEY_LAST_ALERTED_STATE,
            ""
        ).orEmpty()
        if (alert == null) {
            if (lastAlertedState.isNotEmpty()) {
                getSystemService(NotificationManager::class.java)
                    .cancel(NotificationChannelManager.ALERT_NOTIFICATION_ID)
                prefs.edit()
                    .remove(LuonnotarPreferences.KEY_LAST_ALERTED_STATE)
                    .remove(LuonnotarPreferences.KEY_LAST_ALERT_ELAPSED)
                    .apply()
            }
        } else {
            val lastAlertElapsed = prefs.getLong(
                LuonnotarPreferences.KEY_LAST_ALERT_ELAPSED,
                0L
            )
            if (
                lastAlertedState != alert.first &&
                (lastAlertElapsed == 0L || now - lastAlertElapsed >= ALERT_COOLDOWN_MS)
            ) {
                showAlert(alert.second, alert.third)
                prefs.edit()
                    .putString(LuonnotarPreferences.KEY_LAST_ALERTED_STATE, alert.first)
                    .putLong(LuonnotarPreferences.KEY_LAST_ALERT_ELAPSED, now)
                    .apply()
            }
        }
    }

    private fun persistNetworkEvidence(ignoreQuietWindow: Boolean = false) {
        if (!isCurrentServiceInstance()) return
        if (
            !ignoreQuietWindow &&
            quietWindowActive() &&
            vpnEvidence.present &&
            !(vpnEvidence.blockedKnown && vpnEvidence.blocked)
        ) {
            pendingNetworkEvidenceFlush = true
            return
        }
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val previousHandle = prefs.getLong(LuonnotarPreferences.KEY_NETWORK_HANDLE, -1)
        val previousInternetRouted = prefs.getBoolean(
            LuonnotarPreferences.KEY_VPN_INTERNET_ROUTED,
            false
        )
        val previousRouteState = runCatching {
            VpnRouteState.valueOf(
                prefs.getString(
                    LuonnotarPreferences.KEY_VPN_ROUTE_STATE,
                    if (previousInternetRouted) {
                        VpnRouteState.ROUTED.name
                    } else {
                        VpnRouteState.UNKNOWN.name
                    }
                ).orEmpty()
            )
        }.getOrDefault(VpnRouteState.UNKNOWN)
        val previousProvider = prefs.getString(
            LuonnotarPreferences.KEY_VPN_PROVIDER_PACKAGE,
            ""
        ).orEmpty()
        val currentProvider = vpnEvidence.providerPackage.orEmpty()
        val previousSessionFingerprint = prefs.getString(
            LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT,
            ""
        ).orEmpty()
        val sessionChanged =
            vpnEvidence.sessionFingerprint.isNotBlank() &&
                previousSessionFingerprint != vpnEvidence.sessionFingerprint
        val persistedSessionGeneration =
            prefs.getLong(
                LuonnotarPreferences.KEY_VPN_SESSION_GENERATION,
                0L
            ) + if (sessionChanged) 1L else 0L
        val handleChanged =
            previousHandle >= 0 && previousHandle != vpnEvidence.networkHandle
        val providerChanged =
            previousProvider.isNotBlank() &&
                currentProvider.isNotBlank() &&
                previousProvider != currentProvider
        val explicitRouteLost =
            vpnEvidence.routeState == VpnRouteState.NOT_ROUTED
        val routeChangedWithEvidence =
            vpnEvidence.routeState != VpnRouteState.UNKNOWN &&
                previousRouteState != vpnEvidence.routeState
        val editor = prefs.edit()
            .putBoolean(LuonnotarPreferences.KEY_VPN, vpnEvidence.present)
            .putBoolean(LuonnotarPreferences.KEY_VALIDATED, vpnEvidence.validated)
            .putBoolean(LuonnotarPreferences.KEY_BYPASSABLE_KNOWN, vpnEvidence.bypassable != null)
            .putBoolean(LuonnotarPreferences.KEY_BYPASSABLE, vpnEvidence.bypassable ?: true)
            .putString(
                LuonnotarPreferences.KEY_VPN_PROVIDER_PACKAGE,
                vpnEvidence.providerPackage
            )
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_INTERNET_ROUTED,
                vpnEvidence.internetRouted
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_ROUTE_STATE,
                vpnEvidence.routeState.name
            )
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_IPV4_DEFAULT_ROUTE,
                vpnEvidence.ipv4DefaultRoute
            )
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_IPV6_DEFAULT_ROUTE,
                vpnEvidence.ipv6DefaultRoute
            )
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_SESSION_COMPLETE,
                vpnEvidence.complete
            )
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_BLOCKED,
                vpnEvidence.blocked
            )
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_BLOCKED_KNOWN,
                vpnEvidence.blockedKnown
            )
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_NOT_SUSPENDED,
                vpnEvidence.notSuspended
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_SESSION_FINGERPRINT,
                vpnEvidence.sessionFingerprint
            )
            .putLong(
                LuonnotarPreferences.KEY_VPN_SESSION_GENERATION,
                persistedSessionGeneration
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_INTERFACE_NAME,
                vpnEvidence.interfaceName
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_LINK_ADDRESSES,
                vpnEvidence.linkAddresses.joinToString(",")
            )
            .putString(
                LuonnotarPreferences.KEY_VPN_DNS_SERVERS,
                vpnEvidence.dnsServers.joinToString(",")
            )
            .putInt(LuonnotarPreferences.KEY_VPN_MTU, vpnEvidence.mtu)
            .putString(
                LuonnotarPreferences.KEY_VPN_UNDERLYING_HANDLES,
                vpnEvidence.underlyingNetworkHandles.sorted().joinToString(",")
            )
            .putLong(LuonnotarPreferences.KEY_NETWORK_HANDLE, vpnEvidence.networkHandle)
            .putString(LuonnotarPreferences.KEY_TRANSPORT, networkEvidence.transport)
            .putString(
                LuonnotarPreferences.KEY_UNDERLAY_SOURCE,
                networkEvidence.underlaySource
            )
        if (vpnEvidence.present) {
            editor.putBoolean(LuonnotarPreferences.KEY_HAS_EVER_OBSERVED_VPN, true)
        }
        if (
            !vpnEvidence.present ||
            handleChanged ||
            providerChanged ||
            sessionChanged ||
            routeChangedWithEvidence
        ) {
            editor
                .remove(LuonnotarPreferences.KEY_HTTP_EVIDENCE_GENERATION)
                .remove(LuonnotarPreferences.KEY_SUCCESS_EVIDENCE_GENERATION)
                .remove(LuonnotarPreferences.KEY_ATTEMPT_EVIDENCE_GENERATION)
                .remove(LuonnotarPreferences.KEY_LAST_SUCCESS_RTT)
                .remove(LuonnotarPreferences.KEY_LAST_SUCCESS_ELAPSED)
                .remove(LuonnotarPreferences.KEY_LAST_SUCCESS_NETWORK_HANDLE)
                .remove(LuonnotarPreferences.KEY_LAST_ATTEMPT_RTT)
                .remove(LuonnotarPreferences.KEY_LAST_ATTEMPT_ELAPSED)
                .remove(LuonnotarPreferences.KEY_LAST_ATTEMPT_NETWORK_HANDLE)
                .remove(
                    LuonnotarPreferences.KEY_LAST_ATTEMPT_SESSION_FINGERPRINT
                )
                .remove(
                    LuonnotarPreferences.KEY_LAST_SUCCESS_SESSION_FINGERPRINT
                )
                .remove(LuonnotarPreferences.KEY_LAST_HTTP_CODE)
                .remove(LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES)
                .remove(
                    LuonnotarPreferences.KEY_VPN_DNS_LAST_ATTEMPT_ELAPSED
                )
                .remove(
                    LuonnotarPreferences.KEY_VPN_DNS_LAST_SUCCESS_ELAPSED
                )
                .remove(LuonnotarPreferences.KEY_VPN_DNS_LAST_RTT)
                .remove(LuonnotarPreferences.KEY_VPN_DNS_LAST_ERROR)
                .remove(LuonnotarPreferences.KEY_VPN_DNS_FAILURES)
                .remove(
                    LuonnotarPreferences.KEY_VPN_DNS_EVIDENCE_SESSION_FINGERPRINT
                )
                .remove(LuonnotarPreferences.KEY_MTALK_LAST_ATTEMPT_ELAPSED)
                .remove(
                    LuonnotarPreferences.KEY_MTALK_LAST_SESSION_FINGERPRINT
                )
                .remove(LuonnotarPreferences.KEY_MTALK_IPV4_DNS)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV6_DNS)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5228)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5229)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV4_TCP_5230)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV4_TCP_443)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5228)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5229)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV6_TCP_5230)
                .remove(LuonnotarPreferences.KEY_MTALK_IPV6_TCP_443)
                .remove(LuonnotarPreferences.KEY_MTALK_RESULT_SUMMARY)
        }
        if (
            !vpnEvidence.present ||
            handleChanged ||
            providerChanged ||
            sessionChanged ||
            explicitRouteLost
        ) {
            editor
                .remove(LuonnotarPreferences.KEY_ADB_VERIFIED_WALL)
                .remove(LuonnotarPreferences.KEY_ADB_VERIFIED_ELAPSED)
                .remove(LuonnotarPreferences.KEY_ADB_VERIFIED_BOOT_ID)
                .remove(LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE)
                .remove(LuonnotarPreferences.KEY_ADB_ALWAYS_ON)
                .remove(LuonnotarPreferences.KEY_ADB_LOCKDOWN)
                .remove(LuonnotarPreferences.KEY_ADB_BYPASSABLE)
                .remove(LuonnotarPreferences.KEY_ADB_GMS_ROUTED)
                .remove(LuonnotarPreferences.KEY_ADB_WHATSAPP_ROUTED)
                .remove(LuonnotarPreferences.KEY_ADB_WHATSAPP_BUSINESS_ROUTED)
                .remove(LuonnotarPreferences.KEY_ADB_INTERNET_ROUTED)
                .remove(LuonnotarPreferences.KEY_ADB_EVIDENCE_HASH)
                .remove(LuonnotarPreferences.KEY_ADB_NETWORK_HANDLE)
                .remove(LuonnotarPreferences.KEY_ADB_SESSION_FINGERPRINT)
        }
        if (sessionChanged) {
            LogManager.timeline(
                this,
                "vpn_session_rebuilt",
                mapOf(
                    "previousSessionFingerprint" to
                        previousSessionFingerprint,
                    "sessionFingerprint" to
                        vpnEvidence.sessionFingerprint,
                    "sessionGeneration" to persistedSessionGeneration,
                    "networkHandle" to vpnEvidence.networkHandle
                )
            )
        }
        editor.apply()
        pendingNetworkEvidenceFlush = false
    }

    private fun probeNetworkIsCurrent(
        connectivity: ConnectivityManager,
        capturedHandle: Long
    ): Boolean = VpnProbeResultPolicy.accepts(
        capturedNetworkHandle = capturedHandle,
        currentVpnNetworkHandle = vpnEvidence.networkHandle,
        activeNetworkHandle =
            connectivity.activeNetwork?.networkHandle ?: -1L,
        vpnPresent = vpnEvidence.present
    )

    private fun probeSessionIsCurrent(
        connectivity: ConnectivityManager,
        capturedHandle: Long,
        capturedSessionFingerprint: String
    ): Boolean =
        capturedSessionFingerprint.isNotBlank() &&
            vpnEvidence.sessionFingerprint == capturedSessionFingerprint &&
            probeNetworkIsCurrent(connectivity, capturedHandle)

    private fun effectiveBypassability(
        prefs: android.content.SharedPreferences,
        nowElapsed: Long
    ): Boolean? {
        val activePackage = prefs.getString(
            LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE,
            ""
        ).orEmpty()
        if (!SupportedVpnProvider.isSupported(activePackage)) return vpnEvidence.bypassable
        val current = AdbVpnEvidencePolicy.isCurrent(
            verifiedElapsed = prefs.getLong(
                LuonnotarPreferences.KEY_ADB_VERIFIED_ELAPSED,
                0L
            ),
            nowElapsed = nowElapsed,
            verifiedBootId = prefs.getString(
                LuonnotarPreferences.KEY_ADB_VERIFIED_BOOT_ID,
                ""
            ).orEmpty(),
            currentBootId = readBootId(),
            activePackage = activePackage,
            evidenceHash = prefs.getString(
                LuonnotarPreferences.KEY_ADB_EVIDENCE_HASH,
                ""
            ).orEmpty(),
            verifiedNetworkHandle = prefs.getLong(
                LuonnotarPreferences.KEY_ADB_NETWORK_HANDLE,
                -1L
            ),
            currentNetworkHandle = vpnEvidence.networkHandle,
            vpnPresent = vpnEvidence.present,
            verifiedSessionFingerprint = prefs.getString(
                LuonnotarPreferences.KEY_ADB_SESSION_FINGERPRINT,
                ""
            ).orEmpty(),
            currentSessionFingerprint = vpnEvidence.sessionFingerprint,
            currentProviderPackage = vpnEvidence.providerPackage
        )
        return if (current) {
            prefs.getBoolean(LuonnotarPreferences.KEY_ADB_BYPASSABLE, true)
        } else {
            vpnEvidence.bypassable
        }
    }

    private fun targetRoutingVerified(
        prefs: android.content.SharedPreferences,
        nowElapsed: Long
    ): Boolean =
        adbEvidenceIsCurrent(prefs, nowElapsed) &&
            SupportedVpnProvider.isSupported(
                prefs.getString(
                    LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE,
                    ""
                ).orEmpty()
            ) &&
            TargetRoutingPolicy.isVerified(
                TargetRoutingSnapshot(
                    monitored = true,
                    active = true,
                    routed = prefs.getBoolean(
                        LuonnotarPreferences.KEY_ADB_INTERNET_ROUTED,
                        false
                    )
                ),
                TargetRoutingSnapshot(
                    monitored = prefs.getBoolean(LuonnotarPreferences.KEY_MONITOR_GMS, true),
                    active = isActiveUserTarget("com.google.android.gms"),
                    routed = prefs.getBoolean(LuonnotarPreferences.KEY_ADB_GMS_ROUTED, false)
                ),
                TargetRoutingSnapshot(
                    monitored = prefs.getBoolean(LuonnotarPreferences.KEY_MONITOR_WHATSAPP, true),
                    active = isActiveUserTarget("com.whatsapp"),
                    routed = prefs.getBoolean(LuonnotarPreferences.KEY_ADB_WHATSAPP_ROUTED, false)
                ),
                TargetRoutingSnapshot(
                    monitored = prefs.getBoolean(LuonnotarPreferences.KEY_MONITOR_WHATSAPP_BUSINESS, false),
                    active = isActiveUserTarget("com.whatsapp.w4b"),
                    routed = prefs.getBoolean(LuonnotarPreferences.KEY_ADB_WHATSAPP_BUSINESS_ROUTED, false)
                )
            )

    private fun isActiveUserTarget(packageName: String): Boolean {
        if (android.os.Process.myUid() / 100_000 != 0) return false
        return runCatching {
            @Suppress("DEPRECATION")
            val info = packageManager.getApplicationInfo(
                packageName,
                android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS
            )
            info.enabled &&
                (info.flags and
                    android.content.pm.ApplicationInfo.FLAG_SUSPENDED) == 0 &&
                (info.flags and
                    android.content.pm.ApplicationInfo.FLAG_STOPPED) == 0
        }.getOrDefault(false)
    }

    private fun adbEvidenceIsCurrent(
        prefs: android.content.SharedPreferences,
        nowElapsed: Long
    ): Boolean = AdbVpnEvidencePolicy.isCurrent(
        verifiedElapsed = prefs.getLong(LuonnotarPreferences.KEY_ADB_VERIFIED_ELAPSED, 0L),
        nowElapsed = nowElapsed,
        verifiedBootId = prefs.getString(
            LuonnotarPreferences.KEY_ADB_VERIFIED_BOOT_ID,
            ""
        ).orEmpty(),
        currentBootId = readBootId(),
        activePackage = prefs.getString(
            LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE,
            ""
        ).orEmpty(),
        evidenceHash = prefs.getString(
            LuonnotarPreferences.KEY_ADB_EVIDENCE_HASH,
            ""
        ).orEmpty(),
        verifiedNetworkHandle = prefs.getLong(
            LuonnotarPreferences.KEY_ADB_NETWORK_HANDLE,
            -1L
        ),
        currentNetworkHandle = vpnEvidence.networkHandle,
        vpnPresent = vpnEvidence.present,
        verifiedSessionFingerprint = prefs.getString(
            LuonnotarPreferences.KEY_ADB_SESSION_FINGERPRINT,
            ""
        ).orEmpty(),
        currentSessionFingerprint = vpnEvidence.sessionFingerprint,
        currentProviderPackage = vpnEvidence.providerPackage
    )

    private fun readBootId(): String = bootId

    private fun restoreWifiUnderlayHistory(
        prefs: android.content.SharedPreferences
    ): WifiUnderlayHistory {
        val restored = WifiUnderlayLockPolicy.restoreHistory(
            storedHistory = WifiUnderlayHistory(
                lastExplicitUnderlay = prefs.getString(
                    LuonnotarPreferences.KEY_LAST_EXPLICIT_UNDERLAY,
                    "NONE"
                ) ?: "NONE",
                lastWifiSeenElapsed = prefs.getLong(
                    LuonnotarPreferences.KEY_LAST_WIFI_SEEN_ELAPSED,
                    0L
                ),
                unknownSinceElapsed = prefs.getLong(
                    LuonnotarPreferences.KEY_UNDERLAY_UNKNOWN_SINCE,
                    0L
                )
            ),
            storedBootId = prefs.getString(
                LuonnotarPreferences.KEY_UNDERLAY_HISTORY_BOOT_ID,
                ""
            ),
            runtimeBootId = prefs.getString(
                LuonnotarPreferences.KEY_RUNTIME_BOOT_ID,
                ""
            ),
            currentBootId = readBootId(),
            nowElapsed = SystemClock.elapsedRealtime()
        )
        LogManager.event(
            this,
            "wifi_underlay_history_restored",
            mapOf(
                "restored" to (restored != WifiUnderlayHistory()),
                "lastExplicitUnderlay" to restored.lastExplicitUnderlay,
                "lastWifiSeenElapsed" to restored.lastWifiSeenElapsed,
                "unknownSinceElapsed" to restored.unknownSinceElapsed
            )
        )
        return restored
    }

    private fun createLocks() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:guardian_cpu_continuous"
        ).apply {
            setReferenceCounted(false)
        }
        scopedWakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:guardian_cpu_scoped"
        ).apply {
            setReferenceCounted(false)
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:guardian_wifi").apply {
            setReferenceCounted(false)
        }
    }

    @Synchronized
    private fun reconcileCpuLockPolicy(
        reason: String,
        screenInteractiveOverride: Boolean? = null
    ) {
        if (!::wakeLock.isInitialized) return
        val settings = runtimeSettings()
        val powerManager = getSystemService(PowerManager::class.java)
        val screenInteractive =
            screenInteractiveOverride ?: powerManager.isInteractive
        val decision = GuardianPowerPolicy.decide(
            GuardianPowerInput(
                guardianActive = isActivelyEnabled(),
                currentService = isCurrentServiceInstance(),
                profile = settings.profile,
                screenInteractive = screenInteractive,
                screenOffCpuGuard =
                    settings.experiments.screenOffCpuGuard,
                labPermanentCpuLock =
                    settings.experiments.permanentCpuLock
            )
        )
        if (decision.holdCpuLock && !wakeLock.isHeld) {
            wakeLock.acquire()
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, true)
                .putBoolean(
                    LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                    true
                )
                .apply()
            LogManager.event(
                this,
                if (
                    decision.scope.startsWith(
                        "screen_off_cpu_guard"
                    )
                ) {
                    "screen_off_cpu_guard_acquired"
                } else {
                    "wake_lock_acquired"
                },
                mapOf(
                    "reason" to reason,
                    "scope" to decision.scope
                )
            )
            LogManager.timeline(
                this,
                "wake_lock_state_changed",
                mapOf(
                    "wakeLockHeld" to true,
                    "continuousWakeLockHeld" to true,
                    "scopedWakeLockHeld" to
                        (::scopedWakeLock.isInitialized &&
                            scopedWakeLock.isHeld),
                    "lockChangeReason" to reason,
                    "scope" to decision.scope,
                    "screenInteractive" to screenInteractive
                )
            )
        } else if (!decision.holdCpuLock && wakeLock.isHeld) {
            wakeLock.release()
            val anyCpuLockHeld =
                ::scopedWakeLock.isInitialized && scopedWakeLock.isHeld
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(
                    LuonnotarPreferences.KEY_WAKE_LOCK,
                    anyCpuLockHeld
                )
                .putBoolean(
                    LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                    false
                )
                .apply()
            LogManager.event(
                this,
                if (
                    decision.scope.startsWith(
                        "screen_off_cpu_guard"
                    )
                ) {
                    "screen_off_cpu_guard_released"
                } else {
                    "wake_lock_released"
                },
                mapOf(
                    "reason" to reason,
                    "scope" to decision.scope
                )
            )
            LogManager.timeline(
                this,
                "wake_lock_state_changed",
                mapOf(
                    "wakeLockHeld" to anyCpuLockHeld,
                    "continuousWakeLockHeld" to false,
                    "scopedWakeLockHeld" to anyCpuLockHeld,
                    "lockChangeReason" to reason,
                    "scope" to decision.scope,
                    "screenInteractive" to screenInteractive
                )
            )
        }
    }

    private inline fun <T> withScopedCpuLock(
        reason: String,
        block: () -> T
    ): T {
        val settings = runtimeSettings()
        if (!settings.experiments.scopedCpuLock) {
            return block()
        }
        var acquired = false
        synchronized(this) {
            if (
                isActivelyEnabled() &&
                isCurrentServiceInstance() &&
                !wakeLock.isHeld &&
                !scopedWakeLock.isHeld
            ) {
                scopedWakeLock.acquire(
                    GuardianProfilePolicy.SCOPED_CPU_LOCK_TIMEOUT_MS
                )
                acquired = true
                LuonnotarPreferences.deviceProtected(this).edit()
                    .putBoolean(
                        LuonnotarPreferences.KEY_WAKE_LOCK,
                        true
                    )
                    .apply()
                LogManager.timeline(
                    this,
                    "wake_lock_state_changed",
                    mapOf(
                        "wakeLockHeld" to true,
                        "continuousWakeLockHeld" to false,
                        "scopedWakeLockHeld" to true,
                        "lockChangeReason" to reason,
                        "scope" to "scoped_10s"
                    )
                )
            }
        }
        return try {
            block()
        } finally {
            if (acquired) {
                synchronized(this) {
                    if (scopedWakeLock.isHeld) {
                        scopedWakeLock.release()
                    }
                    val anyCpuLockHeld = wakeLock.isHeld
                    LuonnotarPreferences.deviceProtected(this).edit()
                        .putBoolean(
                            LuonnotarPreferences.KEY_WAKE_LOCK,
                            anyCpuLockHeld
                        )
                        .apply()
                    LogManager.timeline(
                        this,
                        "wake_lock_state_changed",
                        mapOf(
                            "wakeLockHeld" to anyCpuLockHeld,
                            "continuousWakeLockHeld" to
                                wakeLock.isHeld,
                            "scopedWakeLockHeld" to false,
                            "lockChangeReason" to reason,
                            "scope" to "scoped_complete"
                        )
                    )
                }
            }
        }
    }

    @Synchronized
    private fun reconcileWifiLock() {
        if (!isCurrentServiceInstance()) return
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val active = isActivelyEnabled(prefs)
        val allowHighPerformance =
            runtimeSettings().experiments.highPerfWifiLock
        val nowElapsed = SystemClock.elapsedRealtime()
        val previousHistory = wifiUnderlayHistory
        val previousLockHeld = wifiLock.isHeld
        val decision = WifiUnderlayLockPolicy.decide(
            guardianActive = active && allowHighPerformance,
            observedUnderlay = networkEvidence.transport,
            nowElapsed = nowElapsed,
            lockCurrentlyHeld = wifiLock.isHeld,
            history = wifiUnderlayHistory
        )
        wifiUnderlayHistory = decision.history
        if (decision.shouldHoldLock && !wifiLock.isHeld) {
            wifiLock.acquire()
            LogManager.event(
                this,
                "wifi_lock_acquired",
                mapOf(
                    "reason" to decision.reason,
                    "underlay" to networkEvidence.transport,
                    "underlaySource" to networkEvidence.underlaySource
                )
            )
            LogManager.timeline(
                this,
                "wifi_lock_state_changed",
                mapOf(
                    "wifiLockHeld" to true,
                    "lockChangeReason" to decision.reason,
                    "underlaySource" to networkEvidence.underlaySource,
                    "lastExplicitUnderlay" to
                        wifiUnderlayHistory.lastExplicitUnderlay,
                    "unknownDurationMs" to decision.unknownDurationMs
                )
            )
        } else if (!decision.shouldHoldLock && wifiLock.isHeld) {
            wifiLock.release()
            LogManager.event(
                this,
                "wifi_lock_released",
                mapOf(
                    "reason" to decision.reason,
                    "underlay" to networkEvidence.transport,
                    "underlaySource" to networkEvidence.underlaySource
                )
            )
            LogManager.timeline(
                this,
                "wifi_lock_state_changed",
                mapOf(
                    "wifiLockHeld" to false,
                    "lockChangeReason" to decision.reason,
                    "underlaySource" to networkEvidence.underlaySource,
                    "lastExplicitUnderlay" to
                        wifiUnderlayHistory.lastExplicitUnderlay,
                    "unknownDurationMs" to decision.unknownDurationMs
                )
            )
        }
        if (
            networkEvidence.transport == "UNDERLAY_UNKNOWN" &&
            (
                lastUnderlayDiagnosticElapsed == 0L ||
                    nowElapsed - lastUnderlayDiagnosticElapsed >= 30_000L
                )
        ) {
            lastUnderlayDiagnosticElapsed = nowElapsed
            LogManager.event(
                this,
                "wifi_underlay_unknown_hysteresis",
                mapOf(
                    "underlaySource" to networkEvidence.underlaySource,
                    "lastExplicitUnderlay" to
                        wifiUnderlayHistory.lastExplicitUnderlay,
                    "unknownDurationMs" to decision.unknownDurationMs,
                    "wifiLockHeld" to wifiLock.isHeld,
                    "decisionReason" to decision.reason
                )
            )
        } else if (networkEvidence.transport != "UNDERLAY_UNKNOWN") {
            lastUnderlayDiagnosticElapsed = 0L
        }
        val historyChanged = previousHistory != wifiUnderlayHistory
        val lockChanged = previousLockHeld != wifiLock.isHeld
        if (
            quietWindowActive() &&
            !historyChanged &&
            !lockChanged
        ) return
        val historyEditor = prefs.edit()
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, wifiLock.isHeld)
            .putString(
                LuonnotarPreferences.KEY_LAST_EXPLICIT_UNDERLAY,
                wifiUnderlayHistory.lastExplicitUnderlay
            )
            .putLong(
                LuonnotarPreferences.KEY_LAST_WIFI_SEEN_ELAPSED,
                wifiUnderlayHistory.lastWifiSeenElapsed
            )
            .putLong(
                LuonnotarPreferences.KEY_UNDERLAY_UNKNOWN_SINCE,
                wifiUnderlayHistory.unknownSinceElapsed
            )
        val currentBootId = readBootId()
        if (currentBootId.isNotBlank() && currentBootId != "unavailable") {
            historyEditor.putString(
                LuonnotarPreferences.KEY_UNDERLAY_HISTORY_BOOT_ID,
                currentBootId
            )
        } else {
            historyEditor
                .remove(LuonnotarPreferences.KEY_LAST_EXPLICIT_UNDERLAY)
                .remove(LuonnotarPreferences.KEY_LAST_WIFI_SEEN_ELAPSED)
                .remove(LuonnotarPreferences.KEY_UNDERLAY_UNKNOWN_SINCE)
                .remove(LuonnotarPreferences.KEY_UNDERLAY_HISTORY_BOOT_ID)
        }
        historyEditor.apply()
    }

    @Synchronized
    private fun releaseLocks(reason: String) {
        val wifiWasHeld = ::wifiLock.isInitialized && wifiLock.isHeld
        val continuousWakeWasHeld =
            ::wakeLock.isInitialized && wakeLock.isHeld
        val scopedWakeWasHeld =
            ::scopedWakeLock.isInitialized && scopedWakeLock.isHeld
        if (wifiWasHeld) wifiLock.release()
        if (continuousWakeWasHeld) wakeLock.release()
        if (scopedWakeWasHeld) scopedWakeLock.release()
        LuonnotarPreferences.deviceProtected(this).edit()
            .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
            .putBoolean(
                LuonnotarPreferences.KEY_CONTINUOUS_WAKE_LOCK,
                false
            )
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
            .apply()
        if (
            wifiWasHeld ||
            continuousWakeWasHeld ||
            scopedWakeWasHeld
        ) {
            LogManager.timeline(
                this,
                "guardian_locks_released",
                mapOf(
                    "wakeLockHeld" to false,
                    "wifiLockHeld" to false,
                    "previousWakeLockHeld" to
                        (continuousWakeWasHeld || scopedWakeWasHeld),
                    "previousContinuousWakeLockHeld" to
                        continuousWakeWasHeld,
                    "previousScopedWakeLockHeld" to scopedWakeWasHeld,
                    "previousWifiLockHeld" to wifiWasHeld,
                    "lockChangeReason" to reason
                )
            )
        }
    }

    private fun quiesceGuardianExecution(reason: String) {
        GmsBinderAnchorCoordinator.stop(this, reason)
        GmsBinderPulseCoordinator.stop(this, reason)
        GmsImportanceFenceCoordinator.stop(this, reason)
        var connectionToDisconnect: HttpsURLConnection? = null
        var dnsSocketToClose: DatagramSocket? = null
        var dnsCancellationToCancel: CancellationSignal? = null
        var mtalkSocketToClose: Socket? = null
        synchronized(probeLifecycleLock) {
            probeRequestGate.advanceGeneration(
                recoveryEpoch.incrementAndGet()
            )
            pendingForcedPlan = ProbePlan.DNS
            scheduled?.cancel(true)
            scheduled = null
            startupProbeFuture?.cancel(true)
            startupProbeFuture = null
            startupProbeScheduled.set(false)
            connectionToDisconnect = activeConnection
            activeConnection = null
            dnsSocketToClose = activeDnsSocket
            activeDnsSocket = null
            dnsCancellationToCancel = activeDnsCancellation
            activeDnsCancellation = null
            mtalkSocketToClose = activeMtalkSocket
            activeMtalkSocket = null
            activeProbeStage = ProbeStage.IDLE
            activeProbeStageStartedElapsed = 0L
            processProbeRetryScheduled.set(false)
            persistProbeGateSnapshotLocked()
        }
        dnsCancellationToCancel?.cancel()
        dnsSocketToClose?.close()
        runCatching { mtalkSocketToClose?.close() }
        connectionToDisconnect?.disconnect()
        wifiUnderlayHistory = wifiUnderlayHistory.copy(
            unknownSinceElapsed = 0L
        )
        LuonnotarPreferences.deviceProtected(this).edit()
            .putLong(
                LuonnotarPreferences.KEY_UNDERLAY_UNKNOWN_SINCE,
                0L
            )
            .commit()
        if (::persistentNetworkLease.isInitialized) {
            persistentNetworkLease.release(reason)
        }
        if (::persistentHeartbeatSocketLease.isInitialized) {
            persistentHeartbeatSocketLease.stop(reason)
        }
        releaseLocks(reason)
    }

    private fun stopServiceIfStillLatest(
        originStartId: Int,
        reason: String
    ): Boolean {
        val stopped = stopSelfResult(originStartId)
        if (stopped) {
            ServiceCompat.stopForeground(
                this,
                ServiceCompat.STOP_FOREGROUND_REMOVE
            )
        } else {
            LogManager.event(
                this,
                "guardian_stale_stop_ignored",
                mapOf(
                    "reason" to reason,
                    "originStartId" to originStartId,
                    "latestDeliveredStartId" to latestStartId.get()
                )
            )
        }
        return stopped
    }
    private fun stopForAuthoritativeDisable() {
        if (!authoritativeStopPending.compareAndSet(false, true)) return
        val posted = mainHandler.post {
            try {
                stopForAuthoritativeDisableOnMainThread()
            } finally {
                authoritativeStopPending.set(false)
            }
        }
        if (!posted) {
            authoritativeStopPending.set(false)
            LogManager.event(
                this,
                "guardian_authoritative_stop_post_failed"
            )
        }
    }

    private fun stopForAuthoritativeDisableOnMainThread() {
        if (destroyed.get() || stopping.get()) return
        val prefs = LuonnotarPreferences.deviceProtected(this)
        if (prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)) {
            LogManager.event(
                this,
                "guardian_authoritative_stop_cancelled",
                mapOf("reason" to "enabled_again")
            )
            return
        }
        val originStartId = latestStartId.get()
        if (originStartId <= 0) {
            LogManager.event(
                this,
                "guardian_authoritative_stop_deferred",
                mapOf("reason" to "start_id_not_delivered")
            )
            return
        }
        stopping.set(true)
        val stopped = stopSelfResult(originStartId)
        if (!stopped) {
            val activeAgain =
                prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) &&
                    !prefs.getBoolean(
                        LuonnotarPreferences.KEY_PAUSED,
                        false
                    )
            stopping.set(!activeAgain)
            LogManager.event(
                this,
                "guardian_stale_stop_ignored",
                mapOf(
                    "reason" to "authoritative_disable",
                    "originStartId" to originStartId,
                    "latestDeliveredStartId" to latestStartId.get(),
                    "activeAgain" to activeAgain
                )
            )
            return
        }
        LogManager.event(
            this,
            "guardian_self_stop_disabled_state",
            mapOf("originStartId" to originStartId)
        )
        quiesceGuardianExecution("authoritative_disable")
        LabAlarmScheduler.cancel(this)
        cancelTransientNotifications()
        prefs.edit()
            .putInt(LuonnotarPreferences.KEY_PID, 0)
            .remove(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
            .remove(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED)
            .remove(LuonnotarPreferences.KEY_LAST_ALERTED_STATE)
            .remove(LuonnotarPreferences.KEY_LAST_ALERT_ELAPSED)
            .putString(
                LuonnotarPreferences.KEY_STATE,
                GuardianState.DISABLED.name
            )
            .commit()
        sendBroadcast(
            Intent(this, GuardianCleanupReceiver::class.java)
                .setAction(GuardianCleanupReceiver.ACTION_CLEANUP_DISABLED)
        )
        ServiceCompat.stopForeground(
            this,
            ServiceCompat.STOP_FOREGROUND_REMOVE
        )
    }

    private fun stopForPassiveMode() {
        val prefs = LuonnotarPreferences.deviceProtected(this)
        prefs.edit()
            .putBoolean(LuonnotarPreferences.KEY_ENABLED, false)
            .putString(
                LuonnotarPreferences.KEY_LAST_SERVICE_EXIT,
                "adb_passive_window_complete"
            )
            .apply()
        LogManager.timeline(
            this,
            "adb_passive_window_complete",
            mapOf("activeWindowMs" to 60_000L)
        )
        stopForAuthoritativeDisable()
    }

    @Synchronized
    private fun recoverInternalSchedulers(
        reason: String,
        expectedEpoch: Long? = null
    ) {
        if (
            expectedEpoch != null &&
            !isEpochCurrent(expectedEpoch)
        ) return
        if (!isActivelyEnabled()) return
        val requestedElapsed = SystemClock.elapsedRealtime()
        var connectionToDisconnect: HttpsURLConnection? = null
        var dnsSocketToClose: DatagramSocket? = null
        var dnsCancellationToCancel: CancellationSignal? = null
        var mtalkSocketToClose: Socket? = null
        val oldExecutors = synchronized(probeLifecycleLock) {
            if (
                expectedEpoch != null &&
                !isEpochCurrent(expectedEpoch)
            ) return
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(
                    LuonnotarPreferences.KEY_RECOVERY_CONFIRMATION_PENDING,
                    true
                )
                .putLong(
                    LuonnotarPreferences.KEY_RECOVERY_REQUESTED_ELAPSED,
                    requestedElapsed
                )
                .commit()
            scheduled?.cancel(true)
            scheduled = null
            startupProbeFuture?.cancel(true)
            startupProbeFuture = null
            startupProbeScheduled.set(false)
            connectionToDisconnect = activeConnection
            activeConnection = null
            dnsSocketToClose = activeDnsSocket
            activeDnsSocket = null
            dnsCancellationToCancel = activeDnsCancellation
            activeDnsCancellation = null
            mtalkSocketToClose = activeMtalkSocket
            activeMtalkSocket = null
            activeProbeStage = ProbeStage.IDLE
            activeProbeStageStartedElapsed = 0L
            val oldScheduler = scheduler
            val oldProbeExecutor = probeExecutor
            val newEpoch = recoveryEpoch.incrementAndGet()
            scheduler = Executors.newSingleThreadScheduledExecutor()
            probeExecutor = Executors.newSingleThreadExecutor()
            probeRequestGate.advanceGeneration(newEpoch)
            pendingForcedPlan = ProbePlan.DNS
            lastKeepaliveAttemptElapsed.set(0L)
            processProbeRetryScheduled.set(false)
            persistProbeGateSnapshotLocked()
            oldScheduler to oldProbeExecutor
        }
        connectionToDisconnect?.disconnect()
        dnsCancellationToCancel?.cancel()
        dnsSocketToClose?.close()
        runCatching { mtalkSocketToClose?.close() }
        LogManager.event(
            this,
            "guardian_internal_recovery_started",
            mapOf("reason" to reason)
        )
        oldExecutors.first.shutdownNow()
        oldExecutors.second.shutdownNow()
        lastExpectedTickElapsed = SystemClock.elapsedRealtime()
        scheduleTicks()
        restartStartupAggregation()
    }

    private fun confirmRecoveryIfPending(
        prefs: android.content.SharedPreferences,
        completedElapsed: Long
    ) {
        if (!prefs.getBoolean(LuonnotarPreferences.KEY_RECOVERY_CONFIRMATION_PENDING, false)) {
            return
        }
        val requestedElapsed = prefs.getLong(
            LuonnotarPreferences.KEY_RECOVERY_REQUESTED_ELAPSED,
            0L
        )
        val heartbeatElapsed = prefs.getLong(
            LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED,
            0L
        )
        if (
            requestedElapsed <= 0L ||
            heartbeatElapsed < requestedElapsed ||
            completedElapsed < requestedElapsed
        ) return
        prefs.edit()
            .putBoolean(LuonnotarPreferences.KEY_RECOVERY_CONFIRMATION_PENDING, false)
            .remove(LuonnotarPreferences.KEY_RECOVERY_REQUESTED_ELAPSED)
            .putLong(
                LuonnotarPreferences.KEY_LAST_RECOVERY_SUCCESS_ELAPSED,
                completedElapsed
            )
            .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE)
            .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE_ELAPSED)
            .commit()
        getSystemService(NotificationManager::class.java)
            .cancel(NotificationChannelManager.RECOVERY_NOTIFICATION_ID)
        LogManager.event(
            this,
            "guardian_internal_recovery_confirmed",
            mapOf("completedElapsed" to completedElapsed)
        )
    }

    private fun pauseGuardian(startId: Int): Boolean {
        stopping.set(true)
        disconnectActiveConnection()
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val committed = prefs.edit()
                .putBoolean(LuonnotarPreferences.KEY_PAUSED, true)
                .putString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "paused")
                .putInt(LuonnotarPreferences.KEY_PID, 0)
                .remove(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
                .remove(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED)
                .remove(LuonnotarPreferences.KEY_LAST_TICK_ELAPSED)
                .remove(LuonnotarPreferences.KEY_LAST_TICK_UPTIME)
                .remove(LuonnotarPreferences.KEY_LAST_ALERTED_STATE)
                .remove(LuonnotarPreferences.KEY_LAST_ALERT_ELAPSED)
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE)
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE_ELAPSED)
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM)
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED)
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION)
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_NOTIFICATION_ELAPSED)
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT)
                .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT_ELAPSED)
                .commit()
        if (!committed) {
            stopping.set(false)
            persistError("pause:SharedPreferencesCommitFailed")
            LogManager.event(this, "guardian_pause_commit_failed")
            updateNotification()
            return false
        }
        activeMonitoringStartedElapsed = 0L
        quiesceGuardianExecution("guardian_paused")
        LabAlarmScheduler.cancel(this)
        cancelTransientNotifications()
        sendBroadcast(
            Intent(this, GuardianCleanupReceiver::class.java)
                .setAction(GuardianCleanupReceiver.ACTION_CANCEL_PAUSED)
        )
        updateStateAndAlerts()
        LogManager.event(this, "guardian_fully_paused")
        stopServiceIfStillLatest(startId, "guardian_paused")
        return true
    }

    private fun resumeGuardian(): Boolean {
        stopping.set(false)
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val resumedElapsed = SystemClock.elapsedRealtime()
        val committed = prefs.edit()
                .putBoolean(LuonnotarPreferences.KEY_PAUSED, false)
                .putString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "")
                .putString(LuonnotarPreferences.KEY_LAST_START_REASON, "resume")
                .putInt(
                    LuonnotarPreferences.KEY_PID,
                    Process.myPid()
                )
                .putLong(
                    LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED,
                    startedElapsed
                )
                .putLong(
                    LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED,
                    resumedElapsed
                )
                .putString(
                    LuonnotarPreferences.KEY_STATE,
                    GuardianState.STARTING.name
                )
                .commit()
        if (!committed) {
            persistError("resume:SharedPreferencesCommitFailed")
            LogManager.event(this, "guardian_resume_commit_failed")
            return false
        }
        heartbeatElapsed = resumedElapsed
        lastHeartbeatPersistedElapsed = resumedElapsed
        activeMonitoringStartedElapsed = resumedElapsed
        showForeground(GuardianState.STARTING, "正在恢复守护…")
        reconcileCpuLockPolicy("resume")
        reconcileWifiLock()
        reconcilePersistentTransports()
        GmsBinderAnchorCoordinator.reconcile(this, true)
        scheduleTicks()
        if (!LabAlarmScheduler.scheduleNext(this)) {
            prefs.edit()
                .putString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM,
                    "继续保活成功，但恢复闹钟安排失败"
                )
                .putLong(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_ALARM_ELAPSED,
                    SystemClock.elapsedRealtime()
                )
                .apply()
        }
        sendBroadcast(
            Intent(this, GuardianCleanupReceiver::class.java)
                .setAction(GuardianCleanupReceiver.ACTION_ENSURE_ENABLED)
                .putExtra(
                    GuardianCleanupReceiver.EXTRA_EXPECTED_GENERATION,
                    serviceGeneration
                )
                .putExtra(
                    GuardianCleanupReceiver.EXTRA_EXPECTED_BOOT_ID,
                    bootId
                )
        )
        restartStartupAggregation()
        updateStateAndAlerts()
        updateNotification()
        LogManager.event(this, "guardian_resumed")
        return true
    }

    private fun observeVpnPolicySettings() {
        val prefs = LuonnotarPreferences.deviceProtected(this)
        fun readSecure(name: String): Int? = runCatching {
            Settings.Secure.getInt(contentResolver, name)
        }.getOrNull()
        val lockdown = readSecure("always_on_vpn_lockdown")
        val alwaysOnPackage = runCatching {
            Settings.Secure.getString(contentResolver, "always_on_vpn_app")
        }.getOrNull()
        val lockdownKnown = lockdown != null
        val lockdownEnabled = lockdown == 1
        val alwaysOnKnown = alwaysOnPackage != null
        val alwaysOnSupported =
            SupportedVpnProvider.isSupported(alwaysOnPackage)
        if (
            prefs.getBoolean(
                LuonnotarPreferences.KEY_LOCKDOWN_KNOWN,
                false
            ) == lockdownKnown &&
            prefs.getBoolean(
                LuonnotarPreferences.KEY_LOCKDOWN,
                false
            ) == lockdownEnabled &&
            prefs.getBoolean(
                LuonnotarPreferences.KEY_ALWAYS_ON_KNOWN,
                false
            ) == alwaysOnKnown &&
            prefs.getBoolean(
                LuonnotarPreferences.KEY_ALWAYS_ON,
                false
            ) == alwaysOnSupported
        ) return
        prefs.edit()
            .putBoolean(
                LuonnotarPreferences.KEY_LOCKDOWN_KNOWN,
                lockdownKnown
            )
            .putBoolean(
                LuonnotarPreferences.KEY_LOCKDOWN,
                lockdownEnabled
            )
            .putBoolean(
                LuonnotarPreferences.KEY_ALWAYS_ON_KNOWN,
                alwaysOnKnown
            )
            .putBoolean(
                LuonnotarPreferences.KEY_ALWAYS_ON,
                alwaysOnSupported
            )
            .apply()
    }

    private fun showForeground(state: GuardianState, detail: String) {
        val notification = buildNotification(state, detail)
        ServiceCompat.startForeground(
            this,
            NotificationChannelManager.NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun updateNotification() {
        if (destroyed.get()) return
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val state = runCatching {
            GuardianState.valueOf(prefs.getString(LuonnotarPreferences.KEY_STATE, GuardianState.STARTING.name)!!)
        }.getOrDefault(GuardianState.STARTING)
        val successRtt = prefs.getLong(LuonnotarPreferences.KEY_LAST_SUCCESS_RTT, -1)
        val attemptRtt = prefs.getLong(LuonnotarPreferences.KEY_LAST_ATTEMPT_RTT, -1)
        val failures = prefs.getInt(LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES, 0)
        val generationMatches =
            prefs.getLong(
                LuonnotarPreferences.KEY_SUCCESS_EVIDENCE_GENERATION,
                -1L
            ) == serviceGeneration
        val successHandleMatches =
            prefs.getLong(
                LuonnotarPreferences.KEY_LAST_SUCCESS_NETWORK_HANDLE,
                -1L
            ) == vpnEvidence.networkHandle
        val detail = when {
            prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false) ->
                "已暂停：锁、心跳与 HTTPS 探测均已停止"
            !generationMatches || (successRtt >= 0L && !successHandleMatches) ->
                "等待本代服务完成首次 VPN-only 探测"
            successRtt < 0 && attemptRtt >= 0 ->
                "尚无 204 成功证据 · 最近尝试 ${attemptRtt}ms"
            successRtt < 0 -> "等待首次 VPN-only 204"
            failures > 0 -> "上次成功 ${successRtt}ms · 连续失败 $failures"
            else -> "最近成功 VPN-only 204：${successRtt}ms"
        }
        val failureLevel = when {
            prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false) ->
                "PAUSED"
            failures >= 3 -> "FAILURE_3_PLUS"
            failures > 0 -> "FAILURE_1_2"
            successRtt < 0L -> "NO_SUCCESS"
            else -> "OK"
        }
        val fingerprint = listOf(
            state.name,
            vpnEvidence.present,
            vpnEvidence.validated,
            vpnEvidence.blockedKnown && vpnEvidence.blocked,
            vpnEvidence.notSuspended,
            failureLevel
        ).joinToString("|")
        val now = SystemClock.elapsedRealtime()
        if (
            quietWindowActive(now) &&
            fingerprint == lastNotificationFingerprint
        ) return
        val refreshInterval =
            GuardianProfilePolicy.notificationRefreshInterval(
                runtimeSettings().experiments
            )
        if (
            fingerprint == lastNotificationFingerprint &&
            now - lastNotificationPostedElapsed < refreshInterval
        ) return
        // Keep the notification attached to the foreground-service record.
        // On MIUI/HyperOS, replacing the same ID through NotificationManager.notify()
        // clears FLAG_FOREGROUND_SERVICE, after which ActivityManager stops this
        // now-background service when the app becomes idle.
        showForeground(state, detail)
        lastNotificationFingerprint = fingerprint
        lastNotificationPostedElapsed = now
    }

    private fun buildNotification(state: GuardianState, detail: String): Notification {
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val paused = prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        val pauseAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val pauseLabel = if (paused) "继续保活" else "暂停保活"
        val lockdownText =
            if (!prefs.getBoolean(LuonnotarPreferences.KEY_LOCKDOWN_KNOWN, false)) {
                "未验证"
            } else if (prefs.getBoolean(LuonnotarPreferences.KEY_LOCKDOWN, false)) {
                "开启（全局阻断）"
            } else {
                "关闭（拆分隧道兼容）"
            }
        val style = NotificationCompat.InboxStyle()
            .addLine("默认 VPN：${if (vpnEvidence.present) "已确认" else "未连接"} · VALIDATED：${yesNo(vpnEvidence.validated)}")
            .addLine("Lockdown：$lockdownText")
            .addLine(
                "CPU / Wi-Fi 锁：${
                    yesNo(
                        (::wakeLock.isInitialized && wakeLock.isHeld) ||
                            (::scopedWakeLock.isInitialized &&
                                scopedWakeLock.isHeld)
                    )
                } / ${yesNo(::wifiLock.isInitialized && wifiLock.isHeld)}"
            )
            .addLine(detail)
            .addLine("服务持续：${formatDuration(SystemClock.elapsedRealtime() - startedElapsed)} · PID ${Process.myPid()}")
        return NotificationCompat.Builder(this, NotificationChannelManager.GUARDIAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setLargeIcon(notificationLargeIcon())
            .setContentTitle("努昂诺塔 · ${state.name}")
            .setContentText(detail)
            .setStyle(style)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, pauseLabel, servicePendingIntent(2, pauseAction))
            .addAction(0, "停止", servicePendingIntent(3, ACTION_STOP))
            .addAction(
                0,
                "打开 VPN",
                actionPendingIntent(4, ActionActivity.ACTION_OPEN_VPN_APP)
            )
            .addAction(0, "VPN 设置", actionPendingIntent(5, ActionActivity.ACTION_OPEN_VPN_SETTINGS))
            .build()
    }

    @Synchronized
    private fun notificationLargeIcon(): Bitmap {
        notificationLargeIcon?.takeUnless { it.isRecycled }?.let { return it }
        val side = (64f * resources.displayMetrics.density).toInt().coerceAtLeast(64)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, R.mipmap.ic_luonnotar, bounds)
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= side &&
            bounds.outHeight / (sample * 2) >= side
        ) {
            sample *= 2
        }
        val source = BitmapFactory.decodeResource(
            resources,
            R.mipmap.ic_luonnotar,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
        val scaled = if (source.width == side && source.height == side) {
            source
        } else {
            Bitmap.createScaledBitmap(source, side, side, true).also { source.recycle() }
        }
        notificationLargeIcon = scaled
        return scaled
    }

    @Synchronized
    private fun releaseNotificationLargeIcon() {
        notificationLargeIcon?.takeUnless { it.isRecycled }?.recycle()
        notificationLargeIcon = null
    }

    private fun cancelTransientNotifications() {
        getSystemService(NotificationManager::class.java).run {
            cancel(NotificationChannelManager.ALERT_NOTIFICATION_ID)
            cancel(NotificationChannelManager.RECOVERY_NOTIFICATION_ID)
        }
    }

    private fun isActivelyEnabled(
        prefs: android.content.SharedPreferences =
            LuonnotarPreferences.deviceProtected(this)
    ): Boolean =
        !destroyed.get() &&
            !stopping.get() &&
            prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) &&
            !prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)

    private fun isCurrentServiceInstance(): Boolean {
        if (destroyed.get() || stopping.get() || serviceGeneration <= 0L) {
            return false
        }
        return LuonnotarPreferences.deviceProtected(this).getLong(
            LuonnotarPreferences.KEY_SERVICE_GENERATION,
            -1L
        ) == serviceGeneration
    }

    private fun isEpochCurrent(expectedEpoch: Long): Boolean =
        expectedEpoch == recoveryEpoch.get() &&
            isCurrentServiceInstance()

    private fun showAlert(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, NotificationChannelManager.ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_guardian)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(actionPendingIntent(20, ActionActivity.ACTION_OPEN_VPN_APP))
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NotificationChannelManager.ALERT_NOTIFICATION_ID, notification)
    }

    private fun servicePendingIntent(request: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            request,
            Intent(this, FcmGuardianService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionPendingIntent(request: Int, action: String): PendingIntent =
        PendingIntent.getActivity(
            this,
            request,
            Intent(this, ActionActivity::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun bumpProcessSequence() {
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val sequence = prefs.getLong(LuonnotarPreferences.KEY_PROCESS_SEQUENCE, 0) + 1
        prefs.edit()
            .putLong(LuonnotarPreferences.KEY_PROCESS_SEQUENCE, sequence)
            .putInt(LuonnotarPreferences.KEY_PID, Process.myPid())
            .putLong(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED, startedElapsed)
            .apply()
    }

    private fun persistError(error: String) {
        if (!isCurrentServiceInstance()) return
        LuonnotarPreferences.deviceProtected(this).edit()
            .putString(LuonnotarPreferences.KEY_LAST_ERROR, error).apply()
    }

    private fun yesNo(value: Boolean) = if (value) "是" else "否"

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
    }
}
