package com.yubegreen.luonnotar.privileged.embedded

import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs inside the UID 2000 app_process and schedules a self-handoff. */
internal object EmbeddedGuardianHandoffLauncher {
    private const val PACKAGE_NAME = "com.yubegreen.luonnotar"
    private const val PACKAGE_PATH_TIMEOUT_MS = 2_000L

    fun schedule(
        payload: String,
        port: Int,
        token: String
    ): String {
        val request = JSONObject(payload)
        val apkPath = request.optString("apkPath").trim()
        val expectedRevision = request.optInt("expectedRevision", -1)
        val reason = request.optString("reason", "remote_request").take(120)
        require(expectedRevision >= EmbeddedGuardianProtocol.MIN_HANDOFF_ENGINE_REVISION) {
            "invalid expected revision"
        }
        verifyInstalledPackagePath(apkPath)

        val oldPid = Process.myPid()
        val oldStartTicks = readStartTicks(oldPid)
        val identity = EmbeddedGuardianStore.EndpointIdentity(port, token)
        val command = EmbeddedGuardianHandoffCommand.build(
            apkPath = apkPath,
            mainClass = EmbeddedGuardianServerMain::class.java.name,
            identity = identity,
            oldPid = oldPid,
            oldStartTicks = oldStartTicks,
            expectedRevision = expectedRevision,
            reason = reason
        )

        val log = File(EmbeddedGuardianStarterCommand.LOG_PATH)
        log.parentFile?.mkdirs()
        ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectInput(File("/dev/null"))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .redirectError(ProcessBuilder.Redirect.appendTo(log))
            .start()

        return JSONObject()
            .put("accepted", true)
            .put("fromRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
            .put("expectedRevision", expectedRevision)
            .put("oldPid", oldPid)
            .put("oldStartTicks", oldStartTicks)
            .toString()
    }

    internal fun readStartTicks(pid: Int): Long {
        val raw = File("/proc/$pid/stat").readText()
        val closingParen = raw.lastIndexOf(')')
        require(closingParen > 0 && closingParen + 2 < raw.length) { "malformed proc stat" }
        val fieldsFromState = raw.substring(closingParen + 2)
            .trim()
            .split(Regex("\\s+"))
        // The first token is field 3 (state); starttime is field 22 => token index 19.
        return fieldsFromState.getOrNull(19)?.toLongOrNull()
            ?: error("missing proc starttime")
    }

    private fun verifyInstalledPackagePath(apkPath: String) {
        require(apkPath.startsWith('/') && apkPath.endsWith(".apk")) { "invalid APK path" }
        val canonical = File(apkPath).canonicalPath
        require(File(canonical).isFile && File(canonical).canRead()) { "APK is not readable" }

        val process = ProcessBuilder("/system/bin/cmd", "package", "path", PACKAGE_NAME)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(PACKAGE_PATH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("package path lookup timed out")
        }
        val installed = process.inputStream.bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { it.startsWith("package:") }
                .map { File(it.removePrefix("package:")).canonicalPath }
                .toSet()
        }
        check(process.exitValue() == 0 && canonical in installed) {
            "handoff APK does not match installed package"
        }
    }
}
