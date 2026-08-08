package com.yubegreen.luonnotar.service

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.yubegreen.luonnotar.BuildConfig
import com.yubegreen.luonnotar.experiment.ExperimentSessionOperationResult
import com.yubegreen.luonnotar.experiment.ExperimentSessionRecorder
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianClient
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianManager
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianProtocol
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianStore
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import org.json.JSONObject

/**
 * Synchronous shell-only runtime configuration bridge.
 *
 * Some OEM builds can discard or short-circuit explicit adb broadcasts aimed
 * at an app process. A ContentProvider call is synchronous and does not depend
 * on the broadcast queue, so adb can query and mutate keeper/runtime state even
 * while the device remains locked.
 *
 * The manifest protects this provider with android.permission.DUMP. The UID
 * check below is defense in depth and only permits this app, root, system, or
 * adb shell.
 */
class AdbRuntimeConfigProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val providerContext = context ?: return false
        LuonnotarPreferences.initializeKeeperBoot(providerContext)
        LuonnotarPreferences.initializeKeeperProcess(providerContext)
        GuardianProfilePolicy.ensureDefaults(
            providerContext,
            LuonnotarPreferences.deviceProtected(providerContext)
        )
        return true
    }

    @Synchronized
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!callerAllowed()) {
            return resultBundle(
                ok = false,
                reason = "caller_not_allowed",
                values = emptyMap()
            )
        }
        val appContext = context?.applicationContext
            ?: return resultBundle(
                ok = false,
                reason = "context_unavailable",
                values = emptyMap()
            )
        return when (method) {
            METHOD_STATUS -> status(appContext)
            METHOD_ENGINE_STATUS -> engineStatus(appContext)
            METHOD_ENGINE_RESTART -> engineRestart(appContext)
            METHOD_SELF_UPDATE -> selfUpdate(appContext, extras ?: Bundle.EMPTY)
            METHOD_SELF_UPDATE_STATUS -> selfUpdateStatus(appContext)
            METHOD_SET -> setRuntimeConfig(appContext, extras ?: Bundle.EMPTY)
            METHOD_PROBE -> probeNow(appContext, extras ?: Bundle.EMPTY)
            METHOD_EXPERIMENT_START -> experimentStart(
                appContext,
                extras ?: Bundle.EMPTY
            )
            METHOD_EXPERIMENT_MARK -> experimentMark(
                appContext,
                extras ?: Bundle.EMPTY
            )
            METHOD_EXPERIMENT_STOP -> experimentStop(appContext)
            else -> resultBundle(
                ok = false,
                reason = "unsupported_method",
                values = emptyMap()
            )
        }
    }

    private fun status(context: android.content.Context): Bundle {
        val status = GuardianStatusClient.status(context)
            ?: return resultBundle(
                ok = false,
                reason = "status_unavailable",
                values = emptyMap()
            )
        val profile = readProfile(status)
            ?: return resultBundle(
                ok = false,
                reason = "invalid_persisted_profile",
                values = emptyMap()
            )
        return resultBundle(
            ok = true,
            reason = "",
            values = statusValues(
                status = status,
                profile = profile,
                changed = false,
                serviceReloadRequested = false
            )
        )
    }

    private fun setRuntimeConfig(
        context: android.content.Context,
        config: Bundle
    ): Bundle {
        val before = GuardianStatusClient.status(context)
            ?: return resultBundle(
                ok = false,
                reason = "status_unavailable",
                values = emptyMap()
            )
        val currentProfile = readProfile(before)
            ?: return resultBundle(
                ok = false,
                reason = "invalid_persisted_profile",
                values = emptyMap()
            )
        val requestedProfile = if (config.containsKey(EXTRA_PROFILE)) {
            runCatching {
                GuardianRuntimeProfile.valueOf(
                    config.getString(EXTRA_PROFILE).orEmpty()
                )
            }.getOrNull()
        } else {
            currentProfile
        }
        if (requestedProfile == null) {
            return resultBundle(
                ok = false,
                reason = "invalid_profile",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val currentLevel = before.getInt(
            LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
            0
        ).coerceIn(0, 4)
        val requestedLevel = if (config.containsKey(EXTRA_LAB_LEVEL)) {
            config.getInt(EXTRA_LAB_LEVEL, -1)
        } else {
            currentLevel
        }
        if (requestedLevel !in 0..4) {
            return resultBundle(
                ok = false,
                reason = "invalid_lab_level",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val desiredExperiments = GuardianProfilePolicy.sanitizeExperiments(
            requestedProfile,
            GuardianProfilePolicy.experimentKeys.associateWith { key ->
                before.getBoolean(key, false)
            }
        ).toMutableMap()
        var mutationRequested =
            config.containsKey(EXTRA_PROFILE) ||
                config.containsKey(EXTRA_LAB_LEVEL)
        GuardianProfilePolicy.adbMutableExperimentKeys.forEach { key ->
            if (config.containsKey(key)) {
                desiredExperiments[key] = config.getBoolean(
                    key,
                    desiredExperiments[key] == true
                )
                mutationRequested = true
            }
        }
        if (!mutationRequested) {
            return resultBundle(
                ok = false,
                reason = "no_mutations_requested",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val validationFailure = GuardianProfilePolicy.runtimeConfigError(
            requestedProfile,
            desiredExperiments
        )
        if (validationFailure != null) {
            return resultBundle(
                ok = false,
                reason = validationFailure,
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val changed =
            requestedProfile != currentProfile ||
                requestedLevel != currentLevel ||
                GuardianProfilePolicy.experimentKeys.any { key ->
                    desiredExperiments[key] != before.getBoolean(key, false)
                }
        val committed = GuardianStatusClient.setRuntimeConfig(
            context,
            requestedProfile,
            requestedLevel,
            desiredExperiments
        )
        if (!committed) {
            return resultBundle(
                ok = false,
                reason = "commit_failed",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = false,
                    serviceReloadRequested = false
                )
            )
        }

        val after = GuardianStatusClient.status(context)
        val verified = after != null &&
            after.getString(
                LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                ""
            ) == requestedProfile.name &&
            after.getInt(
                LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
                -1
            ) == requestedLevel &&
            GuardianProfilePolicy.experimentKeys.all { key ->
                after.getBoolean(key, false) == desiredExperiments[key]
            }
        if (!verified || after == null) {
            return resultBundle(
                ok = false,
                reason = "readback_verification_failed",
                values = statusValues(
                    before,
                    currentProfile,
                    changed = changed,
                    serviceReloadRequested = false
                )
            )
        }

        val guardianActive =
            after.getBoolean(LuonnotarPreferences.KEY_ENABLED, false) &&
                !after.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        val reloadResult = if (changed && guardianActive) {
            FcmGuardianService.requestInProcessRuntimeConfigReload(
                "adb_provider_runtime_config_changed"
            )
        } else {
            FcmGuardianService.Companion.InProcessReloadResult.DEFERRED
        }
        val serviceReloadRequested =
            reloadResult == FcmGuardianService.Companion.InProcessReloadResult.APPLIED
        val serviceReloadDeferred =
            changed && guardianActive &&
                reloadResult == FcmGuardianService.Companion.InProcessReloadResult.DEFERRED
        val ok =
            reloadResult != FcmGuardianService.Companion.InProcessReloadResult.FAILED
        val reason = if (ok) "" else "service_reload_failed"
        LogManager.event(
            context,
            "adb_runtime_config_provider_applied",
            mapOf(
                "profile" to requestedProfile.name,
                "labLevel" to requestedLevel,
                "changed" to changed,
                "guardianActive" to guardianActive,
                "serviceReloadRequested" to serviceReloadRequested,
                "serviceReloadDeferred" to serviceReloadDeferred,
                "reloadTransport" to "in_process",
                "ok" to ok,
                "reason" to reason
            )
        )
        return resultBundle(
            ok = ok,
            reason = reason,
            values = statusValues(
                after,
                requestedProfile,
                changed,
                serviceReloadRequested,
                serviceReloadDeferred
            )
        )
    }

    private fun probeNow(
        context: android.content.Context,
        extras: Bundle
    ): Bundle {
        val requested = extras.getString(EXTRA_PROBE).orEmpty()
        val result = FcmGuardianService.requestInProcessProbe(requested)
        val status = GuardianStatusClient.status(context)
        val profile = status?.let(::readProfile)
        val ok = result == FcmGuardianService.Companion.InProcessProbeResult.DISPATCHED
        val reason = if (ok) "" else result.name.lowercase()
        val values = if (status != null && profile != null) {
            statusValues(
                status,
                profile,
                changed = false,
                serviceReloadRequested = false
            ).toMutableMap().apply {
                put("probe", requested.lowercase())
                put("probe_dispatched", ok)
                put("probe_result", result.name)
            }
        } else {
            linkedMapOf(
                "schema" to 2,
                "versionName" to BuildConfig.VERSION_NAME,
                "transport" to "content_provider",
                "probe" to requested.lowercase(),
                "probe_dispatched" to ok,
                "probe_result" to result.name
            )
        }
        LogManager.event(
            context,
            "adb_runtime_probe_requested",
            mapOf(
                "probe" to requested,
                "result" to result.name
            )
        )
        return resultBundle(ok, reason, values)
    }

    private fun experimentStart(
        context: android.content.Context,
        extras: Bundle
    ): Bundle = experimentResult(
        context,
        ExperimentSessionRecorder.start(
            context,
            LuonnotarPreferences.deviceProtected(context),
            extras.getString(EXTRA_SESSION_NAME),
            SOURCE_ADB
        )
    )

    private fun experimentMark(
        context: android.content.Context,
        extras: Bundle
    ): Bundle = experimentResult(
        context,
        ExperimentSessionRecorder.mark(
            context,
            LuonnotarPreferences.deviceProtected(context),
            extras.getString(EXTRA_MARK_LABEL),
            SOURCE_ADB
        )
    )

    private fun experimentStop(
        context: android.content.Context
    ): Bundle = experimentResult(
        context,
        ExperimentSessionRecorder.stop(
            context,
            LuonnotarPreferences.deviceProtected(context),
            SOURCE_ADB
        )
    )

    private fun experimentResult(
        context: android.content.Context,
        operation: ExperimentSessionOperationResult
    ): Bundle {
        val status = GuardianStatusClient.status(context)
        val profile = status?.let(::readProfile)
        val values: MutableMap<String, Any> = if (
            status != null && profile != null
        ) {
            statusValues(
                status = status,
                profile = profile,
                changed = false,
                serviceReloadRequested = false
            ).toMutableMap()
        } else {
            linkedMapOf<String, Any>(
                "schema" to 4,
                "versionName" to BuildConfig.VERSION_NAME,
                "transport" to "content_provider"
            )
        }
        values.putAll(operation.values)
        return resultBundle(operation.ok, operation.reason, values)
    }

    private fun readProfile(status: Bundle): GuardianRuntimeProfile? =
        runCatching {
            GuardianRuntimeProfile.valueOf(
                status.getString(
                    LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
                    GuardianProfilePolicy.defaultProfile(
                        GuardianProfilePolicy.isVivoFamily(
                            android.os.Build.MANUFACTURER,
                            android.os.Build.BRAND
                        )
                    ).name
                ).orEmpty()
            )
        }.getOrNull()

    private fun statusValues(
        status: Bundle,
        profile: GuardianRuntimeProfile,
        changed: Boolean,
        serviceReloadRequested: Boolean,
        serviceReloadDeferred: Boolean = false
    ): Map<String, Any> = linkedMapOf<String, Any>().apply {
        put("schema", 4)
        put("versionName", BuildConfig.VERSION_NAME)
        put("transport", "content_provider")
        put("profile", profile.name)
        put(
            "lab_level",
            status.getInt(LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL, 0)
        )
        put(
            "guardian_enabled",
            status.getBoolean(LuonnotarPreferences.KEY_ENABLED, false)
        )
        put(
            "guardian_paused",
            status.getBoolean(LuonnotarPreferences.KEY_PAUSED, false)
        )
        put("changed", changed)
        put("service_reload_requested", serviceReloadRequested)
        put("service_reload_deferred", serviceReloadDeferred)
        put("service_reload_transport", "in_process")
        val sessionActive = status.getBoolean(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
            false
        )
        val sessionStartedElapsed = status.getLong(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_ELAPSED,
            0L
        )
        put("experiment_session_active", sessionActive)
        put(
            "experiment_session_id",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ID,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_name",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_NAME,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_source",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_SOURCE,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_boot_id",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_BOOT_ID,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_started_wall",
            status.getLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_WALL,
                0L
            )
        )
        put(
            "experiment_session_age_ms",
            if (
                sessionActive &&
                sessionStartedElapsed > 0L &&
                sessionStartedElapsed <= SystemClock.elapsedRealtime()
            ) {
                SystemClock.elapsedRealtime() - sessionStartedElapsed
            } else {
                -1L
            }
        )
        put(
            "experiment_session_mark_count",
            status.getInt(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_MARK_COUNT,
                0
            )
        )
        put(
            "experiment_session_last_mark",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_last_mark_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_EXPERIMENT_SESSION_LAST_MARK_ELAPSED,
                    0L
                )
            )
        )
        put(
            "experiment_session_last_event",
            status.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_EVENT,
                ""
            ).orEmpty()
        )
        put(
            "experiment_session_last_duration_ms",
            status.getLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_DURATION_MS,
                -1L
            )
        )
        GuardianProfilePolicy.adbMutableExperimentKeys.forEach { key ->
            put(key, status.getBoolean(key, false))
        }
        put(
            "service_generation",
            status.getLong(LuonnotarPreferences.KEY_SERVICE_GENERATION, 0L)
        )
        put(
            "keeper_pid",
            status.getInt(LuonnotarPreferences.KEY_KEEPER_PROCESS_PID, 0)
        )
        put(
            "vpn_network_handle",
            status.getLong(LuonnotarPreferences.KEY_NETWORK_HANDLE, -1L)
        )
        put(
            "gms_binder_anchor_state",
            status.getString(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_STATE,
                ""
            ).orEmpty()
        )
        put(
            "gms_binder_anchor_pid",
            status.getInt(LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_PID, 0)
        )
        put(
            "notification_listener_connected",
            status.getBoolean(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_CONNECTED,
                false
            )
        )
        put(
            "notification_listener_pid",
            status.getInt(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_PID,
                0
            )
        )
        val listenerHeartbeat = status.getLong(
            LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED,
            0L
        )
        put(
            "notification_listener_heartbeat_age_ms",
            if (listenerHeartbeat > 0L) {
                (SystemClock.elapsedRealtime() - listenerHeartbeat)
                    .coerceAtLeast(0L)
            } else {
                -1L
            }
        )
        put(
            "notification_listener_rebind_count",
            status.getInt(
                LuonnotarPreferences.KEY_NOTIFICATION_LISTENER_REBIND_COUNT,
                0
            )
        )
        put(
            "vpn_session_generation",
            status.getLong(
                LuonnotarPreferences.KEY_VPN_SESSION_GENERATION,
                0L
            )
        )
        put(
            "gms_binder_anchor_session_generation",
            status.getLong(
                LuonnotarPreferences.KEY_GMS_BINDER_ANCHOR_SESSION_GENERATION,
                0L
            )
        )
        put(
            "persistent_network_lease_state",
            status.getString(
                LuonnotarPreferences.KEY_PERSISTENT_NETWORK_LEASE_STATE,
                "STOPPED"
            ).orEmpty()
        )
        put(
            "persistent_network_lease_handle",
            status.getLong(
                LuonnotarPreferences.KEY_PERSISTENT_NETWORK_LEASE_HANDLE,
                -1L
            )
        )
        put(
            "persistent_network_lease_last_event_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_NETWORK_LEASE_LAST_EVENT_ELAPSED,
                    0L
                )
            )
        )
        put(
            "persistent_heartbeat_socket_state",
            status.getString(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_STATE,
                "STOPPED"
            ).orEmpty()
        )
        put(
            "persistent_heartbeat_socket_handle",
            status.getLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_HANDLE,
                -1L
            )
        )
        put(
            "persistent_heartbeat_socket_last_event_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_EVENT_ELAPSED,
                    0L
                )
            )
        )
        put(
            "persistent_heartbeat_socket_reason",
            status.getString(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_REASON,
                ""
            ).orEmpty()
        )
        put(
            "persistent_heartbeat_socket_total_connect_count",
            status.getInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_CONNECT_COUNT,
                0
            )
        )
        put(
            "persistent_heartbeat_socket_session_connect_count",
            status.getInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_CONNECT_COUNT,
                0
            )
        )
        put(
            "persistent_heartbeat_socket_connection_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_HEARTBEAT_SOCKET_CONNECTION_STARTED_ELAPSED,
                    0L
                )
            )
        )
        put(
            "persistent_heartbeat_socket_last_heartbeat_age_ms",
            elapsedAge(
                status.getLong(
                    LuonnotarPreferences
                        .KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_HEARTBEAT_ELAPSED,
                    0L
                )
            )
        )
        put(
            "persistent_heartbeat_socket_total_heartbeat_count",
            status.getLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_HEARTBEAT_COUNT,
                0L
            )
        )
        put(
            "persistent_heartbeat_socket_session_heartbeat_count",
            status.getInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_HEARTBEAT_COUNT,
                0
            )
        )
        put(
            "persistent_heartbeat_socket_consecutive_failures",
            status.getInt(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_CONSECUTIVE_FAILURES,
                0
            )
        )
        put(
            "persistent_heartbeat_socket_backoff_ms",
            status.getLong(
                LuonnotarPreferences
                    .KEY_PERSISTENT_HEARTBEAT_SOCKET_BACKOFF_MS,
                0L
            )
        )
    }

    private fun elapsedAge(eventElapsed: Long): Long =
        if (eventElapsed > 0L) {
            (SystemClock.elapsedRealtime() - eventElapsed).coerceAtLeast(0L)
        } else {
            -1L
        }

    /**
     * Shell engine lifecycle status over the synchronous provider transport.
     *
     * Keep `ok=true` when the query itself succeeds even if the engine is down;
     * callers need to distinguish a healthy control plane from an unhealthy
     * engine. `engineReachable` / `revisionCurrent` carry the runtime state.
     */
    private fun engineStatus(context: android.content.Context): Bundle {
        val probe = probeEngine(context)
        return engineResultBundle(
            ok = true,
            reason = "",
            values = probe.asValues()
        )
    }

    /**
     * Dispatch a controlled restart while preserving the persisted Kadb host
     * identity. The restart itself is asynchronous: r260+ engines hot-handoff
     * over loopback, while older/unreachable engines use the existing ADB
     * fallback. A second `engine_status` call verifies convergence.
     */
    private fun engineRestart(context: android.content.Context): Bundle {
        val before = probeEngine(context)
        if (!before.featureEnabled) {
            return engineResultBundle(
                ok = false,
                reason = "feature_disabled",
                values = before.asValues() + mapOf("dispatched" to false)
            )
        }
        val dispatch = runCatching {
            EmbeddedGuardianManager.restartEngine(context, "adb_provider_restart")
        }
        if (dispatch.isFailure) {
            val error = dispatch.exceptionOrNull()
            LogManager.event(
                context,
                "adb_provider_engine_restart_failed",
                mapOf(
                    "oldPid" to before.pid,
                    "oldRevision" to before.actualRevision,
                    "expectedRevision" to EmbeddedGuardianProtocol.ENGINE_REVISION,
                    "error" to error.toString()
                )
            )
            return engineResultBundle(
                ok = false,
                reason = "restart_dispatch_failed",
                values = before.asValues() + mapOf(
                    "dispatched" to false,
                    "error" to error.toString().take(400)
                )
            )
        }
        LogManager.event(
            context,
            "adb_provider_engine_restart_requested",
            mapOf(
                "oldPid" to before.pid,
                "oldRevision" to before.actualRevision,
                "expectedRevision" to EmbeddedGuardianProtocol.ENGINE_REVISION,
                "generation" to before.generation
            )
        )
        return engineResultBundle(
            ok = true,
            reason = "",
            values = before.asValues() + mapOf(
                "dispatched" to true,
                "restartSource" to "adb_provider_restart"
            )
        )
    }


    private fun selfUpdate(
        context: android.content.Context,
        extras: Bundle
    ): Bundle {
        val apkPath = extras.getString(EXTRA_APK_PATH).orEmpty().trim()
        if (apkPath.isBlank()) {
            return engineResultBundle(
                ok = false,
                reason = "apk_path_required",
                values = mapOf("dispatched" to false)
            )
        }
        val identity = EmbeddedGuardianStore.identity(context)
            ?: return engineResultBundle(
                ok = false,
                reason = "identity_missing",
                values = mapOf("dispatched" to false)
            )
        val raw = runCatching {
            EmbeddedGuardianClient(
                identity.port,
                identity.token,
                connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000
            ).installSelfUpdate(
                JSONObject().put("apkPath", apkPath).toString()
            )
        }.getOrElse { error ->
            LogManager.event(
                context,
                "self_update_dispatch_failed",
                mapOf("error" to error.toString())
            )
            return engineResultBundle(
                ok = false,
                reason = "engine_rpc_failed",
                values = mapOf(
                    "dispatched" to false,
                    "error" to error.toString().take(400)
                )
            )
        }
        val response = runCatching { JSONObject(raw) }.getOrNull()
            ?: return engineResultBundle(
                ok = false,
                reason = "invalid_engine_response",
                values = mapOf("dispatched" to false)
            )
        val accepted = response.optBoolean("accepted", false)
        LogManager.event(
            context,
            "self_update_request",
            mapOf(
                "transport" to "content_provider",
                "accepted" to accepted,
                "apkName" to java.io.File(apkPath).name
            )
        )
        return engineResultBundle(
            ok = accepted,
            reason = if (accepted) "" else response.optString("reason", "rejected"),
            values = linkedMapOf<String, Any>(
                "dispatched" to accepted,
                "state" to response.optString("state", "unknown"),
                "engineRevision" to EmbeddedGuardianProtocol.ENGINE_REVISION,
                "apkName" to java.io.File(apkPath).name
            )
        )
    }

    private fun selfUpdateStatus(context: android.content.Context): Bundle {
        val identity = EmbeddedGuardianStore.identity(context)
            ?: return engineResultBundle(
                ok = false,
                reason = "identity_missing",
                values = emptyMap()
            )
        val raw = runCatching {
            EmbeddedGuardianClient(
                identity.port,
                identity.token,
                connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000
            ).selfUpdateStatus()
        }.getOrElse { error ->
            return engineResultBundle(
                ok = false,
                reason = "engine_rpc_failed",
                values = mapOf("error" to error.toString().take(400))
            )
        }
        val response = runCatching { JSONObject(raw) }.getOrNull()
            ?: return engineResultBundle(
                ok = false,
                reason = "invalid_engine_response",
                values = emptyMap()
            )
        val values = linkedMapOf<String, Any>(
            "state" to response.optString("state", "unknown"),
            "running" to response.optBoolean("running", false),
            "resultOk" to response.optBoolean("ok", false),
            "code" to response.optString("code", ""),
            "message" to response.optString("message", "").take(400),
            "sessionId" to response.optInt("sessionId", -1),
            "packageName" to response.optString("packageName", ""),
            "versionCode" to response.optLong("versionCode", -1L),
            "apkSize" to response.optLong("apkSize", -1L),
            "durationMs" to response.optLong("durationMs", -1L),
            "permissionApprovalAttempt" to response.optInt("permissionApprovalAttempt", 0),
            "permissionApprovalElapsed" to response.optLong("permissionApprovalElapsed", -1L),
            "callbackCount" to response.optInt("callbackCount", 0),
            "callbackTraceFile" to response.optString("callbackTraceFile", "")
        )
        return engineResultBundle(ok = true, reason = "", values = values)
    }

    private fun probeEngine(context: android.content.Context): EngineRuntimeProbe {
        val snapshot = EmbeddedGuardianStore.snapshot(context)
        val identity = EmbeddedGuardianStore.identity(context)
        if (identity == null) {
            return EngineRuntimeProbe(
                featureEnabled = snapshot.featureEnabled,
                paired = snapshot.paired,
                storeConnected = snapshot.liveConnected,
                generation = snapshot.generation,
                engineReachable = false,
                engineReason = "identity_missing"
            )
        }
        val rawPing = runCatching {
            EmbeddedGuardianClient(
                identity.port,
                identity.token,
                connectTimeoutMs = 800,
                readTimeoutMs = 1_200
            ).ping()
        }.getOrElse {
            return EngineRuntimeProbe(
                featureEnabled = snapshot.featureEnabled,
                paired = snapshot.paired,
                storeConnected = snapshot.liveConnected,
                generation = snapshot.generation,
                engineReachable = false,
                engineReason = "engine_unreachable"
            )
        }
        val ping = runCatching { JSONObject(rawPing) }.getOrNull()
            ?: return EngineRuntimeProbe(
                featureEnabled = snapshot.featureEnabled,
                paired = snapshot.paired,
                storeConnected = snapshot.liveConnected,
                generation = snapshot.generation,
                engineReachable = false,
                engineReason = "invalid_ping_json"
            )
        val trusted =
            ping.optString("engine") == "LuonnotarEmbeddedGuardian" &&
                ping.optInt("uid", -1) == Process.SHELL_UID
        if (!trusted) {
            return EngineRuntimeProbe(
                featureEnabled = snapshot.featureEnabled,
                paired = snapshot.paired,
                storeConnected = snapshot.liveConnected,
                generation = snapshot.generation,
                engineReachable = false,
                engineReason = "unexpected_engine_identity"
            )
        }
        return EngineRuntimeProbe(
            featureEnabled = snapshot.featureEnabled,
            paired = snapshot.paired,
            storeConnected = snapshot.liveConnected,
            generation = snapshot.generation,
            engineReachable = true,
            actualRevision = ping.optInt("engineRevision", -1),
            pid = ping.optInt("pid", -1),
            handoffSupported = ping.optBoolean("handoffSupported", false),
            engineReason = ""
        )
    }

    private fun engineResultBundle(
        ok: Boolean,
        reason: String,
        values: Map<String, Any>
    ): Bundle {
        val bundle = resultBundle(ok, reason, values)
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> bundle.putBoolean(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is String -> bundle.putString(key, value)
                else -> bundle.putString(key, value.toString())
            }
        }
        return bundle
    }

    private fun resultBundle(
        ok: Boolean,
        reason: String,
        values: Map<String, Any>
    ): Bundle {
        val wireValues = linkedMapOf<String, Any>(
            "ok" to ok,
            "reason" to reason
        ).apply { putAll(values) }
        val wire = wireValues.entries.joinToString(";") { (key, value) ->
            "$key=$value"
        }
        return Bundle().apply {
            putBoolean(RESULT_OK, ok)
            putString(RESULT_REASON, reason)
            putString(RESULT_WIRE, wire)
        }
    }

    private fun callerAllowed(): Boolean = AdbRuntimeCallerPolicy.isAllowed(
        callerUid = Binder.getCallingUid(),
        appUid = Process.myUid(),
        rootUid = Process.ROOT_UID,
        systemUid = Process.SYSTEM_UID,
        shellUid = Process.SHELL_UID
    )

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private data class EngineRuntimeProbe(
        val featureEnabled: Boolean,
        val paired: Boolean,
        val storeConnected: Boolean,
        val generation: Long,
        val engineReachable: Boolean,
        val actualRevision: Int = -1,
        val pid: Int = -1,
        val handoffSupported: Boolean = false,
        val engineReason: String = ""
    ) {
        fun asValues(): Map<String, Any> = linkedMapOf(
            "transport" to "content_provider",
            "featureEnabled" to featureEnabled,
            "paired" to paired,
            "storeConnected" to storeConnected,
            "engineReachable" to engineReachable,
            "actualRevision" to actualRevision,
            "expectedRevision" to EmbeddedGuardianProtocol.ENGINE_REVISION,
            "revisionCurrent" to (
                engineReachable && actualRevision == EmbeddedGuardianProtocol.ENGINE_REVISION
            ),
            "pid" to pid,
            "handoffSupported" to handoffSupported,
            "generation" to generation,
            "engineReason" to engineReason
        )
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".adb_runtime_config"
        const val METHOD_STATUS = "status"
        const val METHOD_ENGINE_STATUS = "engine_status"
        const val METHOD_ENGINE_RESTART = "engine_restart"
        const val METHOD_SELF_UPDATE = "self_update"
        const val METHOD_SELF_UPDATE_STATUS = "self_update_status"
        const val METHOD_SET = "set"
        const val METHOD_PROBE = "probe"
        const val METHOD_EXPERIMENT_START = "experiment_start"
        const val METHOD_EXPERIMENT_MARK = "experiment_mark"
        const val METHOD_EXPERIMENT_STOP = "experiment_stop"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_SESSION_NAME = "session_name"
        const val EXTRA_MARK_LABEL = "mark_label"
        const val EXTRA_PROBE = "probe"
        const val EXTRA_APK_PATH = "apk_path"
        const val EXTRA_LAB_LEVEL = "lab_level"
        const val RESULT_OK = "ok"
        const val RESULT_REASON = "reason"
        const val RESULT_WIRE = "wire"
        const val SOURCE_ADB = "adb"
    }
}
