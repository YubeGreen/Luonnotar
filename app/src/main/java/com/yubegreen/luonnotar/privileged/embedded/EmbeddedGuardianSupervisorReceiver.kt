package com.yubegreen.luonnotar.privileged.embedded

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yubegreen.luonnotar.util.LogManager

/**
 * Main-process bridge for the :keeper supervisor.
 *
 * EmbeddedGuardianStore deliberately binds its volatile runtime state to one
 * application process. The keeper process must therefore never read or mutate
 * that store directly; it sends this explicit in-app broadcast instead.
 */
class EmbeddedGuardianSupervisorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) return
        val pending = goAsync()
        runCatching {
            EmbeddedGuardianManager.refreshIfStale(
                context = context.applicationContext,
                minAgeMs = 0L
            ) { result ->
                try {
                    result.onFailure { error ->
                        LogManager.event(
                            context,
                            "embedded_supervisor_refresh_failed",
                            mapOf("error" to error.toString())
                        )
                    }
                } finally {
                    pending.finish()
                }
            }
        }.onFailure { error ->
            LogManager.event(
                context,
                "embedded_supervisor_refresh_failed",
                mapOf("error" to error.toString())
            )
            pending.finish()
        }
    }

    companion object {
        const val ACTION_REFRESH =
            "com.yubegreen.luonnotar.action.EMBEDDED_GUARDIAN_SUPERVISOR_REFRESH"
    }
}
