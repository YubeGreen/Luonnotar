package com.yubegreen.luonnotar.privileged.embedded

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.RemoteInput
import com.flyfishxu.kadb.Kadb
import com.yubegreen.luonnotar.notification.NotificationChannelManager
import com.yubegreen.luonnotar.util.LogManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class EmbeddedAdbService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-local-adb").apply { isDaemon = true }
    }
    private val working = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val retryScheduled = AtomicBoolean(false)
    private val retryHandler = Handler(Looper.getMainLooper())
    private val connectCandidates = EmbeddedAdbPortCandidatePool()
    private var discovery: WirelessAdbDiscovery? = null
    @Volatile private var activeGeneration = -1L
    @Volatile private var retryGeneration = -1L
    @Volatile private var pairingCode: String? = null
    @Volatile private var pairingPort: Int = 0
    @Volatile private var localTcpFallbackGeneration = -1L

    private val retryRunnable = Runnable {
        retryScheduled.set(false)
        val generation = retryGeneration
        if (isActive(generation)) maybeStartEngine(generation)
    }

    override fun onCreate() {
        super.onCreate()
        LogManager.event(
            this,
            "embedded_adb_service_created",
            mapOf("pid" to android.os.Process.myPid())
        )
        startForeground(
            NotificationChannelManager.PRIVILEGED_SETUP_NOTIFICATION_ID,
            EmbeddedGuardianNotifier.setupNotification(
                this,
                "正在准备努昂诺塔特权引擎",
                "请开启无线调试；努昂诺塔会自动检测配对与连接端口。",
                waitingCode = true
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogManager.event(
            this,
            "embedded_adb_service_command_received",
            mapOf(
                "pid" to android.os.Process.myPid(),
                "action" to intent?.action.orEmpty(),
                "requestedGeneration" to (intent?.getLongExtra(EXTRA_GENERATION, -1L) ?: -1L),
                "startId" to startId
            )
        )
        val command = intent ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val snapshot = EmbeddedGuardianStore.snapshot(this)
        if (!snapshot.featureEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            EmbeddedGuardianNotifier.cancelAll(this)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val generation = command.getLongExtra(EXTRA_GENERATION, snapshot.generation)
        if (!EmbeddedGuardianStore.isGenerationActive(this, generation)) {
            LogManager.event(
                this,
                "embedded_adb_service_generation_rejected",
                mapOf(
                    "pid" to android.os.Process.myPid(),
                    "requestedGeneration" to generation,
                    "currentGeneration" to snapshot.generation,
                    "featureEnabled" to snapshot.featureEnabled,
                    "action" to command.action.orEmpty()
                )
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val generationChanged = activeGeneration != generation
        activeGeneration = generation
        when (command.action ?: ACTION_START) {
            ACTION_START, ACTION_RETRY -> startDiscovery(
                reset = command.action == ACTION_RETRY || generationChanged,
                generation = generation
            )
            ACTION_RESTART_ENGINE -> startEngineRestart(
                generation = generation,
                source = command.getStringExtra(EXTRA_RESTART_SOURCE)
                    .orEmpty()
                    .ifBlank { "engine_restart" }
            )
            ACTION_PAIR -> {
                val code = RemoteInput.getResultsFromIntent(command)
                    ?.getCharSequence(EmbeddedGuardianNotifier.REMOTE_INPUT_KEY)
                    ?.toString()?.trim()
                if (!code.orEmpty().matches(PAIRING_CODE)) {
                    state(
                        generation,
                        EmbeddedSetupState.WAITING_PAIRING_CODE,
                        "配对码必须是 6 位数字",
                        waitingCode = true
                    )
                } else {
                    pairingCode = code
                    state(
                        generation,
                        EmbeddedSetupState.WAITING_PAIRING_CODE,
                        "已收到配对码，正在等待配对端口…",
                        waitingCode = true
                    )
                    maybePair(generation)
                }
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed.set(true)
        cancelCandidateRetry()
        stopForeground(STOP_FOREGROUND_REMOVE)
        val generation = activeGeneration
        discovery?.close()
        discovery = null
        executor.shutdownNow()
        if (generation >= 0L && EmbeddedGuardianStore.isGenerationActive(this, generation)) {
            val snapshot = EmbeddedGuardianStore.snapshot(this)
            if (!snapshot.liveConnected && snapshot.setupState != EmbeddedSetupState.IDLE) {
                EmbeddedGuardianStore.updateSetupState(
                    this,
                    generation,
                    EmbeddedSetupState.FAILED,
                    "本地无线 ADB 启动流程已停止",
                    "setup_service_destroyed"
                )
                EmbeddedGuardianStore.markConnectionUnavailable(
                    this,
                    generation,
                    EmbeddedConnectionState.DISCONNECTED,
                    "本地无线 ADB 启动流程已停止",
                    "setup_service_destroyed"
                )
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startEngineRestart(generation: Long, source: String) {
        if (!isActive(generation)) return
        state(
            generation,
            EmbeddedSetupState.STARTING,
            "正在热切换 shell UID 特权引擎…",
            waitingCode = false
        )
        executor.execute {
            if (!isActive(generation)) return@execute
            discovery?.close()
            discovery = null
            cancelCandidateRetry()
            val attempt = runCatching {
                EmbeddedGuardianManager.performHotHandoff(this, generation, source)
            }.getOrElse { error ->
                LogManager.event(
                    this,
                    "embedded_engine_handoff_failed",
                    mapOf(
                        "source" to source,
                        "generation" to generation,
                        "stage" to "service",
                        "error" to error.toString()
                    )
                )
                EmbeddedGuardianManager.HandoffAttempt(false, "exception")
            }
            if (attempt.success) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@execute
            }

            LogManager.event(
                this,
                "embedded_engine_restart_fallback_adb",
                mapOf(
                    "source" to source,
                    "generation" to generation,
                    "reason" to attempt.reason,
                    "oldRevision" to attempt.oldRevision
                )
            )
            // Do not reset the persisted Kadb identity or paired flag here. An old
            // engine revision is not an authorization failure. Existing local TCP
            // 5555 / wireless-ADB authorization should be reused automatically.
            startDiscovery(reset = true, generation = generation)
        }
    }

    private fun startDiscovery(reset: Boolean, generation: Long) {
        if (!isActive(generation)) return
        if (reset) {
            pairingCode = null
            pairingPort = 0
            localTcpFallbackGeneration = -1L
            connectCandidates.clear()
            cancelCandidateRetry()
            discovery?.close()
            discovery = null
        }
        val alreadyPaired = EmbeddedGuardianStore.snapshot(this).paired
        if (!state(
                generation,
                EmbeddedSetupState.DISCOVERING,
                if (alreadyPaired) {
                    "正在自动检测无线调试连接端口…"
                } else {
                    "正在自动检测无线调试端口。首次使用请在系统中打开“使用配对码配对设备”。"
                },
                waitingCode = !alreadyPaired
            )
        ) return
        executor.execute {
            if (!isActive(generation)) return@execute
            if (tryExistingEngine(generation)) return@execute
            if (!isActive(generation)) return@execute
            runCatching { EmbeddedAdbIdentity.ensure(this) }
                .onFailure {
                    failSetup(
                        generation,
                        "ADB 身份初始化失败：${it.message}",
                        EmbeddedConnectionState.DISCONNECTED,
                        "identity_error",
                        stopDiscovery = true
                    )
                    return@execute
                }
            if (!isActive(generation)) return@execute
            offerSafeLocalTcpFallback(generation)
            if (!isActive(generation)) return@execute
            if (discovery == null) {
                discovery = WirelessAdbDiscovery(
                    context = this,
                    onPairingPort = pairingPortCallback@ { port ->
                        if (!isActive(generation)) return@pairingPortCallback
                        pairingPort = port
                        EmbeddedGuardianStore.setPairingPort(this, generation, port)
                        if (!EmbeddedGuardianStore.snapshot(this).paired) {
                            state(
                                generation,
                                EmbeddedSetupState.WAITING_PAIRING_CODE,
                                "已发现配对端口 $port；请点通知中的“输入配对码”。",
                                waitingCode = true
                            )
                            maybePair(generation)
                        }
                    },
                    onConnectPort = connectPortCallback@ { port ->
                        if (!isActive(generation)) return@connectPortCallback
                        val isNew = connectCandidates.offer(port, SystemClock.elapsedRealtime())
                        EmbeddedGuardianStore.setConnectPort(this, generation, port)
                        val paired = EmbeddedGuardianStore.snapshot(this).paired
                        if (isNew) {
                            cancelCandidateRetry()
                            state(
                                generation,
                                if (paired) EmbeddedSetupState.STARTING else EmbeddedSetupState.WAITING_PAIRING_CODE,
                                if (paired) {
                                    "已发现无线调试端口 $port，正在尝试连接…"
                                } else {
                                    "已发现无线调试端口 $port；首次使用仍需输入 6 位配对码。"
                                },
                                waitingCode = !paired
                            )
                            LogManager.event(
                                this,
                                "embedded_adb_connect_candidate",
                                mapOf(
                                    "generation" to generation,
                                    "port" to port,
                                    "candidates" to connectCandidates.ports().joinToString(",")
                                )
                            )
                        }
                        if (paired) maybeStartEngine(generation)
                    },
                    onError = { error ->
                        if (isActive(generation)) {
                            LogManager.event(this, "embedded_adb_mdns_error", mapOf("error" to error))
                        }
                    }
                ).also(WirelessAdbDiscovery::start)
            }
        }
    }

    private fun offerSafeLocalTcpFallback(generation: Long) {
        if (!isActive(generation)) return
        if (localTcpFallbackGeneration == generation) return
        localTcpFallbackGeneration = generation
        val snapshot = EmbeddedGuardianStore.snapshot(this)
        val servicePort = readSystemProperty("service.adb.tcp.port")
        val persistedPort = readSystemProperty("persist.adb.tcp.port")
        val shouldProbe = snapshot.paired &&
            servicePort.trim() == LocalAdbTcpFallbackPolicy.PORT.toString()
        val socketReachable = shouldProbe && probeLocalTcpPort(LocalAdbTcpFallbackPolicy.PORT)
        val decision = LocalAdbTcpFallbackPolicy.decide(
            paired = snapshot.paired,
            serviceTcpPort = servicePort,
            persistedTcpPort = persistedPort,
            socketReachable = socketReachable
        )
        LogManager.event(
            this,
            "embedded_adb_local_tcp_fallback",
            mapOf(
                "generation" to generation,
                "allowed" to decision.allowed,
                "reason" to decision.reason,
                "servicePort" to servicePort.take(20),
                "persistedPort" to persistedPort.take(20),
                "socketReachable" to socketReachable
            )
        )
        val port = decision.port ?: return
        if (!connectCandidates.offer(port, SystemClock.elapsedRealtime())) return
        EmbeddedGuardianStore.setConnectPort(this, generation, port)
        state(
            generation,
            EmbeddedSetupState.STARTING,
            "已验证本机传统 ADB 端口 5555，正在尝试启动特权引擎…",
            waitingCode = false
        )
        maybeStartEngine(generation)
    }

    private fun readSystemProperty(name: String): String = runCatching {
        val process = ProcessBuilder("getprop", name)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(LOCAL_PROPERTY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            ""
        } else {
            process.inputStream.bufferedReader().use { it.readText().trim() }
        }
    }.getOrDefault("")

    private fun probeLocalTcpPort(port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(
                InetSocketAddress("127.0.0.1", port),
                LOCAL_TCP_PROBE_TIMEOUT_MS
            )
        }
        true
    }.getOrDefault(false)

    private fun maybePair(generation: Long) {
        if (!isActive(generation)) return
        val code = pairingCode ?: return
        val port = pairingPort.takeIf { it in 1..65535 } ?: return
        if (!working.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (!isActive(generation)) return@execute
                state(
                    generation,
                    EmbeddedSetupState.STARTING,
                    "正在通过本机无线 ADB 配对…",
                    waitingCode = false
                )
                EmbeddedAdbIdentity.ensure(this)
                runSuspendBlocking { Kadb.pair("127.0.0.1", port, code) }
                if (!isActive(generation)) return@execute
                EmbeddedGuardianStore.markPaired(this, generation)
                localTcpFallbackGeneration = -1L
                pairingCode = null
                state(
                    generation,
                    EmbeddedSetupState.STARTING,
                    "配对成功，正在等待无线调试连接端口…",
                    waitingCode = false
                )
            } catch (error: Throwable) {
                if (isActive(generation)) {
                    pairingCode = null
                    state(
                        generation,
                        EmbeddedSetupState.WAITING_PAIRING_CODE,
                        "配对失败：${error.javaClass.simpleName}: ${error.message}；请重新输入配对码。",
                        waitingCode = true
                    )
                    EmbeddedGuardianStore.markConnectionUnavailable(
                        this,
                        generation,
                        EmbeddedConnectionState.DISCONNECTED,
                        error.toString(),
                        "pairing_failed"
                    )
                }
            } finally {
                working.set(false)
                if (isActive(generation) && EmbeddedGuardianStore.snapshot(this).paired) {
                    offerSafeLocalTcpFallback(generation)
                    maybeStartEngine(generation)
                }
            }
        }
    }

    private fun maybeStartEngine(generation: Long) {
        if (!isActive(generation)) return
        val snapshot = EmbeddedGuardianStore.snapshot(this)
        if (!snapshot.paired || snapshot.liveConnected || snapshot.setupState == EmbeddedSetupState.FAILED) return
        val selection = connectCandidates.next(SystemClock.elapsedRealtime())
        if (selection.port == null) {
            selection.retryAfterMs?.let { scheduleCandidateRetry(generation, it) }
            return
        }
        if (!working.compareAndSet(false, true)) return
        executor.execute {
            try {
                attemptConnectCandidates(generation)
            } finally {
                working.set(false)
                if (isActive(generation)) {
                    val next = connectCandidates.next(SystemClock.elapsedRealtime())
                    if (next.port != null) maybeStartEngine(generation)
                    else next.retryAfterMs?.let { scheduleCandidateRetry(generation, it) }
                }
            }
        }
    }

    private fun attemptConnectCandidates(generation: Long) {
        val endpointErrors = linkedMapOf<Int, String>()
        while (isActive(generation)) {
            val selection = connectCandidates.next(SystemClock.elapsedRealtime())
            val port = selection.port
            if (port == null) {
                if (!connectCandidates.isEmpty()) {
                    val ports = connectCandidates.ports()
                    val detail = endpointErrors.entries.joinToString("; ") { "${it.key}: ${it.value}" }
                    state(
                        generation,
                        EmbeddedSetupState.DISCOVERING,
                        "已发现的无线调试端口暂时无法连接（${ports.joinToString(", ")}）；正在等待系统发布新端口。",
                        waitingCode = false
                    )
                    EmbeddedGuardianStore.markConnectionUnavailable(
                        this,
                        generation,
                        EmbeddedConnectionState.DISCONNECTED,
                        detail.ifBlank { "all advertised ADB endpoints are cooling down" },
                        "adb_endpoint_candidates_exhausted"
                    )
                    selection.retryAfterMs?.let { scheduleCandidateRetry(generation, it) }
                }
                return
            }

            try {
                LogManager.event(
                    this,
                    "embedded_adb_endpoint_attempt",
                    mapOf(
                        "generation" to generation,
                        "port" to port,
                        "candidates" to connectCandidates.ports().joinToString(",")
                    )
                )
                startEngineOnce(port, generation)
                return
            } catch (error: Throwable) {
                if (!isActive(generation)) return
                when {
                    EmbeddedAdbFailurePolicy.isAuthorizationFailure(error) -> {
                        handleAuthorizationFailure(generation, error)
                        return
                    }
                    EmbeddedAdbFailurePolicy.isEndpointUnavailable(error) -> {
                        val retryAfter = connectCandidates.markEndpointFailure(
                            port,
                            SystemClock.elapsedRealtime()
                        )
                        endpointErrors[port] = "${error.javaClass.simpleName}: ${error.message}".take(300)
                        LogManager.event(
                            this,
                            "embedded_adb_endpoint_rejected",
                            mapOf(
                                "generation" to generation,
                                "port" to port,
                                "retryAfterMs" to retryAfter,
                                "error" to error.toString().take(600),
                                "candidates" to connectCandidates.ports().joinToString(",")
                            )
                        )
                    }
                    else -> {
                        failSetup(
                            generation,
                            "启动失败：${error.javaClass.simpleName}: ${error.message}",
                            EmbeddedConnectionState.DEAD,
                            "start_failed",
                            stopDiscovery = true
                        )
                        return
                    }
                }
            }
        }
    }

    private fun startEngineOnce(adbPort: Int, generation: Long) {
        if (!isActive(generation)) return
        state(
            generation,
            EmbeddedSetupState.STARTING,
            "正在连接本机无线 ADB 端口 $adbPort…",
            waitingCode = false
        )
        EmbeddedGuardianStore.markConnecting(this, generation, "adb_engine_start")
        EmbeddedAdbIdentity.ensure(this)
        val identity = EmbeddedGuardianStore.identity(this) ?: error("engine identity missing")
        val apkPath = applicationInfo.sourceDir
        check(apkPath.isNotBlank()) { "installed APK path is empty" }
        val command = EmbeddedGuardianStarterCommand.build(
            apkPath = apkPath,
            mainClass = EmbeddedGuardianServerMain::class.java.name,
            identity = identity
        )
        if (!isActive(generation)) return
        val response = Kadb.create("127.0.0.1", adbPort).use { adb ->
            state(
                generation,
                EmbeddedSetupState.STARTING,
                "已连接本机 adbd，正在拉起 shell UID 引擎…",
                waitingCode = false
            )
            adb.shell(command)
        }
        if (!isActive(generation)) return
        if (response.exitCode != 0) {
            error("starter exit=${response.exitCode}: ${response.allOutput.take(500)}")
        }
        var last: Throwable? = null
        repeat(24) {
            if (!isActive(generation)) return
            Thread.sleep(500L)
            if (!isActive(generation)) return
            val configured = runCatching { EmbeddedGuardianManager.configure(this, generation) }
            if (configured.isSuccess) {
                LogManager.event(
                    this,
                    "embedded_adb_state",
                    EmbeddedGuardianStore.eventFields(
                        EmbeddedGuardianStore.snapshot(this),
                        "adb_engine_start"
                    ) + mapOf(
                        "detail" to "live_handshake_connected",
                        "adbPort" to adbPort
                    )
                )
                cancelCandidateRetry()
                discovery?.close()
                discovery = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            last = configured.exceptionOrNull()
        }
        error("engine did not answer: ${last?.message}")
    }

    private fun handleAuthorizationFailure(generation: Long, error: Throwable) {
        EmbeddedAdbIdentity.reset(this)
        EmbeddedGuardianStore.markPairingInvalid(
            this,
            generation,
            "无线 ADB 授权已失效：${error.javaClass.simpleName}: ${error.message}",
            "adb_authorization_failed"
        )
        pairingCode = null
        pairingPort = 0
        localTcpFallbackGeneration = -1L
        connectCandidates.clear()
        cancelCandidateRetry()
        state(
            generation,
            EmbeddedSetupState.WAITING_PAIRING_CODE,
            "无线 ADB 授权已失效，请在系统中重新选择“使用配对码配对设备”。",
            waitingCode = true
        )
    }

    private fun tryExistingEngine(generation: Long): Boolean {
        if (!isActive(generation)) return false
        return runCatching {
            EmbeddedGuardianManager.configure(this, generation)
            check(isActive(generation)) { "embedded setup superseded" }
            cancelCandidateRetry()
            discovery?.close()
            discovery = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            true
        }.getOrDefault(false)
    }

    private fun scheduleCandidateRetry(generation: Long, retryAfterMs: Long) {
        if (!isActive(generation)) return
        retryGeneration = generation
        if (!retryScheduled.compareAndSet(false, true)) return
        retryHandler.postDelayed(
            retryRunnable,
            retryAfterMs.coerceIn(MIN_RETRY_DELAY_MS, EmbeddedAdbPortCandidatePool.DEFAULT_COOLDOWN_MS)
        )
    }

    private fun cancelCandidateRetry() {
        retryHandler.removeCallbacks(retryRunnable)
        retryScheduled.set(false)
        retryGeneration = -1L
    }

    private fun <T> runSuspendBlocking(block: suspend () -> T): T {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<Result<T>?>(null)
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                outcome.set(result)
                latch.countDown()
            }
        })
        latch.await()
        return outcome.get()?.getOrThrow()
            ?: error("suspend ADB operation completed without a result")
    }

    private fun failSetup(
        generation: Long,
        text: String,
        connectionState: EmbeddedConnectionState,
        source: String,
        stopDiscovery: Boolean
    ) {
        if (stopDiscovery) {
            cancelCandidateRetry()
            discovery?.close()
            discovery = null
        }
        if (!state(
                generation,
                EmbeddedSetupState.FAILED,
                text,
                waitingCode = false
            )
        ) return
        EmbeddedGuardianStore.markConnectionUnavailable(
            this,
            generation,
            connectionState,
            text,
            source
        )
    }

    private fun state(
        generation: Long,
        setupState: EmbeddedSetupState,
        text: String,
        waitingCode: Boolean
    ): Boolean {
        if (!isActive(generation)) return false
        val accepted = EmbeddedGuardianStore.updateSetupState(
            this,
            generation,
            setupState,
            if (setupState == EmbeddedSetupState.FAILED) text else "",
            "embedded_adb_service"
        )
        if (!accepted || !isActive(generation)) return false
        val notification = EmbeddedGuardianNotifier.setupNotification(
            this,
            if (setupState == EmbeddedSetupState.FAILED) {
                "努昂诺塔特权引擎启动失败"
            } else {
                "努昂诺塔本机无线 ADB"
            },
            text,
            waitingCode
        )
        getSystemService(android.app.NotificationManager::class.java).notify(
            NotificationChannelManager.PRIVILEGED_SETUP_NOTIFICATION_ID,
            notification
        )
        LogManager.event(
            this,
            "embedded_adb_state",
            EmbeddedGuardianStore.eventFields(
                EmbeddedGuardianStore.snapshot(this),
                "embedded_adb_service"
            ) + mapOf("detail" to text.take(300))
        )
        return true
    }

    private fun isActive(generation: Long): Boolean =
        !destroyed.get() &&
            activeGeneration == generation &&
            EmbeddedGuardianStore.isGenerationActive(this, generation)

    companion object {
        const val ACTION_START = "com.yubegreen.luonnotar.action.EMBEDDED_ADB_START"
        const val ACTION_RETRY = "com.yubegreen.luonnotar.action.EMBEDDED_ADB_RETRY"
        const val ACTION_PAIR = "com.yubegreen.luonnotar.action.EMBEDDED_ADB_PAIR"
        const val ACTION_STOP = "com.yubegreen.luonnotar.action.EMBEDDED_ADB_STOP"
        const val ACTION_RESTART_ENGINE =
            "com.yubegreen.luonnotar.action.EMBEDDED_ADB_RESTART_ENGINE"
        const val EXTRA_GENERATION = "embedded_guardian_generation"
        const val EXTRA_RESTART_SOURCE = "embedded_guardian_restart_source"
        private const val LOCAL_PROPERTY_TIMEOUT_MS = 1_000L
        private const val LOCAL_TCP_PROBE_TIMEOUT_MS = 600
        private const val MIN_RETRY_DELAY_MS = 1_000L
        private val PAIRING_CODE = Regex("^[0-9]{6}$")
    }
}
