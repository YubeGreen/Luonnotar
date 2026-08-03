package com.yubegreen.luonnotar.privileged.embedded

import android.content.Context
import android.os.SystemClock
import com.yubegreen.luonnotar.privileged.BackgroundPolicyReport
import com.yubegreen.luonnotar.util.LogManager
import org.json.JSONObject

object EmbeddedBackgroundPolicyStore {
    private const val PREFS = "luonnotar_embedded_background_policy"
    private const val KEY_REPORT = "last_report"
    private const val KEY_UPDATED_ELAPSED = "updated_elapsed"
    private const val KEY_SOURCE = "source"

    data class Snapshot(
        val report: BackgroundPolicyReport,
        val updatedElapsed: Long,
        val source: String
    ) {
        val hasReport: Boolean get() = report.createdElapsed > 0L && report.targets.isNotEmpty()
    }

    fun snapshot(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            report = BackgroundPolicyReport.fromJson(prefs.getString(KEY_REPORT, null)),
            updatedElapsed = prefs.getLong(KEY_UPDATED_ELAPSED, 0L),
            source = prefs.getString(KEY_SOURCE, "").orEmpty()
        )
    }

    fun recordReport(context: Context, rawReport: String, source: String): Boolean {
        val report = BackgroundPolicyReport.fromJson(rawReport)
        if (report.createdElapsed <= 0L || report.targets.isEmpty()) return false
        val app = context.applicationContext
        val committed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REPORT, report.toJson())
            .putLong(KEY_UPDATED_ELAPSED, SystemClock.elapsedRealtime())
            .putString(KEY_SOURCE, source)
            .commit()
        if (committed) {
            LogManager.event(
                app,
                "embedded_background_policy_reported",
                mapOf(
                    "source" to source,
                    "vendor" to report.device.family.name,
                    "verifiedTargets" to report.verifiedTargets,
                    "installedTargets" to report.installedTargets,
                    "commandsSucceeded" to report.commandsSucceeded,
                    "commandsAttempted" to report.commandsAttempted,
                    "requiresOemUserAction" to report.requiresOemUserAction
                )
            )
        }
        return committed
    }

    fun recordFromEngineStatus(context: Context, rawStatus: String, source: String): Boolean {
        val report = runCatching {
            JSONObject(rawStatus).optJSONObject("backgroundPolicy")?.toString()
        }.getOrNull() ?: return false
        return recordReport(context, report, source)
    }
}
