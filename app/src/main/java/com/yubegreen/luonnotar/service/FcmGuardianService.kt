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
import android.os.IBinder
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
import com.yubegreen.luonnotar.monitor.NetworkEvidence
import com.yubegreen.luonnotar.monitor.NetworkStateMonitor
import com.yubegreen.luonnotar.monitor.SupportedVpnProvider
import com.yubegreen.luonnotar.monitor.VpnConnectivityMonitor
import com.yubegreen.luonnotar.monitor.VpnEvidence
import com.yubegreen.luonnotar.monitor.VpnOnlyRoutingPolicy
import com.yubegreen.luonnotar.monitor.WifiUnderlayHistory
import com.yubegreen.luonnotar.monitor.WifiUnderlayLockPolicy
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.receiver.GuardianCleanupReceiver
import com.yubegreen.luonnotar.receiver.LabAlarmScheduler
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        const val EXTRA_START_REASON = "start_reason"
        const val KEEPALIVE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        private const val TICK_SECONDS = 5L
        private const val LOCK_CHECK_MS = 30_000L
        private const val RECOVERY_PROBE_COOLDOWN_MS = 15_000L
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val PROBE_HARD_TIMEOUT_MS = 45_000L
        private const val ALERT_COOLDOWN_MS = 10 * 60_000L
        private const val LEGACY_LAST_RTT_KEY = "last_rtt_ms"
        private val PROCESS_ACTUAL_PROBE_PERMIT = ActualProbePermit()
        private val SCREEN_ACTIONS = setOf(
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT
        )
    }

    @Volatile private var scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var probeExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var scheduled: ScheduledFuture<*>? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var vpnMonitor: VpnConnectivityMonitor
    private lateinit var networkMonitor: NetworkStateMonitor
    @Volatile private var vpnEvidence = VpnEvidence(false, false, null, -1)
    @Volatile private var networkEvidence = NetworkEvidence(false, false, "NONE", false)
    private var startedElapsed = 0L
    private var activeMonitoringStartedElapsed = 0L
    private var lastExpectedTickElapsed = 0L
    private var lastLockCheckElapsed = 0L
    private val lastKeepaliveAttemptElapsed = AtomicLong(0L)
    private val recoveryEpoch = AtomicLong(0L)
    private val probeRequestGate = ProbeRequestGate(recoveryEpoch.get())
    private val probeLifecycleLock = Any()
    private val destroyed = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val processProbeRetryScheduled = AtomicBoolean(false)
    private val hardProbeRestartRequested = AtomicBoolean(false)
    private var serviceGeneration = 0L
    @Volatile private var activeConnection: HttpsURLConnection? = null
    private var notificationLargeIcon: Bitmap? = null
    private var lastNotificationFingerprint = ""
    private var lastNotificationPostedElapsed = 0L
    private var wifiUnderlayHistory = WifiUnderlayHistory()
    private var lastUnderlayDiagnosticElapsed = 0L
    private var lastDrainingActualDiagnosticElapsed = 0L
    private var screenReceiverRegistered = false
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
            if (
                prefs.getBoolean(
                    LuonnotarPreferences.KEY_AGGRESSIVE_VIVO_MODE,
                    false
                ) &&
                isActivelyEnabled(prefs)
            ) {
                requestRecoveryProbe(timelineEvent, force = true)
            }
        }
    }
    private val bootId: String by lazy {
        runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrDefault("unavailable")
    }

    override fun onCreate() {
        super.onCreate()
        startedElapsed = SystemClock.elapsedRealtime()
        showForeground(GuardianState.STARTING, "初始化系统证据…")
        LuonnotarPreferences.deviceProtected(this).edit()
            .remove(LEGACY_LAST_RTT_KEY)
            .remove(LuonnotarPreferences.KEY_PROBE_STARTED_ELAPSED)
            .remove(LuonnotarPreferences.KEY_PROBE_DEADLINE_ELAPSED)
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
            acquireWakeLock("service_create")
        }
        observeVpnPolicySettings()
        vpnMonitor = VpnConnectivityMonitor(this) vpnCallback@{
            if (!isCurrentServiceInstance()) return@vpnCallback
            val previous = vpnEvidence
            vpnEvidence = it
            persistNetworkEvidence()
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
            if (handleChanged) {
                disconnectActiveConnection()
            }
            if (
                it.present != previous.present ||
                it.networkHandle != previous.networkHandle ||
                it.validated != previous.validated ||
                routeChanged ||
                providerChanged
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
                        "currentProvider" to it.providerPackage
                    )
                )
            }
            if (
                it.present &&
                it.internetRouted &&
                (
                    recovered ||
                        handleChanged ||
                        validationRegained ||
                        routeChanged ||
                        providerChanged
                    )
            ) {
                requestRecoveryProbe(
                    when {
                        handleChanged -> "vpn_handle_changed"
                        recovered -> "vpn_recovered"
                        routeChanged -> "vpn_default_route_changed"
                        providerChanged -> "vpn_provider_changed"
                        else -> "vpn_validated"
                    },
                    force = true
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
            }
            if (handleChanged && vpnEvidence.present) {
                requestRecoveryProbe("default_network_handle_changed", force = true)
            }
            if (it.validated && !previous.validated && vpnEvidence.present) {
                requestRecoveryProbe("network_validated", force = true)
            }
        }
        runCatching {
            vpnMonitor.start()
            networkMonitor.start()
        }.onFailure {
            runCatching { vpnMonitor.stop() }
            runCatching { networkMonitor.stop() }
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
        persistNetworkEvidence()
        reconcileWifiLock()
        if (active) scheduleTicks()
        LogManager.event(this, "guardian_service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_START_REASON)
            ?: if (intent == null) "START_STICKY_REDELIVERY" else "unspecified"
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val action = intent?.action ?: ACTION_START
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
            stopSelf()
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
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_STOP -> {
                stopping.set(true)
                disconnectActiveConnection()
                val stopped = prefs.edit()
                    .putBoolean(LuonnotarPreferences.KEY_ENABLED, false)
                    .putBoolean(LuonnotarPreferences.KEY_PAUSED, false)
                    .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
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
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                return if (pauseGuardian()) START_NOT_STICKY else START_STICKY
            }
            ACTION_RESUME -> {
                if (!resumeGuardian()) {
                    ServiceCompat.stopForeground(
                        this,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_CHECK -> submitManualCheck()
            ACTION_RECOVER -> recoverInternalSchedulers(reason)
            else -> {
                prefs.edit()
                    .putString(LuonnotarPreferences.KEY_LAST_START_REASON, reason)
                    .putString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "")
                    .apply()
                if (!prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)) {
                    if (activeMonitoringStartedElapsed == 0L) {
                        activeMonitoringStartedElapsed = SystemClock.elapsedRealtime()
                    }
                    acquireWakeLock("start_command")
                    reconcileWifiLock()
                    scheduleTicks()
                }
            }
        }
        LogManager.event(this, "guardian_start_command", mapOf("reason" to reason, "action" to intent?.action))
        updateNotification()
        return if (prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)) {
            START_NOT_STICKY
        } else {
            START_STICKY
        }
    }

    override fun onDestroy() {
        stopping.set(true)
        destroyed.set(true)
        var connectionToDisconnect: HttpsURLConnection? = null
        val oldExecutors = synchronized(probeLifecycleLock) {
            probeRequestGate.advanceGeneration(
                recoveryEpoch.incrementAndGet()
            )
            scheduled?.cancel(true)
            scheduled = null
            connectionToDisconnect = activeConnection
            activeConnection = null
            processProbeRetryScheduled.set(false)
            scheduler to probeExecutor
        }
        connectionToDisconnect?.disconnect()
        if (::vpnMonitor.isInitialized) vpnMonitor.stop()
        if (::networkMonitor.isInitialized) networkMonitor.stop()
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
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
            .putBoolean(LuonnotarPreferences.KEY_PROBE_IN_FLIGHT, false)
            .remove(LuonnotarPreferences.KEY_PROBE_STARTED_ELAPSED)
            .remove(LuonnotarPreferences.KEY_PROBE_DEADLINE_ELAPSED)
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
                    requestRecoveryProbe("manual_check", force = true)
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
                        SystemClock.elapsedRealtime() + TICK_SECONDS * 1000
                }
            }, 0, TICK_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun tick(expectedEpoch: Long) {
        if (!isEpochCurrent(expectedEpoch)) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val drift = max(0L, nowElapsed - lastExpectedTickElapsed)
        val prefs = LuonnotarPreferences.deviceProtected(this)
        if (!prefs.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)) {
            stopForAuthoritativeDisable()
            return
        }
        if (prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)) return
        val previousElapsed = prefs.getLong(LuonnotarPreferences.KEY_LAST_TICK_ELAPSED, 0)
        val previousUptime = prefs.getLong(LuonnotarPreferences.KEY_LAST_TICK_UPTIME, 0)
        val suspendGap = if (previousElapsed > 0 && previousUptime > 0) {
            (nowElapsed - previousElapsed) - (nowUptime - previousUptime)
        } else 0
        val maximum = max(prefs.getLong(LuonnotarPreferences.KEY_MAX_TIMER_DRIFT, 0), drift)
        val heartbeatCommitted = synchronized(probeLifecycleLock) {
            if (!isEpochCurrent(expectedEpoch)) {
                false
            } else {
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
                        maximum
                    )
                    .putLong(
                        LuonnotarPreferences.KEY_LAST_TIMER_DRIFT,
                        drift
                    )
                    .remove(LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT)
                    .remove(
                        LuonnotarPreferences.KEY_RECOVERY_FAILURE_BOOT_ELAPSED
                    )
                    .commit()
            }
        }
        if (!heartbeatCommitted || !isEpochCurrent(expectedEpoch)) return
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
        if (
            probeStarted > 0L &&
            watchdogAction == ProbeWatchdogAction.REBUILD_EXECUTOR
        ) {
            prefs.edit()
                .putString(
                    LuonnotarPreferences.KEY_RECOVERY_FAILURE_SERVICE,
                    "HTTPS 探测超过 ${PROBE_TIMEOUT_MS / 1000} 秒未结束，正在重建探测器"
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
                "https_probe_timeout",
                mapOf("probeAgeMs" to probeAge)
            )
            recoverInternalSchedulers(
                "probe_watchdog_timeout",
                expectedEpoch
            )
            return
        }
        if (
            probeStarted > 0L &&
            watchdogAction == ProbeWatchdogAction.RESTART_KEEPER_PROCESS
        ) {
            restartKeeperForHardProbeTimeout(
                probeAge,
                probeSnapshot
            )
            return
        }
        if (
            probeSnapshot.actualInFlight &&
            !probeSnapshot.inFlight &&
            probeStarted > 0L &&
            nowElapsed - probeStarted >= PROBE_TIMEOUT_MS &&
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
                "https_probe_old_actual_draining",
                mapOf(
                    "probeAgeMs" to (nowElapsed - probeStarted),
                    "actualOwnerGeneration" to
                        probeSnapshot.actualOwner?.generation
                )
            )
        } else if (!probeSnapshot.actualInFlight) {
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
            if (!wakeLock.isHeld) acquireWakeLock("self_check_reacquire")
            vpnEvidence = vpnMonitor.current()
            networkEvidence = networkMonitor.current()
            persistNetworkEvidence()
            reconcileWifiLock()
            observeVpnPolicySettings()
        }
        if (!isEpochCurrent(expectedEpoch)) return
        val aggressiveMode = prefs.getBoolean(
            LuonnotarPreferences.KEY_AGGRESSIVE_VIVO_MODE,
            false
        )
        val interactive =
            getSystemService(PowerManager::class.java).isInteractive
        val keepaliveInterval = KeepaliveCadencePolicy.intervalMs(
            aggressiveMode = aggressiveMode,
            screenInteractive = interactive
        )
        if (
            KeepaliveCadencePolicy.mayProbe(
                vpnPresent = vpnEvidence.present,
                paused = prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
            ) &&
            (
                lastKeepaliveAttemptElapsed.get() == 0L ||
                    nowElapsed - lastKeepaliveAttemptElapsed.get() >=
                    keepaliveInterval
                )
        ) {
            if (isEpochCurrent(expectedEpoch)) {
                requestRecoveryProbe(
                    if (aggressiveMode && !interactive) {
                        "screen_off_aggressive_periodic"
                    } else {
                        "periodic_keepalive"
                    }
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
        reason: String
    ) {
        if (!isActivelyEnabled() || expectedEpoch != recoveryEpoch.get()) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return
        val capturedHandle = network.networkHandle
        val before = connectivity.getNetworkCapabilities(network)
        if (before?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return
        val start = SystemClock.elapsedRealtime()
        val mayStart = synchronized(probeLifecycleLock) {
            if (
                !probeRequestGate.owns(ownerToken) ||
                expectedEpoch != recoveryEpoch.get() ||
                !isActivelyEnabled() ||
                !probeNetworkIsCurrent(connectivity, capturedHandle)
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
        try {
            if (!isActivelyEnabled() || expectedEpoch != recoveryEpoch.get()) return
            connection = network.openConnection(URL(KEEPALIVE_URL)) as HttpsURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 2_000
            connection.useCaches = false
            connection.defaultUseCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.requestMethod = "GET"
            val prepared = synchronized(probeLifecycleLock) {
                if (
                    !probeRequestGate.owns(ownerToken) ||
                    !isActivelyEnabled() ||
                    expectedEpoch != recoveryEpoch.get() ||
                    !probeNetworkIsCurrent(connectivity, capturedHandle)
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
                    probeNetworkIsCurrent(connectivity, capturedHandle)
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
                        !probeNetworkIsCurrent(connectivity, capturedHandle)
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
                    ownerToken
                )
            }
        } catch (error: Exception) {
            if (
                !isActivelyEnabled() ||
                expectedEpoch != recoveryEpoch.get() ||
                !probeRequestGate.owns(ownerToken) ||
                !probeNetworkIsCurrent(connectivity, capturedHandle)
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
                    ownerToken
                )
            }
        } finally {
            connection?.disconnect()
            synchronized(probeLifecycleLock) {
                if (activeConnection === connection) activeConnection = null
            }
        }
    }

    private fun disconnectActiveConnection() {
        val connection = synchronized(probeLifecycleLock) {
            activeConnection.also { activeConnection = null }
        }
        connection?.disconnect()
    }

    private fun restartKeeperForHardProbeTimeout(
        probeAgeMs: Long,
        snapshot: ProbeGateSnapshot
    ) {
        if (!hardProbeRestartRequested.compareAndSet(false, true)) return
        val now = SystemClock.elapsedRealtime()
        val preferences = LuonnotarPreferences.deviceProtected(this)
        val recoveryScheduled = runCatching {
            LabAlarmScheduler.scheduleNext(this)
        }.getOrDefault(false)
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
                "logicalGeneration" to snapshot.generation,
                "actualOwnerGeneration" to snapshot.actualOwner?.generation,
                "recoveryAlarmScheduled" to recoveryScheduled
            )
        )
        disconnectActiveConnection()
        Process.killProcess(Process.myPid())
    }

    private fun requestRecoveryProbe(reason: String, force: Boolean = false) {
        if (!isActivelyEnabled()) return
        val prefs = LuonnotarPreferences.deviceProtected(this)
        if (
            !KeepaliveCadencePolicy.mayProbe(
                vpnPresent = vpnEvidence.present,
                paused = prefs.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
            )
        ) {
            LogManager.timeline(
                this,
                "https_probe_blocked",
                mapOf("probeReason" to reason, "blockReason" to "vpn_not_present")
            )
            return
        }
        val now = SystemClock.elapsedRealtime()
        val lastAttempt = lastKeepaliveAttemptElapsed.get()
        if (!force && lastAttempt != 0L && now - lastAttempt < RECOVERY_PROBE_COOLDOWN_MS) return
        val taskEpoch = recoveryEpoch.get()
        val submission = synchronized(probeLifecycleLock) {
            val token = probeRequestGate.begin(
                generation = taskEpoch,
                force = force,
                startedElapsed = now
            ) ?: return
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
                    if (!PROCESS_ACTUAL_PROBE_PERMIT.tryAcquire(ownerToken)) {
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
                        scheduleProcessProbePermitRetry()
                        return@execute
                    }
                    try {
                        executeKeepalive(ownerToken, taskEpoch, reason)
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
                                force = true
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
                            force = true
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

    private fun scheduleProcessProbePermitRetry() {
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
                        force = true
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

    private fun recordKeepaliveFailure(
        error: String,
        rtt: Long,
        code: Int,
        capturedHandle: Long,
        expectedEpoch: Long,
        reason: String,
        ownerToken: ProbeOwnerToken
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
                !probeNetworkIsCurrent(connectivity, capturedHandle)
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
            ) == vpnEvidence.networkHandle
        val attemptEvidenceIsCurrent =
            prefs.getLong(
                LuonnotarPreferences.KEY_ATTEMPT_EVIDENCE_GENERATION,
                -1L
            ) == serviceGeneration &&
            prefs.getLong(
                LuonnotarPreferences.KEY_LAST_ATTEMPT_NETWORK_HANDLE,
                -1L
            ) == vpnEvidence.networkHandle
        val lastSuccess = prefs.getLong(LuonnotarPreferences.KEY_LAST_SUCCESS_ELAPSED, 0)
        val lastAttempt = prefs.getLong(LuonnotarPreferences.KEY_LAST_ATTEMPT_ELAPSED, 0)
        val now = SystemClock.elapsedRealtime()
        val hasAnySuccess = successEvidenceIsCurrent && lastSuccess > 0L && lastSuccess <= now
        val currentInterval = KeepaliveCadencePolicy.intervalMs(
            aggressiveMode = prefs.getBoolean(
                LuonnotarPreferences.KEY_AGGRESSIVE_VIVO_MODE,
                false
            ),
            screenInteractive =
                getSystemService(PowerManager::class.java).isInteractive
        )
        val recentEnough =
            hasAnySuccess && now - lastSuccess <= 2 * currentInterval
        val state = GuardianStateReducer.reduce(
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
        prefs.edit().putString(LuonnotarPreferences.KEY_STATE, state.name).apply()
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
            httpsDegraded ->
                Triple(
                    "HTTPS_DEGRADED",
                    "VPN 路径保活已降级",
                    "VPN 仍在线，但连续探测失败或最后一次 204 已过期。目标 UID 路由验证不会遮蔽此告警。"
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

    private fun persistNetworkEvidence() {
        if (!isCurrentServiceInstance()) return
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val previousHandle = prefs.getLong(LuonnotarPreferences.KEY_NETWORK_HANDLE, -1)
        val previousInternetRouted = prefs.getBoolean(
            LuonnotarPreferences.KEY_VPN_INTERNET_ROUTED,
            false
        )
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
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_IPV4_DEFAULT_ROUTE,
                vpnEvidence.ipv4DefaultRoute
            )
            .putBoolean(
                LuonnotarPreferences.KEY_VPN_IPV6_DEFAULT_ROUTE,
                vpnEvidence.ipv6DefaultRoute
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
            (previousHandle >= 0 && previousHandle != vpnEvidence.networkHandle) ||
            previousInternetRouted != vpnEvidence.internetRouted
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
                .remove(LuonnotarPreferences.KEY_LAST_HTTP_CODE)
                .remove(LuonnotarPreferences.KEY_CONSECUTIVE_FAILURES)
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
        }
        editor.apply()
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
            vpnPresent = vpnEvidence.present
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
            prefs.getBoolean(LuonnotarPreferences.KEY_ADB_INTERNET_ROUTED, false) &&
            prefs.getBoolean(LuonnotarPreferences.KEY_ADB_GMS_ROUTED, false) &&
            (
                !isPackageInstalled("com.whatsapp") ||
                    prefs.getBoolean(
                        LuonnotarPreferences.KEY_ADB_WHATSAPP_ROUTED,
                        false
                    )
                ) &&
            (
                !isPackageInstalled("com.whatsapp.w4b") ||
                    prefs.getBoolean(
                        LuonnotarPreferences.KEY_ADB_WHATSAPP_BUSINESS_ROUTED,
                        false
                    )
                )

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching {
            packageManager.getApplicationInfo(packageName, 0)
        }.isSuccess

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
        vpnPresent = vpnEvidence.present
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
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:guardian_cpu").apply {
            setReferenceCounted(false)
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:guardian_wifi").apply {
            setReferenceCounted(false)
        }
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    @Synchronized
    private fun acquireWakeLock(reason: String) {
        if (!isActivelyEnabled() || !isCurrentServiceInstance()) return
        if (!wakeLock.isHeld) {
            wakeLock.acquire()
            LuonnotarPreferences.deviceProtected(this).edit()
                .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, true).apply()
            LogManager.event(this, "wake_lock_acquired", mapOf("reason" to reason))
            LogManager.timeline(
                this,
                "wake_lock_state_changed",
                mapOf("wakeLockHeld" to true, "lockChangeReason" to reason)
            )
        }
    }

    @Synchronized
    private fun reconcileWifiLock() {
        if (!isCurrentServiceInstance()) return
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val active = isActivelyEnabled(prefs)
        val nowElapsed = SystemClock.elapsedRealtime()
        val decision = WifiUnderlayLockPolicy.decide(
            guardianActive = active,
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
        if (!historyEditor.commit()) {
            LogManager.event(
                this,
                "wifi_underlay_history_commit_failed"
            )
        }
    }

    @Synchronized
    private fun releaseLocks(reason: String) {
        val wifiWasHeld = ::wifiLock.isInitialized && wifiLock.isHeld
        val wakeWasHeld = ::wakeLock.isInitialized && wakeLock.isHeld
        if (wifiWasHeld) wifiLock.release()
        if (wakeWasHeld) wakeLock.release()
        LuonnotarPreferences.deviceProtected(this).edit()
            .putBoolean(LuonnotarPreferences.KEY_WAKE_LOCK, false)
            .putBoolean(LuonnotarPreferences.KEY_WIFI_LOCK, false)
            .apply()
        if (wifiWasHeld || wakeWasHeld) {
            LogManager.timeline(
                this,
                "guardian_locks_released",
                mapOf(
                    "wakeLockHeld" to false,
                    "wifiLockHeld" to false,
                    "previousWakeLockHeld" to wakeWasHeld,
                    "previousWifiLockHeld" to wifiWasHeld,
                    "lockChangeReason" to reason
                )
            )
        }
    }

    private fun quiesceGuardianExecution(reason: String) {
        var connectionToDisconnect: HttpsURLConnection? = null
        synchronized(probeLifecycleLock) {
            probeRequestGate.advanceGeneration(
                recoveryEpoch.incrementAndGet()
            )
            scheduled?.cancel(true)
            scheduled = null
            connectionToDisconnect = activeConnection
            activeConnection = null
            processProbeRetryScheduled.set(false)
            persistProbeGateSnapshotLocked()
        }
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
        releaseLocks(reason)
    }

    private fun stopForAuthoritativeDisable() {
        stopping.set(true)
        LogManager.event(this, "guardian_self_stop_disabled_state")
        quiesceGuardianExecution("authoritative_disable")
        LabAlarmScheduler.cancel(this)
        cancelTransientNotifications()
        LuonnotarPreferences.deviceProtected(this).edit()
            .putInt(LuonnotarPreferences.KEY_PID, 0)
                    .remove(LuonnotarPreferences.KEY_HEARTBEAT_ELAPSED)
                    .remove(LuonnotarPreferences.KEY_SERVICE_STARTED_ELAPSED)
                    .remove(LuonnotarPreferences.KEY_LAST_ALERTED_STATE)
                    .remove(LuonnotarPreferences.KEY_LAST_ALERT_ELAPSED)
            .putString(LuonnotarPreferences.KEY_STATE, GuardianState.DISABLED.name)
            .commit()
        sendBroadcast(
            Intent(this, GuardianCleanupReceiver::class.java)
                .setAction(GuardianCleanupReceiver.ACTION_CLEANUP_DISABLED)
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
            connectionToDisconnect = activeConnection
            activeConnection = null
            val oldScheduler = scheduler
            val oldProbeExecutor = probeExecutor
            val newEpoch = recoveryEpoch.incrementAndGet()
            scheduler = Executors.newSingleThreadScheduledExecutor()
            probeExecutor = Executors.newSingleThreadExecutor()
            probeRequestGate.advanceGeneration(newEpoch)
            lastKeepaliveAttemptElapsed.set(0L)
            processProbeRetryScheduled.set(false)
            persistProbeGateSnapshotLocked()
            oldScheduler to oldProbeExecutor
        }
        connectionToDisconnect?.disconnect()
        LogManager.event(
            this,
            "guardian_internal_recovery_started",
            mapOf("reason" to reason)
        )
        oldExecutors.first.shutdownNow()
        oldExecutors.second.shutdownNow()
        lastExpectedTickElapsed = SystemClock.elapsedRealtime()
        scheduleTicks()
        requestRecoveryProbe("internal_recovery", force = true)
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

    private fun pauseGuardian(): Boolean {
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return true
    }

    private fun resumeGuardian(): Boolean {
        stopping.set(false)
        val prefs = LuonnotarPreferences.deviceProtected(this)
        val committed = prefs.edit()
                .putBoolean(LuonnotarPreferences.KEY_PAUSED, false)
                .putString(LuonnotarPreferences.KEY_LAST_SERVICE_EXIT, "")
                .putString(LuonnotarPreferences.KEY_LAST_START_REASON, "resume")
                .commit()
        if (!committed) {
            persistError("resume:SharedPreferencesCommitFailed")
            LogManager.event(this, "guardian_resume_commit_failed")
            return false
        }
        activeMonitoringStartedElapsed = SystemClock.elapsedRealtime()
        showForeground(GuardianState.STARTING, "正在恢复守护…")
        acquireWakeLock("resume")
        reconcileWifiLock()
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
        )
        requestRecoveryProbe("resume", force = true)
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
        prefs.edit()
            .putBoolean(LuonnotarPreferences.KEY_LOCKDOWN_KNOWN, lockdown != null)
            .putBoolean(LuonnotarPreferences.KEY_LOCKDOWN, lockdown == 1)
            .putBoolean(LuonnotarPreferences.KEY_ALWAYS_ON_KNOWN, alwaysOnPackage != null)
            .putBoolean(
                LuonnotarPreferences.KEY_ALWAYS_ON,
                SupportedVpnProvider.isSupported(alwaysOnPackage)
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
        val fingerprint = "${state.name}|$detail|${vpnEvidence.present}|${vpnEvidence.validated}"
        val now = SystemClock.elapsedRealtime()
        if (
            fingerprint == lastNotificationFingerprint &&
            now - lastNotificationPostedElapsed < 60_000L
        ) return
        getSystemService(NotificationManager::class.java)
            .notify(NotificationChannelManager.NOTIFICATION_ID, buildNotification(state, detail))
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
            .addLine("CPU / Wi-Fi 锁：${yesNo(::wakeLock.isInitialized && wakeLock.isHeld)} / ${yesNo(::wifiLock.isInitialized && wifiLock.isHeld)}")
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
