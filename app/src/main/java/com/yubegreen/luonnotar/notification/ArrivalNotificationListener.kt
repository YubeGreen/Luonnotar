package com.yubegreen.luonnotar.notification

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.yubegreen.luonnotar.service.GuardianStatusClient
import com.yubegreen.luonnotar.service.GuardianStatusProvider
import com.yubegreen.luonnotar.util.LogManager
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ArrivalNotificationListener : NotificationListenerService() {
    private enum class ObservationSource {
        LIVE_CALLBACK,
        ACTIVE_SCAN
    }

    private sealed class NotificationEvent {
        data class Posted(val event: PostedEvent) : NotificationEvent()
        data class Removed(val packageName: String, val keyHash: String) : NotificationEvent()
    }

    private data class PostedEvent(
        val packageName: String,
        val keyHash: String,
        val groupHash: String,
        val groupSummary: Boolean,
        val postTime: Long,
        val seenWall: Long,
        val seenElapsed: Long,
        val observationSource: ObservationSource,
        val parseDiagnostic: PushTestParseDiagnostic
    )

    private val rebindHandler = Handler(Looper.getMainLooper())
    private val eventQueue = ConcurrentLinkedQueue<NotificationEvent>()
    private val drainScheduled = AtomicBoolean(false)
    private val eventExecutor =
        Executors.newSingleThreadScheduledExecutor {
            Thread(it, "luonnotar-notification-events").apply {
                isDaemon = true
            }
        }
    @Volatile private var destroying = false
    @Volatile private var listenerConnected = false
    private var rebindAttempt = 0
    private var rebindScheduled = false
    private var rebindRequestWatchdog: Runnable? = null
    private val rebindRunnable = Runnable {
        rebindScheduled = false
        if (
            !destroying &&
                rebindAttempt in 1..MAX_REBIND_FAILURES &&
                notificationAccessGranted()
        ) {
            requestRebind(
                ComponentName(
                    this,
                    ArrivalNotificationListener::class.java
                )
            )
            rebindRequestWatchdog?.let(rebindHandler::removeCallbacks)
            rebindRequestWatchdog = Runnable {
                rebindRequestWatchdog = null
                if (!destroying && !listenerConnected && notificationAccessGranted()) {
                    rebindAttempt++
                    scheduleRebind()
                }
            }
            rebindHandler.postDelayed(rebindRequestWatchdog!!, REBIND_REQUEST_WATCHDOG_MS)
            LogManager.event(
                this,
                "notification_listener_rebind_requested",
                mapOf("attempt" to rebindAttempt)
            )
        }
    }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            GuardianStatusClient.setNotificationListenerState(
                this@ArrivalNotificationListener,
                connected = true,
                pid = Process.myPid()
            )
            rebindHandler.postDelayed(this, LISTENER_HEARTBEAT_INTERVAL_MS)
        }
    }
    private val allowedPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.gms"
    )
    private val whatsappPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        activeInstance = WeakReference(this)
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindScheduled = false
        rebindHandler.removeCallbacks(heartbeatRunnable)
        rebindRequestWatchdog?.let(rebindHandler::removeCallbacks)
        rebindRequestWatchdog = null
        rebindAttempt = 0
        GuardianStatusClient.setNotificationListenerState(
            this,
            connected = true,
            pid = Process.myPid()
        )
        rebindHandler.postDelayed(
            heartbeatRunnable,
            LISTENER_HEARTBEAT_INTERVAL_MS
        )
        LogManager.event(
            this,
            "notification_listener_connected",
            mapOf(
                "pid" to Process.myPid(),
                "activeScanQueued" to enqueueActiveNotificationScan("listener_connected")
            )
        )
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        GuardianStatusClient.setNotificationListenerState(
            this,
            connected = false,
            pid = 0
        )
        LogManager.event(
            this,
            "notification_listener_disconnected",
            mapOf("pid" to Process.myPid())
        )
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindScheduled = false
        rebindHandler.removeCallbacks(heartbeatRunnable)
        if (notificationAccessGranted()) {
            if (rebindAttempt == 0) rebindAttempt = 1
            scheduleRebind()
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        destroying = true
        listenerConnected = false
        if (activeInstance?.get() === this) activeInstance = null
        rebindHandler.removeCallbacks(rebindRunnable)
        rebindScheduled = false
        rebindRequestWatchdog?.let(rebindHandler::removeCallbacks)
        rebindHandler.removeCallbacks(heartbeatRunnable)
        GuardianStatusClient.setNotificationListenerState(
            this,
            connected = false,
            pid = 0
        )
        runCatching { eventExecutor.execute(::drainNotificationEvents) }
        eventExecutor.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (destroying || sbn.packageName !in allowedPackages) return
        eventQueue.offer(
            NotificationEvent.Posted(
                buildPostedEvent(sbn, ObservationSource.LIVE_CALLBACK)
            )
        )
        scheduleNotificationDrain()
    }

    private fun buildPostedEvent(
        sbn: StatusBarNotification,
        source: ObservationSource
    ): PostedEvent {
        val candidates = pushTestCandidates(sbn.notification)
        return PostedEvent(
            packageName = sbn.packageName,
            keyHash = sha256(sbn.key),
            groupHash = sbn.groupKey?.let(::sha256).orEmpty(),
            groupSummary =
                sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            postTime = sbn.postTime,
            seenWall = System.currentTimeMillis(),
            seenElapsed = SystemClock.elapsedRealtime(),
            observationSource = source,
            parseDiagnostic = PushTestNotificationParser.parseLatestCandidates(candidates)
        )
    }

    private fun scheduleNotificationDrain() {
        if (destroying) return
        if (!drainScheduled.compareAndSet(false, true)) return
        runCatching {
            eventExecutor.schedule(
                ::drainNotificationEvents,
                EVENT_BATCH_DELAY_MS,
                TimeUnit.MILLISECONDS
            )
        }.onFailure {
            drainScheduled.set(false)
            LogManager.event(
                this,
                "notification_event_queue_rejected",
                mapOf("error" to it.toString())
            )
        }
    }

    private fun drainNotificationEvents() {
        try {
            var processed = 0
            while (processed < MAX_BATCH_SIZE) {
                val event = eventQueue.poll() ?: break
                when (event) {
                    is NotificationEvent.Posted -> processNotificationEvent(event.event)
                    is NotificationEvent.Removed -> GuardianStatusClient.removeNotificationKey(
                        this,
                        event.packageName,
                        event.keyHash
                    )
                }
                processed++
            }
            if (destroying) {
                while (true) {
                    val event = eventQueue.poll() ?: break
                    when (event) {
                        is NotificationEvent.Posted -> processNotificationEvent(event.event)
                        is NotificationEvent.Removed -> GuardianStatusClient.removeNotificationKey(
                            this,
                            event.packageName,
                            event.keyHash
                        )
                    }
                }
            }
        } finally {
            drainScheduled.set(false)
            if (!destroying && eventQueue.isNotEmpty()) scheduleNotificationDrain()
        }
    }

    private fun processNotificationEvent(event: PostedEvent) {
        if (event.packageName in whatsappPackages) {
            logPrivacySafeCallbackDiagnostic(event)
            recordControlledPushTest(event)
        }
        if (event.observationSource == ObservationSource.ACTIVE_SCAN) return

        val pushTest = event.parseDiagnostic.notification
        val record = GuardianStatusClient.recordNotification(
            context = this,
            packageName = event.packageName,
            keyHash = event.keyHash,
            postTime = event.postTime,
            seenWall = event.seenWall,
            groupHash = event.groupHash,
            groupSummary = event.groupSummary
        ) ?: return
        if (!record.getBoolean(GuardianStatusProvider.RESULT_OK)) return
        val kind = runCatching {
            NotificationArrivalKind.valueOf(
                record.getString(
                    GuardianStatusProvider.RESULT_NOTIFICATION_KIND
                ).orEmpty()
            )
        }.getOrDefault(NotificationArrivalKind.DUPLICATE)
        if (kind == NotificationArrivalKind.DUPLICATE) {
            LogManager.event(
                this,
                "notification_arrival_duplicate",
                mapOf(
                    "packageName" to event.packageName,
                    "postTime" to event.postTime,
                    "keyHash" to event.keyHash
                )
            )
            return
        }
        val arrivalCount = record.getLong(
            GuardianStatusProvider.RESULT_ARRIVAL_COUNT
        )
        val updateCount = record.getLong(
            GuardianStatusProvider.RESULT_UPDATE_COUNT
        )
        LogManager.event(
            this,
            if (kind == NotificationArrivalKind.NEW) {
                "notification_arrival"
            } else {
                "notification_update"
            },
            mapOf(
                "packageName" to event.packageName,
                "postTime" to event.postTime,
                "keyHash" to event.keyHash,
                "groupHash" to event.groupHash,
                "isGroupSummary" to event.groupSummary,
                "arrivalCount" to arrivalCount,
                "updateCount" to updateCount
            )
        )
        if (event.packageName in whatsappPackages) {
            val lastSuccessElapsed = record.getLong(
                GuardianStatusProvider.RESULT_LAST_SUCCESS_ELAPSED,
                0L
            )
            val timelineDetails = linkedMapOf<String, Any?>(
                "packageName" to event.packageName,
                "notificationKind" to kind.name,
                "notificationPostTime" to event.postTime,
                "listenerSeenTime" to event.seenWall,
                "listenerDispatchDelayMs" to
                    (event.seenWall - event.postTime).coerceAtLeast(0L),
                "currentNetworkHandle" to record.getLong(
                    GuardianStatusProvider.RESULT_NETWORK_HANDLE,
                    -1L
                ),
                "lastSuccessfulProbeAgeMs" to if (
                    lastSuccessElapsed > 0L &&
                    lastSuccessElapsed <= event.seenElapsed
                ) {
                    event.seenElapsed - lastSuccessElapsed
                } else {
                    -1L
                },
                "screenInteractive" to
                    getSystemService(PowerManager::class.java).isInteractive,
                "groupHash" to event.groupHash,
                "isGroupSummary" to event.groupSummary
            )
            if (pushTest != null) {
                timelineDetails["pushTestSequence"] = pushTest.sequence
                timelineDetails["pushTestSenderLocalTime"] =
                    pushTest.senderLocalTime
                timelineDetails["pushTestSenderZone"] = pushTest.senderZoneId
                timelineDetails["pushTestSenderEpochMs"] =
                    pushTest.senderEpochMs
                timelineDetails["pushTestSenderPrecisionMs"] =
                    pushTest.senderPrecisionMs
                val timestampDeltaMs =
                    event.seenWall - pushTest.senderEpochMs
                timelineDetails["pushTestTimestampDeltaMs"] =
                    timestampDeltaMs
                timelineDetails["pushTestEndToEndDelayMs"] =
                    timestampDeltaMs
                timelineDetails["pushTestEndToEndDelayApproximate"] = true
                timelineDetails["pushTestDelaySemantics"] =
                    "message_timestamp_to_listener_seen"
            }
            LogManager.timeline(
                this,
                "whatsapp_notification_observed",
                timelineDetails
            )
        }
    }

    private fun logPrivacySafeCallbackDiagnostic(event: PostedEvent) {
        val diagnostic = event.parseDiagnostic
        val pushTest = diagnostic.notification
        val details = linkedMapOf<String, Any?>(
            "packageName" to event.packageName,
            "observationSource" to event.observationSource.name,
            "keyHash" to event.keyHash,
            "postTime" to event.postTime,
            "seenWall" to event.seenWall,
            "groupHash" to event.groupHash,
            "isGroupSummary" to event.groupSummary,
            "candidateSources" to diagnostic.candidateSourcesPresent.joinToString("|"),
            "messageCandidateCount" to diagnostic.messageCandidateCount,
            "titlePresent" to diagnostic.candidateSourcesPresent.contains("EXTRA_TITLE"),
            "pushTestPrefixObserved" to diagnostic.controlledPrefixObserved,
            "pushTestMatched" to (pushTest != null),
            "matchedSource" to diagnostic.matchedSource?.diagnosticName.orEmpty()
        )
        if (pushTest != null) {
            details["sequence"] = pushTest.sequence
            details["senderLocalTime"] = pushTest.senderLocalTime
            details["senderEpochMs"] = pushTest.senderEpochMs
        }
        LogManager.event(
            this,
            if (event.observationSource == ObservationSource.LIVE_CALLBACK) {
                "notification_callback_observed"
            } else {
                "notification_active_scan_observed"
            },
            details
        )
        if (pushTest == null) {
            LogManager.event(
                this,
                "push_test_parse_not_observed",
                mapOf(
                    "packageName" to event.packageName,
                    "observationSource" to event.observationSource.name,
                    "keyHash" to event.keyHash,
                    "postTime" to event.postTime,
                    "candidateSources" to
                        diagnostic.candidateSourcesPresent.joinToString("|"),
                    "messageCandidateCount" to diagnostic.messageCandidateCount,
                    "pushTestPrefixObserved" to diagnostic.controlledPrefixObserved,
                    "reason" to diagnostic.rejectionReason
                )
            )
        }
    }

    private fun recordControlledPushTest(event: PostedEvent) {
        val pushTest = event.parseDiagnostic.notification ?: return
        val pushRecord = GuardianStatusClient.recordPushTestArrival(
            context = this,
            packageName = event.packageName,
            pushTest = pushTest,
            seenWall = event.seenWall,
            seenElapsed = event.seenElapsed,
            postTime = event.postTime,
            observationSource = event.observationSource.name
        ) ?: return
        val accepted = pushRecord.getBoolean(
            GuardianStatusProvider.RESULT_ACCEPTED,
            false
        )
        if (!accepted) {
            val reason = pushRecord.getString(
                GuardianStatusProvider.RESULT_REASON
            ).orEmpty()
            if (reason.isNotBlank()) {
                LogManager.event(
                    this,
                    "push_test_evidence_suppressed",
                    mapOf(
                        "packageName" to event.packageName,
                        "observationSource" to event.observationSource.name,
                        "sequence" to pushTest.sequence,
                        "senderEpochMs" to pushTest.senderEpochMs,
                        "reason" to reason
                    )
                )
            }
            return
        }
        LogManager.event(
            this,
            if (event.observationSource == ObservationSource.LIVE_CALLBACK) {
                "push_test_arrival_observed"
            } else {
                "push_test_active_scan_upper_bound_observed"
            },
            mapOf(
                "packageName" to event.packageName,
                "observationSource" to event.observationSource.name,
                "matchedSource" to
                    event.parseDiagnostic.matchedSource?.diagnosticName.orEmpty(),
                "sequence" to pushTest.sequence,
                "senderEpochMs" to pushTest.senderEpochMs,
                "notificationPostTime" to event.postTime,
                "seenWall" to event.seenWall,
                "endToEndDelayMs" to
                    pushRecord.getLong(
                        GuardianStatusProvider.RESULT_PUSH_TEST_DELAY_MS,
                        -1L
                    ),
                "previousSequence" to
                    pushRecord.getLong(
                        GuardianStatusProvider.RESULT_PREVIOUS_PUSH_TEST_SEQUENCE,
                        0L
                    )
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (destroying || sbn.packageName !in allowedPackages) return
        eventQueue.offer(NotificationEvent.Removed(sbn.packageName, sha256(sbn.key)))
        scheduleNotificationDrain()
    }

    private fun enqueueActiveNotificationScan(reason: String): Boolean {
        if (destroying || !listenerConnected) return false
        return runCatching {
            eventExecutor.execute { scanActiveNotifications(reason) }
        }.onFailure {
            LogManager.event(
                this,
                "notification_active_scan_queue_failed",
                mapOf("reason" to reason, "error" to it.toString())
            )
        }.isSuccess
    }

    private fun scanActiveNotifications(reason: String) {
        if (destroying || !listenerConnected) return
        val notifications = runCatching {
            activeNotifications.orEmpty().filter { it.packageName in whatsappPackages }
        }.onFailure {
            LogManager.event(
                this,
                "notification_active_scan_failed",
                mapOf("reason" to reason, "error" to it.toString())
            )
        }.getOrNull() ?: return
        LogManager.event(
            this,
            "notification_active_scan_started",
            mapOf("reason" to reason, "count" to notifications.size)
        )
        notifications.forEach { notification ->
            eventQueue.offer(
                NotificationEvent.Posted(
                    buildPostedEvent(notification, ObservationSource.ACTIVE_SCAN)
                )
            )
        }
        if (notifications.isNotEmpty()) scheduleNotificationDrain()
        LogManager.event(
            this,
            "notification_active_scan_completed",
            mapOf("reason" to reason, "count" to notifications.size)
        )
    }

    private fun scheduleRebind() {
        if (destroying || listenerConnected || !notificationAccessGranted()) return
        if (rebindScheduled || rebindRequestWatchdog != null) return
        if (rebindAttempt > MAX_REBIND_FAILURES) {
            LogManager.event(
                this,
                "notification_listener_rebind_exhausted",
                mapOf("attempts" to rebindAttempt)
            )
            return
        }
        val delay = REBIND_DELAYS_MS[
            (rebindAttempt - 1).coerceIn(0, REBIND_DELAYS_MS.lastIndex)
        ]
        rebindScheduled = true
        rebindHandler.postDelayed(rebindRunnable, delay)
        LogManager.event(
            this,
            "notification_listener_rebind_scheduled",
            mapOf("attempt" to rebindAttempt, "delayMs" to delay)
        )
    }

    private fun pushTestCandidates(
        notification: Notification
    ): List<PushTestCandidate> = buildList {
        val extras = notification.extras ?: Bundle.EMPTY
        addCandidate(
            PushTestCandidateSource.EXTRA_TEXT,
            extras.getCharSequence(Notification.EXTRA_TEXT)
        )
        addCandidate(
            PushTestCandidateSource.EXTRA_BIG_TEXT,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        )
        addCandidate(
            PushTestCandidateSource.EXTRA_SUB_TEXT,
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        )
        addCandidate(
            PushTestCandidateSource.EXTRA_TITLE,
            extras.getCharSequence(Notification.EXTRA_TITLE)
        )
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.forEach {
                addCandidate(PushTestCandidateSource.EXTRA_TEXT_LINES, it)
            }
        addMessagingCandidates(
            extras,
            Notification.EXTRA_MESSAGES,
            PushTestCandidateSource.EXTRA_MESSAGES
        )
        addMessagingCandidates(
            extras,
            Notification.EXTRA_HISTORIC_MESSAGES,
            PushTestCandidateSource.EXTRA_HISTORIC_MESSAGES
        )
    }

    private fun MutableList<PushTestCandidate>.addCandidate(
        source: PushTestCandidateSource,
        text: CharSequence?
    ) {
        if (!text.isNullOrBlank()) add(PushTestCandidate(source, text))
    }

    @Suppress("DEPRECATION")
    private fun MutableList<PushTestCandidate>.addMessagingCandidates(
        extras: Bundle,
        key: String,
        source: PushTestCandidateSource
    ) {
        val bundles = runCatching { extras.getParcelableArray(key) }
            .getOrNull()
            ?: return
        val officialMessages =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    Notification.MessagingStyle.Message
                        .getMessagesFromBundleArray(bundles)
                }.getOrNull().orEmpty()
            } else {
                emptyList()
            }
        officialMessages.forEach { addCandidate(source, it.text) }

        bundles.filterIsInstance<Bundle>().forEach { bundle ->
            VENDOR_MESSAGE_TEXT_KEYS.forEach { textKey ->
                val value = runCatching { bundle.get(textKey) }.getOrNull()
                addCandidate(source, value as? CharSequence)
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)

    private fun notificationAccessGranted(): Boolean =
        packageName in NotificationManagerCompat.getEnabledListenerPackages(this)

    companion object {
        private const val MAX_REBIND_FAILURES = 5
        private const val EVENT_BATCH_DELAY_MS = 750L
        private const val MAX_BATCH_SIZE = 200
        private const val LISTENER_HEARTBEAT_INTERVAL_MS = 60_000L
        private const val REBIND_REQUEST_WATCHDOG_MS = 30_000L
        private val REBIND_DELAYS_MS =
            longArrayOf(30_000L, 60_000L, 300_000L, 900_000L, 900_000L)
        private val VENDOR_MESSAGE_TEXT_KEYS =
            listOf("text", "android.text", "message", "body")

        @Volatile private var activeInstance:
            WeakReference<ArrivalNotificationListener>? = null

        fun isRuntimeConnected(): Boolean =
            activeInstance?.get()?.let {
                it.listenerConnected && !it.destroying
            } == true

        fun requestActiveNotificationScan(reason: String): Boolean =
            activeInstance?.get()?.enqueueActiveNotificationScan(reason) == true

        fun requestExternalRebind(
            context: android.content.Context,
            reason: String
        ): Boolean {
            val appContext = context.applicationContext
            val prefs = com.yubegreen.luonnotar.util.LuonnotarPreferences
                .deviceProtected(appContext)
            val now = SystemClock.elapsedRealtime()
            val connected = prefs.getBoolean(
                com.yubegreen.luonnotar.util.LuonnotarPreferences
                    .KEY_NOTIFICATION_LISTENER_CONNECTED,
                false
            )
            val heartbeat = prefs.getLong(
                com.yubegreen.luonnotar.util.LuonnotarPreferences
                    .KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED,
                0L
            )
            val lastRequest = prefs.getLong(
                com.yubegreen.luonnotar.util.LuonnotarPreferences
                    .KEY_NOTIFICATION_LISTENER_REBIND_LAST_REQUEST_ELAPSED,
                0L
            )
            if (
                !NotificationListenerRecoveryPolicy.shouldRequestRebind(
                    nowElapsed = now,
                    connected = connected,
                    heartbeatElapsed = heartbeat,
                    lastRequestElapsed = lastRequest
                )
            ) return false
            val committed = prefs.edit()
                .putLong(
                    com.yubegreen.luonnotar.util.LuonnotarPreferences
                        .KEY_NOTIFICATION_LISTENER_REBIND_LAST_REQUEST_ELAPSED,
                    now
                )
                .putInt(
                    com.yubegreen.luonnotar.util.LuonnotarPreferences
                        .KEY_NOTIFICATION_LISTENER_REBIND_COUNT,
                    prefs.getInt(
                        com.yubegreen.luonnotar.util.LuonnotarPreferences
                            .KEY_NOTIFICATION_LISTENER_REBIND_COUNT,
                        0
                    ) + 1
                )
                .commit()
            if (!committed) return false
            return runCatching {
                requestRebind(
                    ComponentName(
                        appContext,
                        ArrivalNotificationListener::class.java
                    )
                )
                LogManager.event(
                    appContext,
                    "notification_listener_external_rebind_requested",
                    mapOf("reason" to reason)
                )
                true
            }.getOrDefault(false)
        }
    }
}
