package com.yubegreen.luonnotar.privileged.embedded

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.yubegreen.luonnotar.privileged.PrivilegedGuardianController
import com.yubegreen.luonnotar.util.LogManager
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object EmbeddedGuardianManager {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-embedded-control").apply { isDaemon = true }
    }
    private val disableExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-embedded-disable").apply { isDaemon = true }
    }
    private val refreshInFlight = AtomicBoolean(false)
    private val lastRefreshAttemptElapsed = AtomicLong(0L)
    private val lastAutoRepairDispatchElapsed = AtomicLong(0L)

    fun initialize(context: Context) {
        val app = context.applicationContext
        if (!EmbeddedGuardianStore.snapshot(app).featureEnabled) return
        refreshIfStale(app, minAgeMs = 0L)
    }

    /**
     * Requests a controlled engine restart without discarding the persisted ADB
     * host identity. r260+ engines first try a loopback hot handoff; older or
     * unreachable engines fall back to the existing local ADB startup path.
     */
    fun restartEngine(context: Context, source: String = "explicit_engine_restart") {
        val app = context.applicationContext
        val snapshot = EmbeddedGuardianStore.snapshot(app)
        check(snapshot.featureEnabled) { "embedded feature disabled" }
        check(EmbeddedGuardianStore.prepareEngineRestart(app, snapshot.generation, source)) {
            "embedded restart generation superseded"
        }
        LogManager.event(
            app,
            "embedded_engine_restart_requested",
            EmbeddedGuardianStore.eventFields(snapshot, source) +
                mapOf("expectedRevision" to EmbeddedGuardianProtocol.ENGINE_REVISION)
        )
        runCatching {
            ContextCompat.startForegroundService(
                app,
                Intent(app, EmbeddedAdbService::class.java)
                    .setAction(EmbeddedAdbService.ACTION_RESTART_ENGINE)
                    .putExtra(EmbeddedAdbService.EXTRA_GENERATION, snapshot.generation)
                    .putExtra(EmbeddedAdbService.EXTRA_RESTART_SOURCE, source.take(120))
            )
        }.onFailure { error ->
            EmbeddedGuardianStore.markConnectionUnavailable(
                app,
                snapshot.generation,
                EmbeddedConnectionState.DEAD,
                error.toString(),
                "engine_restart_service_failed"
            )
            throw error
        }
    }

    fun startSetup(context: Context, source: String = "explicit_user_enable") {
        val app = context.applicationContext
        val session = EmbeddedGuardianStore.beginUserSetup(app, source)
        lastRefreshAttemptElapsed.set(0L)
        runCatching {
            ContextCompat.startForegroundService(
                app,
                Intent(app, EmbeddedAdbService::class.java)
                    .setAction(EmbeddedAdbService.ACTION_START)
                    .putExtra(EmbeddedAdbService.EXTRA_GENERATION, session.generation)
            )
        }.onFailure { error ->
            EmbeddedGuardianStore.updateSetupState(
                app,
                session.generation,
                EmbeddedSetupState.FAILED,
                error.toString(),
                "start_setup_service_failed"
            )
            EmbeddedGuardianStore.markConnectionUnavailable(
                app,
                session.generation,
                EmbeddedConnectionState.DISCONNECTED,
                error.toString(),
                "start_setup_service_failed"
            )
            throw error
        }
    }

    fun stop(
        context: Context,
        source: String = "explicit_user_disable",
        callback: (Result<String>) -> Unit = {}
    ) {
        val app = context.applicationContext
        val localResult = runCatching {
            val before = EmbeddedGuardianStore.snapshot(app)
            LogManager.event(
                app,
                "embedded_disable_requested",
                EmbeddedGuardianStore.eventFields(before, source)
            )
            val session = EmbeddedGuardianStore.disableFeature(app, source)
            val plan = EmbeddedGuardianStatePolicy.disablePlan(
                session.previous.runtime,
                session.previousIdentity != null
            )
            if (plan.stopLocalSetupService) {
                app.stopService(Intent(app, EmbeddedAdbService::class.java))
            }
            if (plan.cancelSetupNotification || plan.cancelRebootNotification) {
                EmbeddedGuardianNotifier.cancelAll(app)
            }
            val disabled = EmbeddedGuardianStore.snapshot(app)
            LogManager.event(
                app,
                "embedded_disable_completed",
                EmbeddedGuardianStore.eventFields(disabled, source) +
                    mapOf("remoteStopPending" to plan.attemptRemoteStop)
            )
            session to plan
        }
        localResult.onFailure { callback(Result.failure(it)) }
        val (session, plan) = localResult.getOrNull() ?: return
        callback(Result.success("disabled"))

        if (!plan.attemptRemoteStop) {
            LogManager.event(
                app,
                "embedded_disable_remote_unavailable",
                EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                    mapOf("reason" to "identity_unavailable")
            )
            return
        }
        disableExecutor.execute {
            val identity = session.previousIdentity
            val remoteResult = runCatching {
                requireNotNull(identity) { "engine identity missing" }
                val client = EmbeddedGuardianClient(
                    identity.port,
                    identity.token,
                    connectTimeoutMs = 1_500,
                    readTimeoutMs = 5_000
                )
                client.destroy()
                awaitRemoteStop(identity)
            }
            if (remoteResult.isFailure || remoteResult.getOrDefault(false).not()) {
                LogManager.event(
                    app,
                    "embedded_disable_remote_unavailable",
                    EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                        mapOf(
                            "reason" to if (remoteResult.isFailure) {
                                remoteResult.exceptionOrNull().toString()
                            } else {
                                "engine_still_reachable_after_destroy"
                            }
                        )
                )
            }
            if (EmbeddedGuardianStore.isDisabledGeneration(app, session.generation)) {
                EmbeddedGuardianNotifier.cancelAll(app)
            }
        }
    }

    fun refreshIfStale(
        context: Context,
        minAgeMs: Long = 5_000L,
        callback: (Result<String>) -> Unit = {}
    ) {
        val app = context.applicationContext
        val snapshot = EmbeddedGuardianStore.snapshot(app)
        if (!snapshot.featureEnabled) {
            callback(Result.success(snapshot.lastStatus))
            return
        }
        if (EmbeddedLiveRefreshPolicy.shouldDefer(snapshot.setupState)) {
            callback(Result.success(snapshot.lastStatus))
            return
        }
        val now = SystemClock.elapsedRealtime()
        val last = lastRefreshAttemptElapsed.get()
        if (now - last < minAgeMs || !refreshInFlight.compareAndSet(false, true)) {
            callback(Result.success(snapshot.lastStatus))
            return
        }
        lastRefreshAttemptElapsed.set(now)
        refresh(app) { result ->
            refreshInFlight.set(false)
            callback(result)
        }
    }

    fun refresh(context: Context, callback: (Result<String>) -> Unit = {}) {
        val app = context.applicationContext
        executor.execute {
            var generation = -1L
            var identityAvailable = false
            val result = runCatching {
                val snapshot = EmbeddedGuardianStore.snapshot(app)
                check(snapshot.featureEnabled) { "embedded feature disabled" }
                if (EmbeddedLiveRefreshPolicy.shouldDefer(snapshot.setupState)) {
                    return@runCatching snapshot.lastStatus
                }
                generation = snapshot.generation
                val identity = EmbeddedGuardianStore.identity(app)
                    ?: error("engine identity missing")
                identityAvailable = true
                if (!snapshot.liveConnected) {
                    check(
                        EmbeddedGuardianStore.markConnecting(
                            app,
                            generation,
                            "live_refresh"
                        )
                    ) { "embedded setup superseded" }
                }
                val client = EmbeddedGuardianClient(
                    identity.port,
                    identity.token,
                    connectTimeoutMs = 2_500,
                    readTimeoutMs = STATUS_READ_TIMEOUT_MS
                )
                val live = validateLiveHandshake(client)
                check(
                    EmbeddedGuardianStore.recordLiveHandshake(
                        app,
                        generation,
                        live.uid,
                        live.status,
                        "live_refresh"
                    )
                ) { "embedded refresh superseded" }
                EmbeddedBackgroundPolicyStore.recordFromEngineStatus(
                    app,
                    live.status,
                    "live_refresh"
                )
                EmbeddedGuardianNotifier.cancelRebootReminder(app)
                live.status
            }.onFailure { error ->
                if (generation >= 0L) {
                    val identity = if (identityAvailable) EmbeddedGuardianStore.identity(app) else null
                    val engineDead = if (
                        identity != null && error is EmbeddedEngineStatusStaleException
                    ) {
                        terminateStaleEngine(
                            app = app,
                            generation = generation,
                            identity = identity,
                            error = error,
                            source = "live_refresh_stale_status"
                        )
                    } else if (identity != null) {
                        preserveConnectionOrMarkDead(
                            app = app,
                            generation = generation,
                            identity = identity,
                            error = error,
                            source = "live_refresh_failed"
                        )
                    } else {
                        EmbeddedGuardianStore.markConnectionUnavailable(
                            app,
                            generation,
                            EmbeddedConnectionState.DISCONNECTED,
                            error.toString(),
                            "live_refresh_failed"
                        )
                        true
                    }
                    if (engineDead) {
                        val afterFailure = EmbeddedGuardianStore.snapshot(app)
                        val now = SystemClock.elapsedRealtime()
                        val shouldRepair = EmbeddedAutoRepairPolicy.shouldDispatch(
                            featureEnabled = afterFailure.featureEnabled,
                            setupState = afterFailure.runtime.setupState,
                            nowElapsed = now,
                            lastDispatchElapsed = lastAutoRepairDispatchElapsed.get()
                        )
                        if (shouldRepair) {
                            lastAutoRepairDispatchElapsed.set(now)
                            runCatching { startSetup(app, "live_refresh_auto_repair") }
                                .onFailure { repairError ->
                                    LogManager.event(
                                        app,
                                        "embedded_auto_repair_dispatch_failed",
                                        mapOf("error" to repairError.toString())
                                    )
                                }
                        }
                    }
                }
            }
            callback(result)
        }
    }

    fun runCycle(context: Context, callback: (Result<String>) -> Unit = {}) =
        execute(context, "cycle", callback) { it.cycle() }

    fun recoverGms(context: Context, callback: (Result<String>) -> Unit = {}) {
        val app = context.applicationContext
        executor.execute {
            var generation = -1L
            var identity: EmbeddedGuardianStore.EndpointIdentity? = null
            val result = runCatching {
                val snapshot = EmbeddedGuardianStore.snapshot(app)
                check(snapshot.featureEnabled) { "embedded feature disabled" }
                generation = snapshot.generation
                check(snapshot.liveConnected) { "embedded engine has no live connection" }
                identity = EmbeddedGuardianStore.identity(app) ?: error("engine identity missing")
                val endpoint = requireNotNull(identity)
                val client = EmbeddedGuardianClient(
                    endpoint.port,
                    endpoint.token,
                    connectTimeoutMs = 2_500,
                    readTimeoutMs = RECOVERY_REQUEST_TIMEOUT_MS
                )
                val ping = validatePing(client.ping())
                check(EmbeddedGuardianStore.isGenerationActive(app, generation)) {
                    "embedded operation superseded"
                }
                val accepted = client.recoverGms()
                val response = JSONObject(accepted)
                check(response.optBoolean("accepted", false)) {
                    "GMS recovery request rejected: ${response.optString("reason", "unknown")}" 
                }
                val postPing = probePing(endpoint).getOrThrow()
                check(postPing.uid == ping.uid) { "embedded ping identity changed" }
                check(
                    EmbeddedGuardianStore.recordLivePing(
                        app,
                        generation,
                        postPing.uid,
                        "GMS recovery accepted; result pending",
                        "privileged_operation_recover_gms_accepted"
                    )
                ) { "embedded operation superseded" }
                accepted
            }.onFailure { error ->
                val endpoint = identity
                if (generation >= 0L && endpoint != null) {
                    preserveConnectionOrMarkDead(
                        app = app,
                        generation = generation,
                        identity = endpoint,
                        error = error,
                        source = "privileged_operation_recover_gms_failed"
                    )
                }
                LogManager.event(
                    app,
                    "embedded_guardian_recover_gms_failed",
                    mapOf("error" to error.toString())
                )
            }
            callback(result)
        }
    }

    internal fun performHotHandoff(
        context: Context,
        generation: Long,
        source: String
    ): HandoffAttempt {
        val app = context.applicationContext
        if (!EmbeddedGuardianStore.isGenerationActive(app, generation)) {
            return HandoffAttempt(false, "generation_superseded")
        }
        val identity = EmbeddedGuardianStore.identity(app)
            ?: return HandoffAttempt(false, "identity_missing")
        val client = EmbeddedGuardianClient(
            identity.port,
            identity.token,
            connectTimeoutMs = FAST_PING_TIMEOUT_MS,
            readTimeoutMs = FAST_PING_TIMEOUT_MS
        )
        val oldPing = runCatching { parsePing(client.ping(), requireCurrentRevision = false) }
            .getOrElse { error ->
                LogManager.event(
                    app,
                    "embedded_engine_handoff_unavailable",
                    EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                        mapOf("reason" to "engine_unreachable", "error" to error.toString())
                )
                return HandoffAttempt(false, "engine_unreachable")
            }
        if (
            oldPing.engineRevision < EmbeddedGuardianProtocol.MIN_HANDOFF_ENGINE_REVISION ||
            !oldPing.handoffSupported
        ) {
            // Pre-r260 engines cannot spawn a successor, but they already support the
            // authenticated destroy operation. Retire the legacy UID 2000 process over
            // loopback first, then let EmbeddedAdbService reuse the persisted Kadb key
            // to start the new APK. This is the one-time bridge from r259 (and older)
            // into the hot-handoff era and does not require re-pairing unless adbd later
            // reports a real authorization failure.
            val retired = retireLegacyEngine(identity, oldPing.pid)
            LogManager.event(
                app,
                "embedded_engine_handoff_unavailable",
                EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                    mapOf(
                        "reason" to if (retired) {
                            "old_revision_retired_for_adb_restart"
                        } else {
                            "old_revision_retire_failed"
                        },
                        "oldRevision" to oldPing.engineRevision,
                        "oldPid" to oldPing.pid,
                        "handoffSupported" to oldPing.handoffSupported
                    )
            )
            return HandoffAttempt(
                false,
                if (retired) "old_revision_retired_for_adb_restart" else "old_revision_retire_failed",
                oldPing.engineRevision
            )
        }

        val apkPath = app.applicationInfo.sourceDir.orEmpty()
        if (apkPath.isBlank()) return HandoffAttempt(false, "apk_path_missing", oldPing.engineRevision)
        val request = JSONObject()
            .put("apkPath", apkPath)
            .put("expectedRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
            .put("reason", source.take(120))
            .toString()
        LogManager.event(
            app,
            "embedded_engine_handoff_started",
            EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                mapOf(
                    "oldRevision" to oldPing.engineRevision,
                    "expectedRevision" to EmbeddedGuardianProtocol.ENGINE_REVISION,
                    "oldPid" to oldPing.pid
                )
        )
        val response = runCatching {
            EmbeddedGuardianClient(
                identity.port,
                identity.token,
                connectTimeoutMs = 2_000,
                readTimeoutMs = HANDOFF_REQUEST_TIMEOUT_MS
            ).handoff(request)
        }.getOrElse { error ->
            LogManager.event(
                app,
                "embedded_engine_handoff_failed",
                EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                    mapOf("stage" to "request", "error" to error.toString())
            )
            return HandoffAttempt(false, "handoff_request_failed", oldPing.engineRevision)
        }
        val accepted = runCatching { JSONObject(response).optBoolean("accepted", false) }
            .getOrDefault(false)
        if (!accepted) return HandoffAttempt(false, "handoff_rejected", oldPing.engineRevision)

        var lastError: Throwable? = null
        var verifiedPing: PingHandshake? = null
        var verifiedAttempt = 0
        for (attempt in 1..HANDOFF_VERIFY_ATTEMPTS) {
            if (!EmbeddedGuardianStore.isGenerationActive(app, generation)) {
                return HandoffAttempt(false, "generation_superseded", oldPing.engineRevision)
            }
            Thread.sleep(HANDOFF_VERIFY_DELAY_MS)
            val result = runCatching {
                validatePing(
                    EmbeddedGuardianClient(
                        identity.port,
                        identity.token,
                        connectTimeoutMs = HANDOFF_PING_TIMEOUT_MS,
                        readTimeoutMs = HANDOFF_PING_TIMEOUT_MS
                    ).ping()
                )
            }
            val ping = result.getOrNull()
            if (ping != null && (ping.pid != oldPing.pid || ping.engineRevision != oldPing.engineRevision)) {
                verifiedPing = ping
                verifiedAttempt = attempt
                break
            }
            lastError = result.exceptionOrNull() ?: IllegalStateException("old engine still serving")
        }
        val newPing = verifiedPing
        if (newPing == null) {
            LogManager.event(
                app,
                "embedded_engine_handoff_failed",
                EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                    mapOf(
                        "stage" to "verify",
                        "oldRevision" to oldPing.engineRevision,
                        "error" to lastError.toString()
                    )
            )
            return HandoffAttempt(false, "handoff_verify_timeout", oldPing.engineRevision)
        }

        val configured = runCatching { configure(app, generation) }
        if (configured.isFailure) {
            LogManager.event(
                app,
                "embedded_engine_handoff_failed",
                EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                    mapOf(
                        "stage" to "configure",
                        "oldRevision" to oldPing.engineRevision,
                        "newRevision" to newPing.engineRevision,
                        "error" to configured.exceptionOrNull().toString()
                    )
            )
            return HandoffAttempt(false, "handoff_configure_failed", oldPing.engineRevision, newPing.engineRevision)
        }
        LogManager.event(
            app,
            "embedded_engine_handoff_succeeded",
            EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                mapOf(
                    "oldRevision" to oldPing.engineRevision,
                    "newRevision" to newPing.engineRevision,
                    "oldPid" to oldPing.pid,
                    "newPid" to newPing.pid,
                    "verifyAttempt" to verifiedAttempt
                )
        )
        return HandoffAttempt(true, "hot_handoff", oldPing.engineRevision, newPing.engineRevision)
    }


    private fun retireLegacyEngine(
        identity: EmbeddedGuardianStore.EndpointIdentity,
        oldPid: Int
    ): Boolean {
        // Old servers close their listen socket from inside OP_DESTROY before the reply
        // is necessarily flushed, so transport failure here is not proof of failure.
        runCatching {
            EmbeddedGuardianClient(
                identity.port,
                identity.token,
                connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000
            ).destroy()
        }
        repeat(LEGACY_RETIRE_VERIFY_ATTEMPTS) {
            Thread.sleep(LEGACY_RETIRE_VERIFY_DELAY_MS)
            val ping = runCatching {
                parsePing(
                    EmbeddedGuardianClient(
                        identity.port,
                        identity.token,
                        connectTimeoutMs = HANDOFF_PING_TIMEOUT_MS,
                        readTimeoutMs = HANDOFF_PING_TIMEOUT_MS
                    ).ping(),
                    requireCurrentRevision = false
                )
            }.getOrNull()
            if (ping == null) return true
            // A different PID means the legacy instance is gone. Do not destroy a
            // successor that may already have been started by another recovery path.
            if (oldPid > 0 && ping.pid != oldPid) return true
        }
        return false
    }

    fun reconfigure(context: Context, callback: (Result<String>) -> Unit = {}) {
        val app = context.applicationContext
        execute(app, "reconfigure", callback) { client ->
            client.configure(PrivilegedGuardianController.engineConfigJson(app))
        }
    }

    fun applyBackgroundPolicy(
        context: Context,
        source: String = "explicit_user_repair",
        callback: (Result<String>) -> Unit = {}
    ) {
        val app = context.applicationContext
        val request = JSONObject()
            .put("source", source)
            .toString()
        execute(
            app,
            "background_policy",
            { result ->
                result.onSuccess {
                    EmbeddedBackgroundPolicyStore.recordReport(app, it, source)
                }
                callback(result)
            }
        ) { client -> client.applyBackgroundPolicy(request) }
    }

    internal fun configure(context: Context, generation: Long): String {
        val app = context.applicationContext
        return try {
            check(EmbeddedGuardianStore.isGenerationActive(app, generation)) {
                "embedded setup superseded"
            }
            val identity = EmbeddedGuardianStore.identity(app) ?: error("engine identity missing")
            check(EmbeddedGuardianStore.markConnecting(app, generation, "engine_configure")) {
                "embedded setup superseded"
            }
            val client = EmbeddedGuardianClient(
                identity.port,
                identity.token,
                connectTimeoutMs = 2_500,
                readTimeoutMs = CONFIGURE_READ_TIMEOUT_MS
            )
            val ping = validatePing(client.ping())
            check(EmbeddedGuardianStore.isGenerationActive(app, generation)) {
                "embedded setup superseded"
            }
            val result = client.configure(PrivilegedGuardianController.engineConfigJson(app))
            val status = client.status()
            val live = validateStatus(ping, status)
            check(
                EmbeddedGuardianStore.recordLiveHandshake(
                    app,
                    generation,
                    live.uid,
                    live.status,
                    "engine_configure"
                )
            ) { "embedded setup superseded" }
            EmbeddedBackgroundPolicyStore.recordFromEngineStatus(
                app,
                live.status,
                "engine_configure"
            )
            EmbeddedGuardianNotifier.cancelRebootReminder(app)
            LogManager.event(
                app,
                "embedded_guardian_connected",
                EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), "engine_configure") +
                    mapOf("statusBytes" to status.length)
            )
            result
        } catch (error: Throwable) {
            EmbeddedGuardianStore.markConnectionUnavailable(
                app,
                generation,
                EmbeddedConnectionState.DEAD,
                error.toString(),
                "engine_configure_failed"
            )
            throw error
        }
    }

    private fun execute(
        context: Context,
        operation: String,
        callback: (Result<String>) -> Unit,
        action: (EmbeddedGuardianClient) -> String
    ) {
        val app = context.applicationContext
        executor.execute {
            var generation = -1L
            val result = runCatching {
                val snapshot = EmbeddedGuardianStore.snapshot(app)
                check(snapshot.featureEnabled) { "embedded feature disabled" }
                generation = snapshot.generation
                check(snapshot.liveConnected) { "embedded engine has no live connection" }
                val identity = EmbeddedGuardianStore.identity(app) ?: error("engine identity missing")
                val client = EmbeddedGuardianClient(
                    identity.port,
                    identity.token,
                    connectTimeoutMs = 2_500,
                    readTimeoutMs = OPERATION_READ_TIMEOUT_MS
                )
                validateLiveHandshake(client)
                check(EmbeddedGuardianStore.isGenerationActive(app, generation)) {
                    "embedded operation superseded"
                }
                val value = action(client)
                val live = validateLiveHandshake(client)
                check(
                    EmbeddedGuardianStore.recordLiveHandshake(
                        app,
                        generation,
                        live.uid,
                        live.status,
                        "privileged_operation_$operation"
                    )
                ) { "embedded operation superseded" }
                EmbeddedBackgroundPolicyStore.recordFromEngineStatus(
                    app,
                    live.status,
                    "privileged_operation_$operation"
                )
                value
            }.onFailure { error ->
                if (generation >= 0L) {
                    EmbeddedGuardianStore.identity(app)?.let { identity ->
                        preserveConnectionOrMarkDead(
                            app = app,
                            generation = generation,
                            identity = identity,
                            error = error,
                            source = "privileged_operation_${operation}_failed"
                        )
                    }
                }
                LogManager.event(
                    app,
                    "embedded_guardian_${operation}_failed",
                    mapOf("error" to error.toString())
                )
            }
            callback(result)
        }
    }

    private fun validateLiveHandshake(client: EmbeddedGuardianClient): LiveHandshake {
        val ping = validatePing(client.ping())
        return validateStatus(ping, client.status())
    }

    private fun validatePing(rawPing: String): PingHandshake =
        parsePing(rawPing, requireCurrentRevision = true)

    private fun parsePing(rawPing: String, requireCurrentRevision: Boolean): PingHandshake {
        val ping = JSONObject(rawPing)
        check(ping.optString("engine") == "LuonnotarEmbeddedGuardian") {
            "unexpected embedded engine identity"
        }
        val uid = ping.optInt("uid", -1)
        check(uid == EmbeddedGuardianRuntimeState.SHELL_UID) {
            "embedded handshake rejected uid=$uid"
        }
        val engineRevision = ping.optInt("engineRevision", -1)
        if (requireCurrentRevision) {
            check(engineRevision == EmbeddedGuardianProtocol.ENGINE_REVISION) {
                "embedded engine revision mismatch expected=${EmbeddedGuardianProtocol.ENGINE_REVISION} actual=$engineRevision"
            }
        } else {
            check(engineRevision > 0) { "embedded engine revision missing" }
        }
        return PingHandshake(
            uid = uid,
            engineRevision = engineRevision,
            pid = ping.optInt("pid", -1),
            handoffSupported = ping.optBoolean("handoffSupported", false)
        )
    }

    private fun validateStatus(ping: PingHandshake, rawStatus: String): LiveHandshake {
        val status = JSONObject(rawStatus)
        val statusUid = status.optInt("uid", -1)
        val running = status.optBoolean("running", false)
        val snapshotElapsed = status.optLong("snapshotElapsed", 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        val snapshotAgeMs = EmbeddedEngineStatusFreshnessPolicy.ageMs(
            nowElapsed = nowElapsed,
            snapshotElapsed = snapshotElapsed
        )
        if (!EmbeddedEngineStatusFreshnessPolicy.isFresh(
                nowElapsed = nowElapsed,
                snapshotElapsed = snapshotElapsed
            )
        ) {
            throw EmbeddedEngineStatusStaleException(
                "embedded engine status stale ageMs=$snapshotAgeMs " +
                    "snapshotElapsed=$snapshotElapsed"
            )
        }
        check(EmbeddedGuardianStatePolicy.acceptsLiveHandshake(ping.uid, statusUid, running)) {
            "embedded live status rejected pingUid=${ping.uid} statusUid=$statusUid running=$running"
        }
        return LiveHandshake(statusUid, rawStatus)
    }

    private fun probePing(
        identity: EmbeddedGuardianStore.EndpointIdentity
    ): Result<PingHandshake> {
        var lastFailure: Throwable = IllegalStateException("embedded ping was not attempted")
        repeat(FAST_PING_ATTEMPTS) { attempt ->
            val result = runCatching {
                validatePing(
                    EmbeddedGuardianClient(
                        identity.port,
                        identity.token,
                        connectTimeoutMs = FAST_PING_TIMEOUT_MS,
                        readTimeoutMs = FAST_PING_TIMEOUT_MS
                    ).ping()
                )
            }
            if (result.isSuccess) return result
            lastFailure = result.exceptionOrNull() ?: lastFailure
            if (attempt + 1 < FAST_PING_ATTEMPTS) Thread.sleep(FAST_PING_RETRY_DELAY_MS)
        }
        return Result.failure(lastFailure)
    }

    /**
     * A live socket with a stale status means the server survived while its
     * privileged guardian stopped publishing progress. A ping cannot detect
     * that split-brain state, so explicitly destroy the stale app_process and
     * let the normal auto-repair path start a clean generation.
     */
    private fun terminateStaleEngine(
        app: Context,
        generation: Long,
        identity: EmbeddedGuardianStore.EndpointIdentity,
        error: Throwable,
        source: String
    ): Boolean {
        val destroyResult = runCatching {
            EmbeddedGuardianClient(
                identity.port,
                identity.token,
                connectTimeoutMs = 1_500,
                readTimeoutMs = 5_000
            ).destroy()
        }
        val stopped = destroyResult.isSuccess && runCatching {
            awaitRemoteStop(identity)
        }.getOrDefault(false)
        EmbeddedGuardianStore.markConnectionUnavailable(
            app,
            generation,
            EmbeddedConnectionState.DEAD,
            error.toString(),
            source
        )
        LogManager.event(
            app,
            "embedded_stale_engine_terminated",
            EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                mapOf(
                    "destroyAccepted" to destroyResult.isSuccess,
                    "remoteStopped" to stopped,
                    "destroyError" to destroyResult.exceptionOrNull().toString()
                )
        )
        return stopped
    }

    /** Returns true only when a separate short ping proves the engine is unavailable/incompatible. */
    private fun preserveConnectionOrMarkDead(
        app: Context,
        generation: Long,
        identity: EmbeddedGuardianStore.EndpointIdentity,
        error: Throwable,
        source: String
    ): Boolean {
        val ping = probePing(identity)
        val pingSucceeded = ping.isSuccess
        if (!EmbeddedConnectionFailurePolicy.shouldMarkDead(pingSucceeded)) {
            val livePing = ping.getOrThrow()
            EmbeddedGuardianStore.recordLivePing(
                app,
                generation,
                livePing.uid,
                "operation result unknown: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                source
            )
            LogManager.event(
                app,
                "embedded_operation_result_unknown",
                EmbeddedGuardianStore.eventFields(EmbeddedGuardianStore.snapshot(app), source) +
                    mapOf("error" to error.toString())
            )
            return false
        }
        EmbeddedGuardianStore.markConnectionUnavailable(
            app,
            generation,
            EmbeddedConnectionState.DEAD,
            error.toString(),
            source
        )
        return true
    }

    private fun awaitRemoteStop(identity: EmbeddedGuardianStore.EndpointIdentity): Boolean {
        repeat(10) {
            Thread.sleep(200L)
            val stillAlive = runCatching {
                EmbeddedGuardianClient(
                    identity.port,
                    identity.token,
                    connectTimeoutMs = 300,
                    readTimeoutMs = 500
                ).ping()
            }.isSuccess
            if (!stillAlive) return true
        }
        return false
    }

    private class EmbeddedEngineStatusStaleException(message: String) :
        IllegalStateException(message)

    internal data class HandoffAttempt(
        val success: Boolean,
        val reason: String,
        val oldRevision: Int? = null,
        val newRevision: Int? = null
    )

    private data class PingHandshake(
        val uid: Int,
        val engineRevision: Int,
        val pid: Int,
        val handoffSupported: Boolean
    )
    private data class LiveHandshake(val uid: Int, val status: String)

    private const val FAST_PING_TIMEOUT_MS = 2_000
    private const val FAST_PING_ATTEMPTS = 3
    private const val FAST_PING_RETRY_DELAY_MS = 150L
    private const val STATUS_READ_TIMEOUT_MS = 8_000
    private const val CONFIGURE_READ_TIMEOUT_MS = 30_000
    private const val OPERATION_READ_TIMEOUT_MS = 30_000
    private const val RECOVERY_REQUEST_TIMEOUT_MS = 10_000
    private const val HANDOFF_REQUEST_TIMEOUT_MS = 5_000
    private const val HANDOFF_PING_TIMEOUT_MS = 750
    private const val HANDOFF_VERIFY_ATTEMPTS = 40
    private const val HANDOFF_VERIFY_DELAY_MS = 200L
    private const val LEGACY_RETIRE_VERIFY_ATTEMPTS = 12
    private const val LEGACY_RETIRE_VERIFY_DELAY_MS = 150L
}
