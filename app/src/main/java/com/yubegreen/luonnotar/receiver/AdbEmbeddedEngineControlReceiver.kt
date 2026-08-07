package com.yubegreen.luonnotar.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianClient
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianManager
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianProtocol
import com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianStore
import com.yubegreen.luonnotar.util.LogManager
import org.json.JSONObject

/**
 * Shell-only engine lifecycle entry point.
 *
 * The manifest protects this exported receiver with android.permission.DUMP,
 * which adb shell holds but ordinary third-party apps do not.
 */
class AdbEmbeddedEngineControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val app = context.applicationContext
        val snapshot = EmbeddedGuardianStore.snapshot(app)

        if (action == ACTION_STATUS) {
            val identity = EmbeddedGuardianStore.identity(app)
            val ping = identity?.let { endpoint ->
                runCatching {
                    EmbeddedGuardianClient(
                        endpoint.port,
                        endpoint.token,
                        connectTimeoutMs = 800,
                        readTimeoutMs = 1_200
                    ).ping()
                }.getOrNull()
            }
            val pingJson = ping?.let { runCatching { JSONObject(it) }.getOrNull() }
            val reachable = pingJson != null
            val actualRevision = pingJson?.optInt("engineRevision", -1) ?: -1
            val pid = pingJson?.optInt("pid", -1) ?: -1
            setResultCode(Activity.RESULT_OK)
            setResultData(
                "ok=true;featureEnabled=${snapshot.featureEnabled};paired=${snapshot.paired};" +
                    "storeConnected=${snapshot.liveConnected};engineReachable=$reachable;" +
                    "actualRevision=$actualRevision;expectedRevision=${EmbeddedGuardianProtocol.ENGINE_REVISION};" +
                    "pid=$pid;generation=${snapshot.generation}"
            )
            return
        }

        if (!snapshot.featureEnabled) {
            setResultCode(Activity.RESULT_CANCELED)
            setResultData("ok=false;reason=feature_disabled")
            return
        }

        val dispatched = runCatching {
            EmbeddedGuardianManager.restartEngine(app, "adb_shell_restart")
            true
        }.getOrElse { error ->
            LogManager.event(
                app,
                "adb_embedded_engine_restart_failed",
                mapOf("error" to error.toString())
            )
            false
        }
        setResultCode(if (dispatched) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        setResultData(
            "ok=$dispatched;expectedRevision=${EmbeddedGuardianProtocol.ENGINE_REVISION};" +
                "generation=${snapshot.generation}"
        )
        LogManager.event(
            app,
            "adb_embedded_engine_restart_requested",
            mapOf(
                "dispatched" to dispatched,
                "expectedRevision" to EmbeddedGuardianProtocol.ENGINE_REVISION,
                "generation" to snapshot.generation
            )
        )
    }

    companion object {
        const val ACTION_RESTART =
            "com.yubegreen.luonnotar.action.ADB_ENGINE_RESTART"
        const val ACTION_STATUS =
            "com.yubegreen.luonnotar.action.ADB_ENGINE_STATUS"
        private val SUPPORTED_ACTIONS = setOf(ACTION_RESTART, ACTION_STATUS)
    }
}
