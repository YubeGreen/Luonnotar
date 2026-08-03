package com.yubegreen.luonnotar.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserManager
import com.yubegreen.luonnotar.BuildConfig
import com.yubegreen.luonnotar.util.LogManager
import rikka.shizuku.Shizuku
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * App-UID control plane for the daemon Shizuku/Sui UserService.
 * No liveness guarantee depends on this object after the daemon has started.
 */
object PrivilegedGuardianController {
    const val PERMISSION_REQUEST_CODE = 20_000

    private data class PendingConnection(
        val startEngine: Boolean,
        val callback: (Result<String>) -> Unit
    )

    private val initialized = AtomicBoolean(false)
    private val binding = AtomicBoolean(false)
    private val refreshInFlight = AtomicBoolean(false)
    private val bindGeneration = AtomicLong(0L)
    private val bindRecoveryAttempts = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-privileged-control").apply { isDaemon = true }
    }
    private val pendingLock = Any()
    private val pendingConnections = ArrayDeque<PendingConnection>()

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var remote: IPrivilegedGuardian? = null

    @Volatile
    private var activeConnection: ServiceConnection? = null


    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val context = applicationContext ?: return@OnBinderReceivedListener
        PrivilegedGuardianStore.updateConnection(context, "shizuku_ready")
        connectIfEnabled(context)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        bindGeneration.incrementAndGet()
        activeConnection = null
        remote = null
        binding.set(false)
        bindRecoveryAttempts.set(0)
        val error = IllegalStateException("Shizuku binder died")
        failPending(error)
        applicationContext?.let {
            if (PrivilegedGuardianStore.isEnabled(it)) {
                PrivilegedGuardianStore.updateConnection(
                    it,
                    "shizuku_dead",
                    error.message.orEmpty()
                )
            } else {
                PrivilegedGuardianStore.markDisabled(it)
            }
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
        val context = applicationContext ?: return@OnRequestPermissionResultListener
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            PrivilegedGuardianStore.updateConnection(context, "permission_granted")
            connectIfEnabled(context)
        } else {
            PrivilegedGuardianStore.updateConnection(context, "permission_denied", "Shizuku permission denied")
        }
    }

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        val unlocked = runCatching {
            context.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        }.getOrDefault(false)
        if (!unlocked) {
            PrivilegedGuardianStore.updateConnection(context, "user_locked")
            return
        }
        if (!initialized.compareAndSet(false, true)) return
        val setup = runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }
        if (setup.isFailure) {
            initialized.set(false)
            val error = setup.exceptionOrNull()
            PrivilegedGuardianStore.updateConnection(
                context,
                "shizuku_unavailable",
                "${error?.javaClass?.simpleName}: ${error?.message}"
            )
            return
        }
        connectIfEnabled(context)
    }

    fun isShizukuAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestPermission(context: Context) {
        initialize(context)
        when {
            !isShizukuAvailable() -> PrivilegedGuardianStore.updateConnection(
                context,
                "shizuku_unavailable",
                "Shizuku/Sui service is not running"
            )
            hasPermission() -> {
                bindRecoveryAttempts.set(0)
                connect(context, startEngine = PrivilegedGuardianStore.isEnabled(context))
            }
            else -> runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
                .onFailure {
                    PrivilegedGuardianStore.updateConnection(
                        context,
                        "permission_request_failed",
                        "${it.javaClass.simpleName}: ${it.message}"
                    )
                }
        }
    }

    fun connectIfEnabled(context: Context) {
        initialize(context)
        if (PrivilegedGuardianStore.isEnabled(context)) connect(context, startEngine = true)
    }

    fun setEnabled(context: Context, enabled: Boolean, callback: (Result<String>) -> Unit = {}) {
        initialize(context)
        val persisted = if (enabled) {
            PrivilegedGuardianStore.beginEnable(context)
        } else {
            PrivilegedGuardianStore.beginDisable(context)
        }
        if (!persisted) {
            callback(Result.failure(IllegalStateException("cannot persist privileged guardian state")))
            return
        }
        if (enabled) {
            bindRecoveryAttempts.set(0)
            when {
                !isShizukuAvailable() -> {
                    val error = IllegalStateException("Shizuku/Sui 未运行")
                    PrivilegedGuardianStore.updateConnection(
                        context,
                        "shizuku_unavailable",
                        error.message.orEmpty()
                    )
                    callback(Result.failure(error))
                }
                !hasPermission() -> {
                    requestPermission(context)
                    callback(Result.failure(SecurityException("需要授予 Shizuku 权限")))
                }
                else -> connect(context, startEngine = true, callback = callback)
            }
        } else {
            val cancellation = CancellationException(
                "Privileged Guardian disabled before connection"
            )
            cancelBindingAttempt(context, cancellation)
            val guardian = remote
            if (guardian == null) {
                PrivilegedGuardianStore.markDisabled(context)
                callback(Result.success("disabled"))
            } else {
                executor.execute {
                    val result = runCatching { guardian.stop() }
                        .onSuccess { PrivilegedGuardianStore.markDisabled(context, it) }
                        .onFailure { error -> recordStopFailure(context, error) }
                    callback(result)
                }
            }
        }
    }

    fun setGmsRecoveryEnabled(
        context: Context,
        enabled: Boolean,
        callback: (Result<String>) -> Unit = {}
    ) {
        initialize(context)
        if (!PrivilegedGuardianStore.setGmsRecoveryEnabled(context, enabled)) {
            callback(Result.failure(IllegalStateException("cannot persist GMS recovery state")))
            return
        }
        val guardian = remote
        if (guardian == null) {
            if (PrivilegedGuardianStore.isEnabled(context)) {
                connect(context, startEngine = true, callback = callback)
            } else {
                callback(Result.success("gms_recovery_configured=$enabled"))
            }
            return
        }
        executor.execute {
            callback(
                runRemoteResult(context, "gms_recovery_config") {
                    it.configureAndStart(currentConfig(context).toJson())
                }
            )
        }
    }

    fun recoverGmsNow(context: Context, callback: (Result<String>) -> Unit = {}) {
        initialize(context)
        if (!PrivilegedGuardianStore.isEnabled(context)) {
            callback(Result.failure(IllegalStateException("请先开启 Privileged Guardian")))
            return
        }
        val guardian = remote
        if (guardian == null) {
            connect(context, startEngine = true) { connected ->
                if (connected.isFailure) {
                    callback(connected)
                } else {
                    recoverGmsNow(context, callback)
                }
            }
            return
        }
        executor.execute {
            callback(runRemoteResult(context, "gms_recovery_manual") { it.recoverGms() })
        }
    }

    fun runCycle(context: Context, callback: (Result<String>) -> Unit = {}) {
        initialize(context)
        if (!PrivilegedGuardianStore.isEnabled(context)) {
            callback(Result.failure(IllegalStateException("请先开启 Privileged Guardian")))
            return
        }
        val guardian = remote
        if (guardian == null) {
            // configureAndStart performs an immediate first cycle; report only after it finishes.
            connect(context, startEngine = true, callback = callback)
            return
        }
        executor.execute { callback(runRemoteResult(context, "manual_cycle") { it.runCycle() }) }
    }

    fun refresh(context: Context, callback: (Result<String>) -> Unit = {}) {
        initialize(context)
        val guardian = remote
        if (guardian == null) {
            callback(Result.failure(IllegalStateException("Privileged Guardian 未连接")))
            return
        }
        executor.execute { callback(runRemoteResult(context, "status") { it.getStatusJson() }) }
    }

    fun refreshIfStale(context: Context, minimumAgeMs: Long = 5_000L) {
        initialize(context)
        if (remote == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        val last = PrivilegedGuardianStore.lastUpdatedElapsed(context)
        if (last > 0L && now >= last && now - last < minimumAgeMs) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        executor.execute {
            try {
                runRemoteResult(context, "status") { it.getStatusJson() }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    fun snapshot(context: Context): PrivilegedGuardianSnapshot =
        PrivilegedGuardianSnapshot.fromStore(
            context = context,
            shizukuAvailable = isShizukuAvailable(),
            permissionGranted = hasPermission()
        )

    private fun connect(
        context: Context,
        startEngine: Boolean,
        callback: ((Result<String>) -> Unit)? = null
    ) {
        initialize(context)
        if (remote != null) {
            executor.execute {
                val result = runRemoteResult(context, if (startEngine) "start" else "status") {
                    if (startEngine) {
                        it.configureAndStart(currentConfig(context).toJson())
                    } else {
                        it.getStatusJson()
                    }
                }
                callback?.invoke(result)
            }
            return
        }
        if (!isShizukuAvailable()) {
            callback?.invoke(Result.failure(IllegalStateException("Shizuku/Sui 未运行")))
            return
        }
        if (!hasPermission()) {
            callback?.invoke(Result.failure(SecurityException("Shizuku permission missing")))
            return
        }
        recoverStaleBindingIfNeeded(context)
        if (callback != null) {
            synchronized(pendingLock) {
                pendingConnections.addLast(PendingConnection(startEngine, callback))
            }
        }
        if (!binding.compareAndSet(false, true)) return
        val generation = bindGeneration.incrementAndGet()
        val connection = createConnection(generation)
        activeConnection = connection
        PrivilegedGuardianStore.updateConnection(context, "binding")
        runCatching {
            Shizuku.bindUserService(userServiceArgs(context), connection)
        }.onSuccess {
            scheduleBindTimeout(context, generation, connection)
        }.onFailure { error ->
            if (
                activeConnection === connection &&
                bindGeneration.compareAndSet(generation, generation + 1L)
            ) {
                activeConnection = null
                binding.set(false)
            }
            PrivilegedGuardianStore.updateConnection(
                context,
                "bind_failed",
                "${error.javaClass.simpleName}: ${error.message}"
            )
            failPending(error)
        }
    }


    private fun createConnection(generation: Long): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                handleServiceConnected(generation, this, service)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                handleServiceDisconnected(generation, this)
            }
        }

    private fun handleServiceConnected(
        generation: Long,
        connection: ServiceConnection,
        service: IBinder?
    ) {
        val context = applicationContext ?: return
        if (bindGeneration.get() != generation || activeConnection !== connection) {
            // unbindUserService(remove=false) clears the service-wide connection bucket,
            // not only this stale callback. Ignore it so a newer bind is not detached.
            return
        }
        binding.set(false)
        bindRecoveryAttempts.set(0)
        val guardian = service?.let(IPrivilegedGuardian.Stub::asInterface)
        remote = guardian
        if (guardian == null) {
            activeConnection = null
            bindGeneration.compareAndSet(generation, generation + 1L)
            runCatching {
                Shizuku.unbindUserService(userServiceArgs(context), connection, true)
            }
            val error = IllegalStateException("Shizuku UserService returned a null binder")
            PrivilegedGuardianStore.updateConnection(
                context,
                "bind_failed",
                error.message.orEmpty()
            )
            failPending(error)
            return
        }
        if (!PrivilegedGuardianStore.isEnabled(context)) {
            val error = CancellationException(
                "Privileged Guardian was disabled before UserService connected"
            )
            failPending(error)
            executor.execute {
                runCatching { guardian.stop() }
                    .onSuccess { PrivilegedGuardianStore.markDisabled(context, it) }
                    .onFailure { recordStopFailure(context, it) }
            }
            return
        }
        PrivilegedGuardianStore.updateConnection(context, "connected")
        val pending = synchronized(pendingLock) {
            buildList {
                while (pendingConnections.isNotEmpty()) add(pendingConnections.removeFirst())
            }
        }
        executor.execute {
            if (pending.isEmpty()) {
                runRemoteResult(context, "connect") {
                    if (PrivilegedGuardianStore.isEnabled(context)) {
                        it.configureAndStart(currentConfig(context).toJson())
                    } else {
                        it.getStatusJson()
                    }
                }
            } else {
                pending.forEach { request ->
                    val result = if (
                        request.startEngine && !PrivilegedGuardianStore.isEnabled(context)
                    ) {
                        Result.failure(
                            CancellationException(
                                "Privileged Guardian was disabled before connection completed"
                            )
                        )
                    } else {
                        runRemoteResult(
                            context,
                            if (request.startEngine) "start" else "status"
                        ) {
                            if (request.startEngine) {
                                it.configureAndStart(currentConfig(context).toJson())
                            } else {
                                it.getStatusJson()
                            }
                        }
                    }
                    runCatching { request.callback(result) }
                }
            }
        }
    }

    private fun handleServiceDisconnected(
        generation: Long,
        connection: ServiceConnection
    ) {
        if (bindGeneration.get() != generation || activeConnection !== connection) return
        activeConnection = null
        bindGeneration.compareAndSet(generation, generation + 1L)
        remote = null
        binding.set(false)
        val error = IllegalStateException("Shizuku UserService disconnected")
        failPending(error)
        applicationContext?.let {
            if (PrivilegedGuardianStore.isEnabled(it)) {
                PrivilegedGuardianStore.updateConnection(
                    it,
                    "disconnected",
                    error.message.orEmpty()
                )
            } else {
                PrivilegedGuardianStore.markDisabled(it)
            }
        }
    }

    private fun recoverStaleBindingIfNeeded(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        val updated = PrivilegedGuardianStore.lastUpdatedElapsed(context)
        val age = updated.takeIf { it > 0L && now >= it }?.let { now - it }
        if (
            PrivilegedGuardianBindingPolicy.shouldResetStaleBind(
                bindingFlag = binding.get(),
                remoteConnected = remote != null,
                connectionState = PrivilegedGuardianStore.connectionState(context),
                stateAgeMs = age
            )
        ) {
            cancelBindingAttempt(
                context,
                IllegalStateException("stale Shizuku UserService bind was reset")
            )
            PrivilegedGuardianStore.updateConnection(context, "bind_reset")
        }
    }

    private fun scheduleBindTimeout(
        context: Context,
        generation: Long,
        connection: ServiceConnection
    ) {
        mainHandler.postDelayed(
            {
                if (
                    bindGeneration.get() != generation ||
                    activeConnection !== connection ||
                    remote != null
                ) return@postDelayed
                if (!binding.compareAndSet(true, false)) return@postDelayed
                activeConnection = null
                bindGeneration.compareAndSet(generation, generation + 1L)
                runCatching {
                    Shizuku.unbindUserService(userServiceArgs(context), connection, true)
                }
                val error = TimeoutException(
                    "Shizuku UserService bind timed out after " +
                        "${PrivilegedGuardianBindingPolicy.BIND_TIMEOUT_MS} ms"
                )
                val attempt = bindRecoveryAttempts.incrementAndGet()
                if (
                    attempt <= MAX_STALE_SERVICE_RECOVERY_ATTEMPTS &&
                    PrivilegedGuardianStore.isEnabled(context) &&
                    isShizukuAvailable() &&
                    hasPermission()
                ) {
                    PrivilegedGuardianStore.updateConnection(
                        context,
                        "bind_recovering",
                        "stale UserService removed; retry $attempt/$MAX_STALE_SERVICE_RECOVERY_ATTEMPTS"
                    )
                    mainHandler.postDelayed(
                        { connect(context, startEngine = true) },
                        STALE_SERVICE_RETRY_DELAY_MS
                    )
                } else {
                    PrivilegedGuardianStore.updateConnection(
                        context,
                        "bind_timeout",
                        error.message.orEmpty()
                    )
                    failPending(error)
                }
            },
            PrivilegedGuardianBindingPolicy.BIND_TIMEOUT_MS
        )
    }

    private fun cancelBindingAttempt(context: Context, error: Throwable) {
        val wasBinding = binding.getAndSet(false)
        if (!wasBinding) {
            failPending(error)
            return
        }
        val connection = activeConnection
        activeConnection = null
        bindGeneration.incrementAndGet()
        if (connection != null) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs(context), connection, true)
            }
        }
        failPending(error)
    }

    private fun recordStopFailure(context: Context, error: Throwable) {
        // A failed stop means the daemon may still be running. Keep configuredEnabled=true
        // so the UI cannot falsely present the engine as disabled.
        PrivilegedGuardianStore.beginEnable(context)
        PrivilegedGuardianStore.updateConnection(
            context,
            "stop_failed",
            "${error.javaClass.simpleName}: ${error.message}"
        )
    }

    private fun failPending(error: Throwable) {
        val pending = synchronized(pendingLock) {
            buildList {
                while (pendingConnections.isNotEmpty()) add(pendingConnections.removeFirst())
            }
        }
        pending.forEach { request -> runCatching { request.callback(Result.failure(error)) } }
    }

    fun engineConfigJson(context: Context): String = currentConfig(context).toJson()

    private fun currentConfig(context: Context): GuardianEngineConfig =
        GuardianEngineConfig(
            gmsRecoveryEnabled = PrivilegedGuardianStore.isGmsRecoveryEnabled(context)
        ).normalized()

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context, PrivilegedGuardianUserService::class.java)
        )
            .daemon(true)
            .tag("luonnotar.guardian.v2")
            .processNameSuffix("guardian_v2")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)

    private const val MAX_STALE_SERVICE_RECOVERY_ATTEMPTS = 1
    private const val STALE_SERVICE_RETRY_DELAY_MS = 750L

    private fun runRemoteResult(
        context: Context,
        operation: String,
        action: (IPrivilegedGuardian) -> String
    ): Result<String> {
        val guardian = remote
            ?: return Result.failure(IllegalStateException("Privileged Guardian not connected"))
        return runCatching { action(guardian) }
            .onSuccess { status ->
                PrivilegedGuardianStore.updateStatus(context, status)
                LogManager.event(
                    context,
                    "privileged_guardian_$operation",
                    mapOf("statusBytes" to status.length)
                )
            }
            .onFailure { error ->
                if (error is android.os.DeadObjectException) remote = null
                PrivilegedGuardianStore.updateConnection(
                    context,
                    "remote_error",
                    "${error.javaClass.simpleName}: ${error.message}"
                )
                LogManager.event(
                    context,
                    "privileged_guardian_${operation}_failed",
                    mapOf("error" to error.toString())
                )
            }
    }
}
