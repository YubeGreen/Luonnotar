package com.yubegreen.luonnotar.privileged.embedded

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import com.yubegreen.luonnotar.util.LogManager
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom

object EmbeddedGuardianStore {
    private const val PREFS = "luonnotar_embedded_guardian"
    private const val STATE_SCHEMA = 2
    private const val KEY_STATE_SCHEMA = "runtime_state_schema"
    private const val KEY_RUNTIME_OWNER = "runtime_owner"
    private const val KEY_RUNTIME_BOOT_ID = "runtime_boot_id"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SETUP_STATE = "setup_state"
    private const val KEY_CONNECTION_STATE = "connection_state"
    private const val KEY_REPORTED_UID = "reported_uid"
    private const val KEY_GENERATION = "generation"
    private const val KEY_TOKEN = "token"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_PAIRING_PORT = "pairing_port"
    private const val KEY_CONNECT_PORT = "connect_port"
    private const val KEY_PAIRED = "paired"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_STATUS = "last_status"
    private const val KEY_UPDATED_ELAPSED = "updated_elapsed"
    private const val KEY_REBOOT_REMINDER_PENDING = "reboot_reminder_pending"
    private const val KEY_REBOOT_REMINDER_SOURCE = "reboot_reminder_source"
    private const val KEY_REBOOT_REMINDER_BOOT_ACTION = "reboot_reminder_boot_action"
    private const val KEY_REBOOT_REMINDER_CREATED_WALL = "reboot_reminder_created_wall"

    // Removed in schema 2. Retained only so an in-place migration can clear them.
    private const val LEGACY_KEY_STATE = "state"
    private const val LEGACY_KEY_CONNECTED = "connected"
    private const val LEGACY_KEY_RUNNING = "running"
    private const val LEGACY_KEY_BINDER_ALIVE = "binder_alive"
    private const val LEGACY_KEY_LAST_UID = "last_uid"
    private const val LEGACY_KEY_LAST_KNOWN_STATE = "last_known_state"
    private val PROCESS_BOOT_ID = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unavailable")
    /**
     * Runtime ownership must be process-neutral. The embedded control plane is
     * intentionally multi-process (:keeper provider + default-process UI/setup
     * service), while the shell engine itself is expected to outlive either app
     * process. A random per-process owner made every cross-process read look like
     * a stale-runtime takeover and could silently invalidate the generation that
     * had just been dispatched to EmbeddedAdbService.
     *
     * Scope runtime validity to the Android boot instead. A real reboot still
     * invalidates transient app-side connection state; an app/keeper process
     * restart no longer destroys a still-valid shell-engine generation.
     */
    private val RUNTIME_OWNER = "boot:$PROCESS_BOOT_ID"

    data class Snapshot(
        val featureEnabled: Boolean,
        val setupState: EmbeddedSetupState,
        val connectionState: EmbeddedConnectionState,
        val reportedUid: Int,
        val generation: Long,
        val serverPort: Int,
        val pairingPort: Int,
        val connectPort: Int,
        val paired: Boolean,
        val lastError: String,
        val lastStatus: String,
        val updatedElapsed: Long
    ) {
        val runtime: EmbeddedGuardianRuntimeState
            get() = EmbeddedGuardianRuntimeState(
                featureEnabled = featureEnabled,
                setupState = setupState,
                connectionState = connectionState,
                reportedUid = reportedUid,
                generation = generation
            )
        val liveConnected: Boolean get() = runtime.liveConnected
        val binderAlive: Boolean get() = liveConnected
    }

    data class RebootReminder(
        val pending: Boolean,
        val source: String,
        val bootAction: String,
        val createdWall: Long
    )

    data class SetupSession(
        val generation: Long,
        val identity: EndpointIdentity
    )

    data class DisableSession(
        val generation: Long,
        val previous: Snapshot,
        val previousIdentity: EndpointIdentity?
    )

    @Synchronized
    fun snapshot(context: Context): Snapshot {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        return snapshotFrom(prefs)
    }

    @Synchronized
    fun beginUserSetup(context: Context, source: String): SetupSession {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        val existingIdentity = identityFrom(prefs)
        if (
            existingIdentity != null &&
            EmbeddedSetupGenerationPolicy.shouldReuseGeneration(
                featureEnabled = before.featureEnabled,
                setupState = before.setupState
            )
        ) {
            prefs.edit()
                .putString(KEY_LAST_ERROR, "")
                .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
                .commit()
            logState(
                context,
                "embedded_setup_generation_reused",
                source,
                snapshotFrom(prefs)
            )
            return SetupSession(before.generation, existingIdentity)
        }
        val token = existingIdentity?.token ?: newToken()
        val port = existingIdentity?.port ?: findPort()
        val generation = before.generation + 1L
        check(
            prefs.edit()
                .putBoolean(KEY_ENABLED, true)
                .putString(KEY_TOKEN, token)
                .putInt(KEY_SERVER_PORT, port)
                .putString(KEY_SETUP_STATE, EmbeddedSetupState.DISCOVERING.name)
                .putString(KEY_CONNECTION_STATE, EmbeddedConnectionState.DISCONNECTED.name)
                .putInt(KEY_REPORTED_UID, -1)
                .putLong(KEY_GENERATION, generation)
                .putString(KEY_LAST_ERROR, "")
                .remove(KEY_LAST_STATUS)
                .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
                .commit()
        ) { "failed to persist embedded feature enable" }
        val after = snapshotFrom(prefs)
        if (!before.featureEnabled) {
            logState(context, "embedded_feature_enabled_changed", source, after)
        }
        if (before.connectionState != after.connectionState || before.reportedUid != after.reportedUid) {
            logState(context, "embedded_connection_state_changed", source, after)
        }
        return SetupSession(generation, EndpointIdentity(port, token))
    }

    @Synchronized
    fun disableFeature(context: Context, source: String): DisableSession {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        val identity = identityFrom(prefs)
        val disabled = EmbeddedGuardianStatePolicy.disabledState(before.runtime)
        val generation = disabled.generation
        check(
            prefs.edit()
                .putBoolean(KEY_ENABLED, disabled.featureEnabled)
                .putString(KEY_SETUP_STATE, disabled.setupState.name)
                .putString(KEY_CONNECTION_STATE, disabled.connectionState.name)
                .putInt(KEY_REPORTED_UID, disabled.reportedUid)
                .putLong(KEY_GENERATION, generation)
                .putInt(KEY_PAIRING_PORT, 0)
                .putInt(KEY_CONNECT_PORT, 0)
                .remove(KEY_TOKEN)
                .remove(KEY_SERVER_PORT)
                .remove(KEY_PAIRED)
                .remove(KEY_LAST_ERROR)
                .remove(KEY_LAST_STATUS)
                .remove(KEY_REBOOT_REMINDER_PENDING)
                .remove(KEY_REBOOT_REMINDER_SOURCE)
                .remove(KEY_REBOOT_REMINDER_BOOT_ACTION)
                .remove(KEY_REBOOT_REMINDER_CREATED_WALL)
                .remove(LEGACY_KEY_STATE)
                .remove(LEGACY_KEY_CONNECTED)
                .remove(LEGACY_KEY_RUNNING)
                .remove(LEGACY_KEY_BINDER_ALIVE)
                .remove(LEGACY_KEY_LAST_UID)
                .remove(LEGACY_KEY_LAST_KNOWN_STATE)
                .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
                .commit()
        ) { "failed to persist embedded feature disable" }
        val after = snapshotFrom(prefs)
        if (before.featureEnabled) {
            logState(context, "embedded_feature_enabled_changed", source, after)
        }
        if (before.connectionState != after.connectionState || before.reportedUid != after.reportedUid) {
            logState(context, "embedded_connection_state_changed", source, after)
        }
        return DisableSession(generation, before, identity)
    }

    @Synchronized
    fun identity(context: Context): EndpointIdentity? {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        return identityFrom(prefs)
    }

    @Synchronized
    fun updateSetupState(
        context: Context,
        generation: Long,
        setupState: EmbeddedSetupState,
        error: String = "",
        source: String
    ): Boolean {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val current = snapshotFrom(prefs)
        if (!accepts(current, generation)) return false
        if (current.liveConnected && setupState != EmbeddedSetupState.IDLE) return false
        return prefs.edit()
            .putString(KEY_SETUP_STATE, setupState.name)
            .putString(KEY_LAST_ERROR, error.take(2_000))
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .commit()
    }

    @Synchronized
    fun prepareEngineRestart(context: Context, generation: Long, source: String): Boolean {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        if (!accepts(before, generation)) return false
        val committed = prefs.edit()
            .putString(KEY_SETUP_STATE, EmbeddedSetupState.STARTING.name)
            .putString(KEY_CONNECTION_STATE, EmbeddedConnectionState.CONNECTING.name)
            .putInt(KEY_REPORTED_UID, -1)
            .remove(KEY_LAST_STATUS)
            .putString(KEY_LAST_ERROR, "")
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .commit()
        if (committed) {
            logState(context, "embedded_engine_restart_prepared", source, snapshotFrom(prefs))
        }
        return committed
    }

    @Synchronized
    fun markConnecting(context: Context, generation: Long, source: String): Boolean {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        if (!accepts(before, generation)) return false
        if (before.connectionState == EmbeddedConnectionState.CONNECTED) return true
        val committed = prefs.edit()
            .putString(KEY_CONNECTION_STATE, EmbeddedConnectionState.CONNECTING.name)
            .putInt(KEY_REPORTED_UID, -1)
            .remove(KEY_LAST_STATUS)
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .commit()
        if (committed) {
            logState(context, "embedded_connection_state_changed", source, snapshotFrom(prefs))
        }
        return committed
    }

    @Synchronized
    fun recordLiveHandshake(
        context: Context,
        generation: Long,
        reportedUid: Int,
        status: String,
        source: String
    ): Boolean {
        require(reportedUid == EmbeddedGuardianRuntimeState.SHELL_UID) {
            "embedded engine must report shell UID 2000"
        }
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        if (!accepts(before, generation)) return false
        val committed = prefs.edit()
            .putString(KEY_SETUP_STATE, EmbeddedSetupState.IDLE.name)
            .putString(KEY_CONNECTION_STATE, EmbeddedConnectionState.CONNECTED.name)
            .putInt(KEY_REPORTED_UID, reportedUid)
            .putString(KEY_LAST_STATUS, status)
            .putString(KEY_LAST_ERROR, "")
            .remove(KEY_REBOOT_REMINDER_PENDING)
            .remove(KEY_REBOOT_REMINDER_SOURCE)
            .remove(KEY_REBOOT_REMINDER_BOOT_ACTION)
            .remove(KEY_REBOOT_REMINDER_CREATED_WALL)
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .commit()
        if (committed && (before.connectionState != EmbeddedConnectionState.CONNECTED || before.reportedUid != reportedUid)) {
            logState(context, "embedded_connection_state_changed", source, snapshotFrom(prefs))
        }
        return committed
    }

    @Synchronized
    fun recordLivePing(
        context: Context,
        generation: Long,
        reportedUid: Int,
        warning: String,
        source: String
    ): Boolean {
        require(reportedUid == EmbeddedGuardianRuntimeState.SHELL_UID) {
            "embedded engine must report shell UID 2000"
        }
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        if (!accepts(before, generation)) return false
        val committed = prefs.edit()
            .putString(KEY_SETUP_STATE, EmbeddedSetupState.IDLE.name)
            .putString(KEY_CONNECTION_STATE, EmbeddedConnectionState.CONNECTED.name)
            .putInt(KEY_REPORTED_UID, reportedUid)
            .putString(KEY_LAST_ERROR, warning.take(2_000))
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .commit()
        if (committed) {
            logState(
                context,
                "embedded_live_ping_preserved_connection",
                source,
                snapshotFrom(prefs),
                mapOf("warning" to warning.take(500))
            )
        }
        return committed
    }

    @Synchronized
    fun markPairingInvalid(
        context: Context,
        generation: Long,
        error: String,
        source: String
    ): Boolean {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        if (!accepts(before, generation)) return false
        val committed = prefs.edit()
            .putBoolean(KEY_PAIRED, false)
            .putInt(KEY_PAIRING_PORT, 0)
            .putInt(KEY_CONNECT_PORT, 0)
            .putString(KEY_SETUP_STATE, EmbeddedSetupState.WAITING_PAIRING_CODE.name)
            .putString(KEY_CONNECTION_STATE, EmbeddedConnectionState.DISCONNECTED.name)
            .putInt(KEY_REPORTED_UID, -1)
            .putString(KEY_LAST_ERROR, error.take(2_000))
            .remove(KEY_LAST_STATUS)
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .commit()
        if (committed) {
            logState(
                context,
                "embedded_pairing_invalidated",
                source,
                snapshotFrom(prefs),
                mapOf("error" to error.take(500))
            )
        }
        return committed
    }

    @Synchronized
    fun markConnectionUnavailable(
        context: Context,
        generation: Long,
        connectionState: EmbeddedConnectionState,
        error: String,
        source: String
    ): Boolean {
        require(connectionState != EmbeddedConnectionState.CONNECTED)
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        if (!accepts(before, generation)) return false
        val committed = prefs.edit()
            .putString(KEY_CONNECTION_STATE, connectionState.name)
            .putInt(KEY_REPORTED_UID, -1)
            .remove(KEY_LAST_STATUS)
            .putString(KEY_LAST_ERROR, error.take(2_000))
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .commit()
        if (committed && (before.connectionState != connectionState || before.reportedUid != -1)) {
            logState(context, "embedded_connection_state_changed", source, snapshotFrom(prefs))
        }
        return committed
    }

    @Synchronized
    fun markRuntimeUnavailableAfterBoot(
        context: Context,
        source: String,
        bootAction: String
    ) {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        val before = snapshotFrom(prefs)
        if (!before.featureEnabled) return
        val generation = before.generation + 1L
        val createdWall = System.currentTimeMillis()
        check(
            prefs.edit()
                .putString(KEY_SETUP_STATE, EmbeddedSetupState.IDLE.name)
                .putString(KEY_CONNECTION_STATE, EmbeddedConnectionState.DISCONNECTED.name)
                .putInt(KEY_REPORTED_UID, -1)
                .putLong(KEY_GENERATION, generation)
                .remove(KEY_LAST_STATUS)
                .putString(KEY_LAST_ERROR, "")
                .putBoolean(KEY_REBOOT_REMINDER_PENDING, true)
                .putString(KEY_REBOOT_REMINDER_SOURCE, source)
                .putString(KEY_REBOOT_REMINDER_BOOT_ACTION, bootAction)
                .putLong(KEY_REBOOT_REMINDER_CREATED_WALL, createdWall)
                .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
                .commit()
        ) { "failed to clear embedded runtime state after boot" }
        val after = snapshotFrom(prefs)
        if (before.connectionState != EmbeddedConnectionState.DISCONNECTED || before.reportedUid != -1) {
            logState(context, "embedded_connection_state_changed", source, after)
        }
        LogManager.event(
            context,
            "embedded_reboot_reminder_pending",
            eventFields(after, source) + mapOf(
                "bootAction" to bootAction,
                "createdWall" to createdWall
            )
        )
    }

    @Synchronized
    fun rebootReminder(context: Context): RebootReminder {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        return RebootReminder(
            pending = prefs.getBoolean(KEY_REBOOT_REMINDER_PENDING, false),
            source = prefs.getString(KEY_REBOOT_REMINDER_SOURCE, "").orEmpty(),
            bootAction = prefs.getString(KEY_REBOOT_REMINDER_BOOT_ACTION, "").orEmpty(),
            createdWall = prefs.getLong(KEY_REBOOT_REMINDER_CREATED_WALL, 0L)
        )
    }

    @Synchronized
    fun isGenerationActive(context: Context, generation: Long): Boolean {
        val current = snapshot(context)
        return accepts(current, generation)
    }

    @Synchronized
    fun isDisabledGeneration(context: Context, generation: Long): Boolean {
        val current = snapshot(context)
        return !current.featureEnabled && current.generation == generation
    }

    @Synchronized
    fun setPairingPort(context: Context, generation: Long, port: Int): Boolean =
        updateIntIfActive(context, generation, KEY_PAIRING_PORT, port)

    @Synchronized
    fun setConnectPort(context: Context, generation: Long, port: Int): Boolean =
        updateIntIfActive(context, generation, KEY_CONNECT_PORT, port)

    @Synchronized
    fun markPaired(context: Context, generation: Long): Boolean {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        if (!accepts(snapshotFrom(prefs), generation)) return false
        return prefs.edit().putBoolean(KEY_PAIRED, true).commit()
    }

    fun eventFields(snapshot: Snapshot, source: String): Map<String, Any?> = mapOf(
        "featureEnabled" to snapshot.featureEnabled,
        "setupState" to snapshot.setupState.name,
        "connectionState" to snapshot.connectionState.name,
        "binderAlive" to snapshot.binderAlive,
        "reportedUid" to snapshot.reportedUid,
        "source" to source,
        "generation" to snapshot.generation
    )

    private fun updateIntIfActive(
        context: Context,
        generation: Long,
        key: String,
        value: Int
    ): Boolean {
        val prefs = prefs(context)
        ensureRuntimeOwner(context, prefs)
        if (!accepts(snapshotFrom(prefs), generation)) return false
        return prefs.edit().putInt(key, value).commit()
    }

    private fun ensureRuntimeOwner(context: Context, prefs: SharedPreferences) {
        val schema = prefs.getInt(KEY_STATE_SCHEMA, 0)
        val ownerMatches = prefs.getString(KEY_RUNTIME_OWNER, "") == RUNTIME_OWNER
        val bootMatches = prefs.getString(KEY_RUNTIME_BOOT_ID, "") == PROCESS_BOOT_ID

        // r294 vCode118 migrates the old random per-process owner in place.
        // Same schema + same Android boot means the persisted generation is
        // still valid; only rewrite the ownership marker. Do not clear runtime
        // state or increment generation merely because another app process is
        // reading the store.
        if (schema == STATE_SCHEMA && bootMatches) {
            if (!ownerMatches) {
                check(
                    prefs.edit()
                        .putString(KEY_RUNTIME_OWNER, RUNTIME_OWNER)
                        .commit()
                ) { "failed to migrate embedded runtime owner" }
            }
            return
        }

        val featureEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val oldState = prefs.getString(LEGACY_KEY_STATE, "").orEmpty()
        val oldStatus = prefs.getString(KEY_LAST_STATUS, "").orEmpty()
        val oldUid = prefs.getInt(KEY_REPORTED_UID, -1)
        val oldConnection = prefs.getString(KEY_CONNECTION_STATE, "").orEmpty()
        val hadStaleRuntime = oldStatus.isNotBlank() || oldUid >= 0 ||
            oldState in setOf("connected", "waiting_start") ||
            oldConnection == EmbeddedConnectionState.CONNECTED.name ||
            prefs.contains(LEGACY_KEY_CONNECTED) ||
            prefs.contains(LEGACY_KEY_RUNNING) ||
            prefs.contains(LEGACY_KEY_BINDER_ALIVE) ||
            prefs.contains(LEGACY_KEY_LAST_UID) ||
            prefs.contains(LEGACY_KEY_LAST_KNOWN_STATE)
        val generation = prefs.getLong(KEY_GENERATION, 0L) + 1L
        val editor = prefs.edit()
            .putInt(KEY_STATE_SCHEMA, STATE_SCHEMA)
            .putString(KEY_RUNTIME_OWNER, RUNTIME_OWNER)
            .putString(KEY_RUNTIME_BOOT_ID, PROCESS_BOOT_ID)
            .putBoolean(KEY_ENABLED, featureEnabled)
            .putString(KEY_SETUP_STATE, EmbeddedSetupState.IDLE.name)
            .putString(KEY_CONNECTION_STATE, EmbeddedConnectionState.DISCONNECTED.name)
            .putInt(KEY_REPORTED_UID, -1)
            .putLong(KEY_GENERATION, generation)
            .remove(LEGACY_KEY_STATE)
            .remove(KEY_LAST_STATUS)
            .remove(KEY_LAST_ERROR)
            .remove(LEGACY_KEY_CONNECTED)
            .remove(LEGACY_KEY_RUNNING)
            .remove(LEGACY_KEY_BINDER_ALIVE)
            .remove(LEGACY_KEY_LAST_UID)
            .remove(LEGACY_KEY_LAST_KNOWN_STATE)
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
        if (schema != STATE_SCHEMA) {
            // The one-time legacy migration keeps only the user's feature choice.
            // Pairing identity is stored separately by Kadb and can be rediscovered safely.
            editor
                .remove(KEY_TOKEN)
                .remove(KEY_SERVER_PORT)
                .remove(KEY_PAIRING_PORT)
                .remove(KEY_CONNECT_PORT)
                .remove(KEY_PAIRED)
        }
        check(editor.commit()) { "failed to migrate embedded runtime state" }
        if (hadStaleRuntime) {
            logState(
                context,
                "embedded_stale_runtime_state_cleared",
                "runtime_owner_migration",
                snapshotFrom(prefs),
                mapOf(
                    "previousState" to oldState,
                    "previousConnectionState" to oldConnection,
                    "previousReportedUid" to oldUid,
                    "schemaBefore" to schema,
                    "legacyMigration" to (schema != STATE_SCHEMA),
                    "bootChanged" to !bootMatches,
                    "runtimeOwnerChanged" to !ownerMatches
                )
            )
        }
    }

    private fun snapshotFrom(prefs: SharedPreferences): Snapshot {
        val featureEnabled = prefs.getBoolean(KEY_ENABLED, false)
        val setupState = enumValueOr(
            prefs.getString(KEY_SETUP_STATE, null),
            EmbeddedSetupState.IDLE
        )
        val connectionState = enumValueOr(
            prefs.getString(KEY_CONNECTION_STATE, null),
            EmbeddedConnectionState.DISCONNECTED
        )
        val generation = prefs.getLong(KEY_GENERATION, 0L)
        val normalized = EmbeddedGuardianStatePolicy.normalizePersisted(
            featureEnabled = featureEnabled,
            setupState = setupState,
            connectionState = connectionState,
            reportedUid = prefs.getInt(KEY_REPORTED_UID, -1),
            generation = generation,
            runtimeOwnerIsCurrent = true
        )
        return Snapshot(
            featureEnabled = normalized.featureEnabled,
            setupState = normalized.setupState,
            connectionState = normalized.connectionState,
            reportedUid = normalized.reportedUid,
            generation = normalized.generation,
            serverPort = prefs.getInt(KEY_SERVER_PORT, 0),
            pairingPort = prefs.getInt(KEY_PAIRING_PORT, 0),
            connectPort = prefs.getInt(KEY_CONNECT_PORT, 0),
            paired = prefs.getBoolean(KEY_PAIRED, false),
            lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty(),
            lastStatus = prefs.getString(KEY_LAST_STATUS, "").orEmpty(),
            updatedElapsed = prefs.getLong(KEY_UPDATED_ELAPSED, 0L)
        )
    }

    private fun accepts(snapshot: Snapshot, generation: Long): Boolean =
        EmbeddedGuardianStatePolicy.acceptsAsyncUpdate(
            featureEnabled = snapshot.featureEnabled,
            expectedGeneration = generation,
            currentGeneration = snapshot.generation
        )

    private fun identityFrom(prefs: SharedPreferences): EndpointIdentity? {
        val token = prefs.getString(KEY_TOKEN, null)?.takeIf(TOKEN::matches) ?: return null
        val port = prefs.getInt(KEY_SERVER_PORT, 0).takeIf { it in 1024..65535 } ?: return null
        return EndpointIdentity(port, token)
    }

    private fun logState(
        context: Context,
        event: String,
        source: String,
        snapshot: Snapshot,
        extra: Map<String, Any?> = emptyMap()
    ) {
        LogManager.event(context, event, eventFields(snapshot, source) + extra)
    }

    private fun prefs(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun newToken(): String = ByteArray(32)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    private fun findPort(): Int = ServerSocket(0).use { it.localPort }

    private inline fun <reified T : Enum<T>> enumValueOr(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty()) }.getOrDefault(fallback)

    data class EndpointIdentity(val port: Int, val token: String)

    private val TOKEN = Regex("^[a-f0-9]{64}$")
}
