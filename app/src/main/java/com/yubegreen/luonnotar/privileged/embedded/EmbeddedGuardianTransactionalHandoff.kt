package com.yubegreen.luonnotar.privileged.embedded

import com.yubegreen.luonnotar.privileged.PrivilegedGuardianUserService
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * r294 two-engine transaction.
 *
 * The old engine stays operational while the candidate proves its revision and
 * prepares the SSH rescue layer. The predecessor then transfers only the
 * singleton lock; the candidate starts its watchdogs and records READY on the
 * temporary authenticated endpoint. Only after READY is durable is the primary
 * control listener transferred. The predecessor stops after exact-revision
 * takeover confirmation.
 */
internal object EmbeddedGuardianTransactionalHandoff {
    private const val CANDIDATE_START_TIMEOUT_MS = 6_000L
    private const val CANDIDATE_PING_DELAY_MS = 100L
    private const val CANDIDATE_PING_ATTEMPTS = 60

    fun execute(
        payload: String,
        primaryPort: Int,
        primaryToken: String,
        engine: PrivilegedGuardianUserService,
        primaryGuard: EmbeddedEngineInstanceGuard,
        serverControl: EmbeddedGuardianServerControl
    ): String {
        val request = JSONObject(payload)
        val apkPath = request.optString("apkPath").trim()
        val expectedRevision = request.optInt("expectedRevision", -1)
        val reason = request.optString("reason", "remote_request").take(120)
        require(expectedRevision >= EmbeddedGuardianProtocol.ENGINE_REVISION) {
            "transactional handoff cannot downgrade revision"
        }
        EmbeddedGuardianHandoffLauncher.verifyInstalledPackagePathForTransaction(apkPath)
        check(primaryGuard.beginHandoffExclusion(expectedRevision)) {
            "handoff exclusion unavailable"
        }

        val candidatePort = findLoopbackPort()
        val candidateToken = randomToken()
        var candidatePid = -1
        var candidateRevision = -1
        var primaryReleased = false
        var primaryListenerClosed = false
        try {
            candidatePid = launchCandidate(
                apkPath = apkPath,
                port = candidatePort,
                token = candidateToken,
                expectedRevision = expectedRevision,
                reason = reason
            )
            val candidateClient = EmbeddedGuardianClient(
                candidatePort,
                candidateToken,
                connectTimeoutMs = 400,
                readTimeoutMs = 2_000
            )
            val ping = awaitCandidate(candidateClient, candidatePid, expectedRevision)
            candidateRevision = ping.optInt("engineRevision", -1)
            val configJson = engine.exportConfigJson()
            val prepared = JSONObject(
                candidateClient.handoffPrepare(
                    JSONObject()
                        .put("config", configJson)
                        .put("expectedRevision", expectedRevision)
                        .put("reason", reason)
                        .toString()
                )
            )
            check(prepared.optBoolean("ready", false)) { "candidate prepare not ready" }

            // Transfer only the singleton lock first. The predecessor keeps
            // its watchdogs and primary control listener alive while the
            // candidate starts its own watchdogs on the authenticated temporary
            // endpoint. This preserves the invariant that a READY engine exists
            // throughout candidate activation.
            check(primaryGuard.releasePrimaryForHandoff(expectedRevision)) {
                "old primary lock release failed"
            }
            primaryReleased = true

            val activated = JSONObject(
                candidateClient.handoffActivate(
                    JSONObject()
                        .put("config", configJson)
                        .put("expectedRevision", expectedRevision)
                        .put("reason", reason)
                        .toString()
                )
            )
            check(activated.optBoolean("ready", false)) { "candidate activation failed" }
            check(activated.optInt("engineRevision", -1) == expectedRevision) {
                "candidate revision mismatch after activation"
            }

            // Candidate is now READY (watchdogs + SSH preflight + exact
            // revision) on its temporary IPC endpoint. Release only the old
            // listener, promote the READY candidate to the persisted endpoint,
            // and verify the endpoint before stopping the predecessor.
            serverControl.closeListener()
            primaryListenerClosed = true

            val promotedResult = JSONObject(
                candidateClient.handoffPromote(
                    JSONObject()
                        .put("primaryPort", primaryPort)
                        .put("primaryToken", primaryToken)
                        .put("expectedRevision", expectedRevision)
                        .toString()
                )
            )
            check(promotedResult.optBoolean("primaryBound", false)) {
                "READY candidate failed to bind primary endpoint"
            }

            val primaryClient = EmbeddedGuardianClient(
                primaryPort,
                primaryToken,
                connectTimeoutMs = 700,
                readTimeoutMs = 3_000
            )
            val primaryPing = JSONObject(primaryClient.ping())
            check(primaryPing.optBoolean("ready", false)) { "promoted candidate not READY" }
            check(primaryPing.optString("role") == "primary") { "promoted candidate role mismatch" }
            check(primaryPing.optInt("engineRevision", -1) == expectedRevision) {
                "promoted endpoint revision mismatch"
            }

            // COMMIT POINT: the candidate owns the primary singleton, has
            // active watchdogs, answers on the persisted endpoint, and reports
            // the exact expected revision. From here onward predecessor cleanup
            // must never roll the READY candidate back.
            val cleanupWarnings = mutableListOf<String>()
            runCatching {
                primaryGuard.recordHandoffTakeoverConfirmed(
                    expectedRevision = expectedRevision,
                    candidateRevision = activated.optInt("engineRevision", -1),
                    candidatePid = activated.optInt("pid", candidatePid)
                )
            }.onFailure { cleanupWarnings += "record:${it.javaClass.simpleName}" }
            // Only now may the predecessor stop its watchdogs. The SSH daemon is
            // a separate shell process and is explicitly preserved across this.
            runCatching { engine.stopForHandoff() }
                .onFailure { cleanupWarnings += "old_stop:${it.javaClass.simpleName}" }
            runCatching { primaryGuard.finishHandoffExclusion("handoff_takeover_confirmed") }
                .onFailure { cleanupWarnings += "exclusion_release:${it.javaClass.simpleName}" }
            return JSONObject()
                .put("accepted", true)
                .put("transactional", true)
                .put("ready", true)
                .put("fromRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                .put("expectedRevision", expectedRevision)
                .put("candidateRevision", activated.optInt("engineRevision", -1))
                .put("oldPid", android.os.Process.myPid())
                .put("candidatePid", activated.optInt("pid", candidatePid))
                .put("ssh", activated.optJSONObject("ssh") ?: JSONObject.NULL)
                .put("cleanupWarnings", cleanupWarnings.joinToString(","))
                .toString()
        } catch (error: Throwable) {
            val rollbackWarnings = mutableListOf<String>()
            runCatching {
                primaryGuard.recordHandoffFailed(
                    expectedRevision = expectedRevision,
                    candidateRevision = candidateRevision,
                    candidatePid = candidatePid,
                    reason = "${error.javaClass.simpleName}:${error.message.orEmpty()}"
                )
            }.onFailure { rollbackWarnings += "record_failure:${it.javaClass.simpleName}" }
            runCatching {
                rollbackCandidate(candidatePid = candidatePid)
            }.onFailure { rollbackWarnings += "candidate_destroy:${it.javaClass.simpleName}" }
            if (primaryReleased) {
                val reacquired = retryRollbackStep(attempts = 20, delayMs = 50L) {
                    primaryGuard.reacquirePrimaryAfterRollback(
                        "${error.javaClass.simpleName}:${error.message.orEmpty()}"
                    )
                }
                if (!reacquired) rollbackWarnings += "old_primary_lock_not_reacquired"
            }
            if (primaryListenerClosed) {
                val rebound = retryRollbackStep(attempts = 20, delayMs = 50L) {
                    runCatching {
                        serverControl.rebind(primaryPort, primaryToken)
                        true
                    }.getOrDefault(false)
                }
                if (!rebound) rollbackWarnings += "old_listener_not_rebound"
            }
            runCatching { primaryGuard.finishHandoffExclusion("handoff_failed") }
                .onFailure { rollbackWarnings += "exclusion_release:${it.javaClass.simpleName}" }
            return JSONObject()
                .put("accepted", false)
                .put("transactional", true)
                .put("ready", false)
                .put("expectedRevision", expectedRevision)
                .put("candidatePid", candidatePid)
                .put("reason", "${error.javaClass.simpleName}:${error.message.orEmpty()}".take(500))
                .put("rollbackWarnings", rollbackWarnings.joinToString(","))
                .toString()
        }
    }

    private fun awaitCandidate(
        client: EmbeddedGuardianClient,
        candidatePid: Int,
        expectedRevision: Int
    ): JSONObject {
        var lastError: Throwable? = null
        repeat(CANDIDATE_PING_ATTEMPTS) {
            if (!File("/proc/$candidatePid").exists()) {
                error("candidate process exited before READY")
            }
            val result = runCatching { JSONObject(client.ping()) }
            val ping = result.getOrNull()
            if (
                ping != null &&
                ping.optInt("engineRevision", -1) == expectedRevision &&
                ping.optString("role") == "candidate"
            ) {
                return ping
            }
            lastError = result.exceptionOrNull()
            Thread.sleep(CANDIDATE_PING_DELAY_MS)
        }
        throw IllegalStateException("candidate READY timeout", lastError)
    }

    private fun launchCandidate(
        apkPath: String,
        port: Int,
        token: String,
        expectedRevision: Int,
        reason: String
    ): Int {
        val command = EmbeddedGuardianHandoffCommand.buildCandidate(
            apkPath = apkPath,
            mainClass = EmbeddedGuardianServerMain::class.java.name,
            port = port,
            token = token,
            expectedRevision = expectedRevision,
            reason = reason
        )
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(CANDIDATE_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("candidate launcher timed out")
        }
        val text = process.inputStream.bufferedReader().readText().trim()
        check(process.exitValue() == 0) { "candidate launcher failed: $text" }
        return text.lineSequence().lastOrNull()?.trim()?.toIntOrNull()
            ?: error("candidate pid missing: $text")
    }

    private fun rollbackCandidate(candidatePid: Int) {
        // Do not send OP_DESTROY here. A candidate can already have acquired
        // the primary role before a pre-commit failure, while the standalone
        // SSH daemon must survive rollback. Killing only the candidate process
        // releases its kernel-held file locks without intentionally touching
        // the independent SSH daemon.
        if (candidatePid > 1 && File("/proc/$candidatePid").exists()) {
            runCatching {
                ProcessBuilder("/system/bin/kill", candidatePid.toString()).start()
                    .waitFor(500L, TimeUnit.MILLISECONDS)
            }
        }
        repeat(20) {
            if (candidatePid <= 1 || !File("/proc/$candidatePid").exists()) return
            Thread.sleep(50L)
        }
        if (candidatePid > 1 && File("/proc/$candidatePid").exists()) {
            runCatching {
                ProcessBuilder("/system/bin/kill", "-9", candidatePid.toString()).start()
                    .waitFor(500L, TimeUnit.MILLISECONDS)
            }
            Thread.sleep(100L)
        }
    }


    private fun retryRollbackStep(attempts: Int, delayMs: Long, action: () -> Boolean): Boolean {
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            if (runCatching(action).getOrDefault(false)) return true
            if (attempt + 1 < attempts) Thread.sleep(delayMs)
        }
        return false
    }

    private fun findLoopbackPort(): Int = ServerSocket(0).use { it.localPort }

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
