package com.yubegreen.luonnotar.experiment

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import com.yubegreen.luonnotar.service.GuardianProfilePolicy
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import java.io.File

data class ExperimentSessionOperationResult(
    val ok: Boolean,
    val reason: String,
    val values: Map<String, Any>
)

object ExperimentSessionRecorder {
    fun start(
        context: Context,
        preferences: SharedPreferences,
        rawName: String?,
        source: String
    ): ExperimentSessionOperationResult {
        reconcileBoot(preferences)
        val name = ExperimentSessionPolicy.normalizeSessionName(rawName)
            ?: return result(preferences, false, "invalid_session_name")
        if (
            preferences.getBoolean(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                false
            )
        ) {
            return result(preferences, false, "session_already_active")
        }

        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val sessionId = ExperimentSessionPolicy.newSessionId(
            nowWall,
            nowElapsed,
            Process.myPid()
        )
        val committed = preferences.edit()
            .putBoolean(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                true
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ID,
                sessionId
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_NAME,
                name
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_SOURCE,
                normalizeSource(source)
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_BOOT_ID,
                currentBootId()
            )
            .putLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_WALL,
                nowWall
            )
            .putLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_ELAPSED,
                nowElapsed
            )
            .putInt(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_MARK_COUNT,
                0
            )
            .remove(LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK)
            .remove(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK_WALL
            )
            .remove(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK_ELAPSED
            )
            .remove(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STOPPED_WALL
            )
            .remove(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STOPPED_ELAPSED
            )
            .remove(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_DURATION_MS
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_EVENT,
                "START"
            )
            .commit()
        if (!committed) {
            return result(preferences, false, "commit_failed")
        }

        LogManager.timeline(
            context,
            "experiment_session_started",
            sessionLogDetails(preferences) + configurationDetails(preferences)
        )
        return result(preferences, true, "")
    }

    fun mark(
        context: Context,
        preferences: SharedPreferences,
        rawLabel: String?,
        source: String
    ): ExperimentSessionOperationResult {
        reconcileBoot(preferences)
        if (
            !preferences.getBoolean(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                false
            )
        ) {
            return result(preferences, false, "no_active_session")
        }
        val label = ExperimentSessionPolicy.normalizeMarkLabel(rawLabel)
            ?: return result(preferences, false, "invalid_mark_label")
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val nextCount = preferences.getInt(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_MARK_COUNT,
            0
        ) + 1
        val committed = preferences.edit()
            .putInt(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_MARK_COUNT,
                nextCount
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK,
                label
            )
            .putLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK_WALL,
                nowWall
            )
            .putLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK_ELAPSED,
                nowElapsed
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_EVENT,
                "MARK"
            )
            .commit()
        if (!committed) {
            return result(preferences, false, "commit_failed")
        }

        LogManager.timeline(
            context,
            "experiment_session_marked",
            sessionLogDetails(preferences) + mapOf(
                "markLabel" to label,
                "markCount" to nextCount,
                "source" to normalizeSource(source)
            )
        )
        return result(preferences, true, "")
    }

    fun stop(
        context: Context,
        preferences: SharedPreferences,
        source: String
    ): ExperimentSessionOperationResult {
        reconcileBoot(preferences)
        if (
            !preferences.getBoolean(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                false
            )
        ) {
            return result(preferences, false, "no_active_session")
        }
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val startedElapsed = preferences.getLong(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_ELAPSED,
            0L
        )
        val duration = if (startedElapsed > 0L && startedElapsed <= nowElapsed) {
            nowElapsed - startedElapsed
        } else {
            -1L
        }
        val logDetails = sessionLogDetails(preferences) + mapOf(
            "durationMs" to duration,
            "source" to normalizeSource(source)
        )
        val committed = preferences.edit()
            .putBoolean(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                false
            )
            .putLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STOPPED_WALL,
                nowWall
            )
            .putLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STOPPED_ELAPSED,
                nowElapsed
            )
            .putLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_DURATION_MS,
                duration
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_EVENT,
                "STOP"
            )
            .commit()
        if (!committed) {
            return result(preferences, false, "commit_failed")
        }

        LogManager.timeline(
            context,
            "experiment_session_stopped",
            logDetails
        )
        return result(preferences, true, "")
    }

    fun statusValues(preferences: SharedPreferences): Map<String, Any> {
        reconcileBoot(preferences)
        val nowElapsed = SystemClock.elapsedRealtime()
        val active = preferences.getBoolean(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
            false
        )
        val startedElapsed = preferences.getLong(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_ELAPSED,
            0L
        )
        val lastMarkElapsed = preferences.getLong(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK_ELAPSED,
            0L
        )
        return linkedMapOf(
            "experiment_session_active" to active,
            "experiment_session_id" to preferences.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ID,
                ""
            ).orEmpty(),
            "experiment_session_name" to preferences.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_NAME,
                ""
            ).orEmpty(),
            "experiment_session_source" to preferences.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_SOURCE,
                ""
            ).orEmpty(),
            "experiment_session_boot_id" to preferences.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_BOOT_ID,
                ""
            ).orEmpty(),
            "experiment_session_started_wall" to preferences.getLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_WALL,
                0L
            ),
            "experiment_session_age_ms" to if (
                active && startedElapsed > 0L && startedElapsed <= nowElapsed
            ) {
                nowElapsed - startedElapsed
            } else {
                -1L
            },
            "experiment_session_mark_count" to preferences.getInt(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_MARK_COUNT,
                0
            ),
            "experiment_session_last_mark" to preferences.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_MARK,
                ""
            ).orEmpty(),
            "experiment_session_last_mark_age_ms" to if (
                lastMarkElapsed > 0L && lastMarkElapsed <= nowElapsed
            ) {
                nowElapsed - lastMarkElapsed
            } else {
                -1L
            },
            "experiment_session_last_event" to preferences.getString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_EVENT,
                ""
            ).orEmpty(),
            "experiment_session_last_duration_ms" to preferences.getLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_DURATION_MS,
                -1L
            )
        )
    }

    private fun result(
        preferences: SharedPreferences,
        ok: Boolean,
        reason: String
    ) = ExperimentSessionOperationResult(
        ok = ok,
        reason = reason,
        values = statusValues(preferences)
    )

    private fun sessionLogDetails(
        preferences: SharedPreferences
    ): Map<String, Any> = linkedMapOf(
        "sessionId" to preferences.getString(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ID,
            ""
        ).orEmpty(),
        "sessionName" to preferences.getString(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_NAME,
            ""
        ).orEmpty(),
        "sessionSource" to preferences.getString(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_SOURCE,
            ""
        ).orEmpty(),
        "sessionStartedWall" to preferences.getLong(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_WALL,
            0L
        ),
        "sessionStartedElapsed" to preferences.getLong(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_ELAPSED,
            0L
        ),
        "markCount" to preferences.getInt(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_MARK_COUNT,
            0
        )
    )

    private fun configurationDetails(
        preferences: SharedPreferences
    ): Map<String, Any> = linkedMapOf(
        "guardianProfile" to preferences.getString(
            LuonnotarPreferences.KEY_GUARDIAN_PROFILE,
            ""
        ).orEmpty(),
        "labLevel" to preferences.getInt(
            LuonnotarPreferences.KEY_LAB_EXTREME_LEVEL,
            0
        ),
        "guardianEnabled" to preferences.getBoolean(
            LuonnotarPreferences.KEY_ENABLED,
            false
        ),
        "guardianPaused" to preferences.getBoolean(
            LuonnotarPreferences.KEY_PAUSED,
            false
        ),
        "enabledExperiments" to GuardianProfilePolicy.experimentKeys
            .filter { preferences.getBoolean(it, false) }
            .sorted()
            .joinToString(",")
    )

    private fun reconcileBoot(preferences: SharedPreferences) {
        if (
            !preferences.getBoolean(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                false
            )
        ) return
        val storedBootId = preferences.getString(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_BOOT_ID,
            ""
        ).orEmpty()
        val currentBootId = currentBootId()
        val elapsedClockStillValid = preferences.getLong(
            LuonnotarPreferences.KEY_EXPERIMENT_SESSION_STARTED_ELAPSED,
            0L
        ).let { startedElapsed ->
            startedElapsed > 0L &&
                startedElapsed <= SystemClock.elapsedRealtime()
        }
        if (
            storedBootId.isNotBlank() &&
            storedBootId == currentBootId &&
            elapsedClockStillValid
        ) return
        preferences.edit()
            .putBoolean(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_ACTIVE,
                false
            )
            .putString(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_EVENT,
                "ABORTED_BOOT"
            )
            .putLong(
                LuonnotarPreferences.KEY_EXPERIMENT_SESSION_LAST_DURATION_MS,
                -1L
            )
            .commit()
    }

    private fun currentBootId(): String = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("unavailable")

    private fun normalizeSource(source: String): String =
        ExperimentSessionPolicy.normalizeMarkLabel(source) ?: "unknown"
}
