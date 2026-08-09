package com.yubegreen.luonnotar.privileged.embedded

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.yubegreen.luonnotar.util.LogManager

/**
 * App-UID bridge for restarting Termux's own sshd.
 *
 * The shell engine cannot invoke Termux RUN_COMMAND directly: Termux explicitly
 * rejects adb shell callers. Luonnotar's ordinary app UID can do it after the
 * user grants com.termux.permission.RUN_COMMAND and enables
 * allow-external-apps=true inside Termux.
 */
internal object TermuxRunCommandBridge {
    data class Result(
        val ok: Boolean,
        val reason: String,
        val permissionGranted: Boolean
    )

    fun startSshd(context: Context): Result {
        val app = context.applicationContext
        val installed = runCatching {
            app.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        }.getOrDefault(false)
        if (!installed) return Result(false, "termux_not_installed", false)

        val permissionGranted = app.packageManager.checkPermission(
            RUN_COMMAND_PERMISSION,
            app.packageName
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            LogManager.event(
                app,
                "termux_sshd_recovery_not_ready",
                mapOf("reason" to "run_command_permission_missing")
            )
            return Result(false, "run_command_permission_missing", false)
        }

        val command = Intent().apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            action = RUN_COMMAND_ACTION
            putExtra(EXTRA_COMMAND_PATH, SSHD_PATH)
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
        }
        return runCatching {
            val component = app.startService(command)
            check(component != null) { "RunCommandService returned null" }
            LogManager.event(
                app,
                "termux_sshd_recovery_dispatched",
                mapOf("component" to component.flattenToShortString())
            )
            Result(true, "dispatched", true)
        }.getOrElse { error ->
            LogManager.event(
                app,
                "termux_sshd_recovery_dispatch_failed",
                mapOf("error" to error.toString().take(500))
            )
            Result(false, "${error.javaClass.simpleName}:${error.message}".take(240), true)
        }
    }

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val SSHD_PATH = "/data/data/com.termux/files/usr/bin/sshd"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
}
