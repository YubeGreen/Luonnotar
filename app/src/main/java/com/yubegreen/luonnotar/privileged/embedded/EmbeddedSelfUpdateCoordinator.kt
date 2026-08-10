package com.yubegreen.luonnotar.privileged.embedded

import android.os.Process
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * One-at-a-time asynchronous self-update state owned by the shell engine.
 *
 * r294/v119 extends the install transaction through a shell-owned hot handoff:
 * once PackageInstaller has verified the new package, the surviving predecessor
 * resolves PackageManager's installed base.apk and asks its own primary endpoint
 * to run the transactional handoff. No App/receiver/service lifetime is required.
 * State is persisted so the promoted candidate can report the same transaction.
 */
internal object EmbeddedSelfUpdateCoordinator {
    private const val STATUS_PATH = "/data/local/tmp/luonnotar-self-update/last-self-update-status.json"
    private const val SELF_HANDOFF_READ_TIMEOUT_MS = 55_000

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "luonnotar-self-update").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val last = AtomicReference(idleState())

    fun start(payload: String, primaryPort: Int, primaryToken: String): String {
        val request = JSONObject(payload)
        val apkPath = request.optString("apkPath").trim()
        require(apkPath.isNotBlank()) { "apkPath required" }
        if (!running.compareAndSet(false, true)) {
            return JSONObject(readPublished())
                .put("accepted", false)
                .put("reason", "self_update_already_running")
                .toString()
        }
        publish(
            JSONObject()
                .put("state", "running")
                .put("ok", true)
                .put("accepted", true)
                .put("installState", "running")
                .put("handoffState", "waiting_for_install")
                .put("apkName", File(apkPath).name)
        )
        executor.execute {
            try {
                val result = EmbeddedSelfUpdateInstaller.install(apkPath)
                val installJson = JSONObject(result.toJson())
                    .put("accepted", true)
                    .put("installState", if (result.ok) "success" else "failure")
                    .put("installCode", result.code)
                    .put("installMessage", result.message.take(800))
                if (!result.ok) {
                    publish(
                        installJson
                            .put("state", "failure")
                            .put("handoffState", "not_started")
                    )
                    return@execute
                }

                val installedApk = try {
                    EmbeddedGuardianHandoffLauncher.resolveInstalledPackagePathForTransaction()
                } catch (error: Throwable) {
                    publish(
                        installJson
                            .put("state", "failure")
                            .put("ok", false)
                            .put("code", "HANDOFF_PREPARE_FAILED")
                            .put("handoffState", "failure")
                            .put("handoffReason", "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(800))
                    )
                    return@execute
                }

                publish(
                    installJson
                        .put("state", "running")
                        .put("handoffState", "running")
                        .put("installedApk", installedApk)
                        .put("handoffOwner", "shell_engine")
                )

                val handoffPayload = JSONObject()
                    .put("apkPath", installedApk)
                    .put("discoverExpectedRevision", true)
                    .put("selfUpdateHandoff", true)
                    .put("reason", "self_update_install_success")
                    .toString()
                try {
                    val raw = EmbeddedGuardianClient(
                        primaryPort,
                        primaryToken,
                        connectTimeoutMs = 1_000,
                        readTimeoutMs = SELF_HANDOFF_READ_TIMEOUT_MS
                    ).handoff(handoffPayload)
                    val outcome = JSONObject(raw)
                    // The transactional handoff records the durable outcome at
                    // its commit/rollback point. This is only a fallback for a
                    // response that returned without doing so.
                    val persisted = JSONObject(readPublished())
                    if (persisted.optString("handoffState") == "running") {
                        if (outcome.optBoolean("accepted", false) && outcome.optBoolean("ready", false)) {
                            recordHandoffSuccess(outcome)
                        } else {
                            recordHandoffFailure(outcome.optString("reason", "handoff_rejected"), outcome)
                        }
                    }
                } catch (error: Throwable) {
                    val persisted = JSONObject(readPublished())
                    if (persisted.optString("handoffState") != "success") {
                        recordHandoffFailure(
                            "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(800),
                            null
                        )
                    }
                }
            } catch (error: Throwable) {
                publish(
                    JSONObject()
                        .put("state", "failure")
                        .put("ok", false)
                        .put("code", "INSTALL_FAILED")
                        .put("installState", "failure")
                        .put("handoffState", "not_started")
                        .put("message", "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(800))
                )
            } finally {
                running.set(false)
            }
        }
        return JSONObject(readPublished()).put("accepted", true).toString()
    }

    fun status(): String {
        if (!running.get()) reconcileOrphanedHandoffIfCurrentPrimary()
        val json = JSONObject(readPublished())
        val transactionRunning = running.get() || json.optString("state") == "running"
        return json.put("running", transactionRunning).toString()
    }

    @Synchronized
    private fun reconcileOrphanedHandoffIfCurrentPrimary() {
        val json = JSONObject(readPublished())
        if (json.optString("state") != "running") return
        if (json.optString("installState") != "success") return
        if (json.optString("handoffState") != "running") return

        val engineState = runCatching {
            JSONObject(File(EmbeddedEngineInstanceGuard.STATE_PATH).readText())
        }.getOrNull() ?: return
        if (engineState.optInt("engineRevision", -1) != EmbeddedGuardianProtocol.ENGINE_REVISION) return
        if (engineState.optInt("pid", -1) != Process.myPid()) return

        val installMessage = json.optString("installMessage", json.optString("message"))
        json.put("state", "success")
            .put("ok", true)
            .put("handoffState", "success")
            .put("handoffReason", "orphaned_handoff_reconciled_current_primary")
            .put("handoffExpectedRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
            .put("handoffCandidateRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
            .put("handoffCandidatePid", Process.myPid())
            .put("handoffRecovered", true)
            .put("message", (installMessage + "; HANDOFF_RECOVERED_CURRENT_PRIMARY").take(800))
        publish(json)
    }

    @Synchronized
    fun recordHandoffSuccess(outcome: JSONObject) {
        val json = JSONObject(readPublished())
        val installMessage = json.optString("installMessage", json.optString("message"))
        json.put("state", "success")
            .put("ok", true)
            .put("handoffState", "success")
            .put("handoffReason", "takeover_confirmed")
            .put("handoffExpectedRevision", outcome.optInt("expectedRevision", -1))
            .put("handoffCandidateRevision", outcome.optInt("candidateRevision", -1))
            .put("handoffCandidatePid", outcome.optInt("candidatePid", -1))
            .put("message", (installMessage + "; HANDOFF_SUCCEEDED").take(800))
        publish(json)
    }

    @Synchronized
    fun recordHandoffFailure(reason: String, outcome: JSONObject?) {
        val json = JSONObject(readPublished())
            .put("state", "failure")
            .put("ok", false)
            .put("code", "HANDOFF_FAILED")
            .put("handoffState", "failure")
            .put("handoffReason", reason.take(800))
            .put("message", ("INSTALL_SUCCEEDED; HANDOFF_FAILED: " + reason).take(800))
        if (outcome != null) {
            json.put("handoffExpectedRevision", outcome.optInt("expectedRevision", -1))
                .put("handoffCandidatePid", outcome.optInt("candidatePid", -1))
                .put("handoffRollbackWarnings", outcome.optString("rollbackWarnings"))
        }
        publish(json)
    }

    fun shutdown() {
        EmbeddedSelfUpdateInstaller.abandonActiveSession()
    }

    @Synchronized
    private fun publish(json: JSONObject) {
        val text = JSONObject(json.toString()).toString()
        last.set(text)
        runCatching {
            val file = File(STATUS_PATH)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "self-update status rename failed" }
            }
        }
    }

    @Synchronized
    private fun readPublished(): String {
        val fileText = runCatching {
            File(STATUS_PATH).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (fileText != null && runCatching { JSONObject(fileText) }.isSuccess) {
            last.set(fileText)
            return fileText
        }
        return last.get()
    }

    private fun idleState(): String = JSONObject()
        .put("state", "idle")
        .put("ok", true)
        .put("installState", "idle")
        .put("handoffState", "idle")
        .toString()
}
