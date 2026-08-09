package com.yubegreen.luonnotar.privileged.embedded

import android.os.Process
import com.yubegreen.luonnotar.privileged.PrivilegedGuardianUserService
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/** Entry point launched by app_process under shell/root UID. */
object EmbeddedGuardianServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = parseArgs(args)
        val port = parsed["port"]?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: error("invalid --port")
        val token = parsed["token"]?.takeIf { TOKEN.matches(it) }
            ?: error("invalid --token")
        val startReason = parsed["reason"].orEmpty().ifBlank { "app_process" }
        val initialRole = parsed["role"].orEmpty().ifBlank { "primary" }
        require(initialRole == "primary" || initialRole == "candidate") { "invalid --role" }

        val primaryGuardRef = AtomicReference<EmbeddedEngineInstanceGuard?>(null)
        val candidateGuardRef = AtomicReference<EmbeddedCandidateInstanceGuard?>(null)
        if (initialRole == "candidate") {
            val guard = EmbeddedCandidateInstanceGuard.acquire(startReason) ?: run {
                System.err.println("Luonnotar duplicate transactional candidate rejected; pid=${Process.myPid()}")
                exitProcess(73)
            }
            candidateGuardRef.set(guard)
        } else {
            val guard = EmbeddedEngineInstanceGuard.acquire(startReason) ?: run {
                System.err.println("Luonnotar duplicate embedded guardian rejected; pid=${Process.myPid()}")
                exitProcess(73)
            }
            primaryGuardRef.set(guard)
        }

        val engine = PrivilegedGuardianUserService()
        val stopping = AtomicBoolean(false)
        val role = AtomicReference(initialRole)
        val serverControl = EmbeddedGuardianServerControl(port, token)
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "luonnotar-embedded-client").apply { isDaemon = true }
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { EmbeddedSelfUpdateCoordinator.shutdown() }
            // Process death/update must not take the independent SSH rescue
            // daemon with it. Explicit OP_STOP still performs a managed stop.
            runCatching { engine.stopForHandoff() }
            runCatching { serverControl.close() }
            runCatching { candidateGuardRef.getAndSet(null)?.close() }
            runCatching { primaryGuardRef.getAndSet(null)?.close() }
        })
        primaryGuardRef.get()?.recordListening(port)
        println(
            "Luonnotar embedded guardian listening on ${EmbeddedGuardianProtocol.HOST}:$port; " +
                "uid=${Process.myUid()} role=$initialRole"
        )

        while (!stopping.get()) {
            val listener = serverControl.currentListener()
            if (listener == null) {
                runCatching { Thread.sleep(25L) }
                continue
            }
            val socket = try {
                listener.accept()
            } catch (_: IOException) {
                if (!stopping.get()) continue else break
            }
            executor.execute {
                handle(
                    socket = socket,
                    engine = engine,
                    role = role,
                    primaryGuardRef = primaryGuardRef,
                    candidateGuardRef = candidateGuardRef,
                    serverControl = serverControl,
                    stopping = stopping
                )
            }
        }
        executor.shutdown()
        runCatching { EmbeddedSelfUpdateCoordinator.shutdown() }
        runCatching { engine.stopForHandoff() }
        runCatching { serverControl.close() }
        runCatching { candidateGuardRef.getAndSet(null)?.close() }
        runCatching { primaryGuardRef.getAndSet(null)?.close() }
        exitProcess(0)
    }

    private fun handle(
        socket: Socket,
        engine: PrivilegedGuardianUserService,
        role: AtomicReference<String>,
        primaryGuardRef: AtomicReference<EmbeddedEngineInstanceGuard?>,
        candidateGuardRef: AtomicReference<EmbeddedCandidateInstanceGuard?>,
        serverControl: EmbeddedGuardianServerControl,
        stopping: AtomicBoolean
    ) {
        var shutdownAfterResponse = false
        try {
            socket.use { client ->
                client.soTimeout = 60_000
                val writer = client.getOutputStream().bufferedWriter()
                val response = runCatching {
                    val line = EmbeddedGuardianProtocol.readLimitedLine(client.getInputStream().bufferedReader())
                        ?: error("empty request")
                    val request = JSONObject(line)
                    if (request.optInt("schema", -1) != EmbeddedGuardianProtocol.SCHEMA) error("schema mismatch")
                    if (request.optString("token") != serverControl.token()) error("authentication failed")
                    val operation = request.optString("operation")
                    val payload = request.optString("payload")
                    val result = when (operation) {
                        EmbeddedGuardianProtocol.OP_PING -> JSONObject()
                            .put("engine", "LuonnotarEmbeddedGuardian")
                            .put("uid", Process.myUid())
                            .put("pid", Process.myPid())
                            .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                            .put("handoffSupported", true)
                            .put("transactionalHandoffSupported", true)
                            .put("role", role.get())
                            .put("ready", role.get() == "primary" || role.get() == "candidate_ready")
                            .toString()
                        EmbeddedGuardianProtocol.OP_CONFIGURE -> {
                            check(role.get() == "primary") { "configure requires primary role" }
                            engine.configureAndStart(payload)
                        }
                        EmbeddedGuardianProtocol.OP_STATUS -> engine.getStatusJson()
                        EmbeddedGuardianProtocol.OP_CYCLE -> {
                            check(role.get() == "primary") { "cycle requires primary role" }
                            engine.runCycle()
                        }
                        EmbeddedGuardianProtocol.OP_RECOVER_GMS -> {
                            check(role.get() == "primary") { "recover_gms requires primary role" }
                            engine.recoverGms()
                        }
                        EmbeddedGuardianProtocol.OP_BACKGROUND_POLICY -> {
                            check(role.get() == "primary") { "background_policy requires primary role" }
                            engine.applyBackgroundPolicy(payload)
                        }
                        EmbeddedGuardianProtocol.OP_INSTALL_SELF_UPDATE -> {
                            check(role.get() == "primary") { "install_self_update requires primary role" }
                            EmbeddedSelfUpdateCoordinator.start(payload)
                        }
                        EmbeddedGuardianProtocol.OP_SELF_UPDATE_STATUS ->
                            EmbeddedSelfUpdateCoordinator.status()
                        EmbeddedGuardianProtocol.OP_SSH_STATUS -> engine.sshStatus()
                        EmbeddedGuardianProtocol.OP_SSH_RECONCILE -> {
                            check(role.get() == "primary") { "ssh_reconcile requires primary role" }
                            engine.sshReconcile()
                        }
                        EmbeddedGuardianProtocol.OP_SSH_INSTALL_AUTHORIZED_KEY -> {
                            check(role.get() == "primary") { "ssh_install_authorized_key requires primary role" }
                            engine.sshInstallAuthorizedKey(payload)
                        }
                        EmbeddedGuardianProtocol.OP_HANDOFF_PREPARE -> {
                            check(role.get() == "candidate") { "handoff_prepare requires candidate role" }
                            val json = JSONObject(payload)
                            val expected = json.optInt("expectedRevision", -1)
                            check(expected == EmbeddedGuardianProtocol.ENGINE_REVISION) {
                                "candidate revision mismatch"
                            }
                            engine.prepareHandoffCandidate(json.optString("config"))
                        }
                        EmbeddedGuardianProtocol.OP_HANDOFF_ACTIVATE -> {
                            check(role.get() == "candidate") {
                                "handoff_activate requires candidate role"
                            }
                            val json = JSONObject(payload)
                            val expected = json.optInt("expectedRevision", -1)
                            check(expected == EmbeddedGuardianProtocol.ENGINE_REVISION) {
                                "candidate revision mismatch"
                            }
                            val promotedGuard = EmbeddedEngineInstanceGuard.acquirePromoted(
                                "transactional_takeover:${json.optString("reason").take(100)}"
                            ) ?: error("candidate could not acquire primary lock")
                            primaryGuardRef.set(promotedGuard)
                            val status = JSONObject(engine.configureAndStart(json.optString("config")))
                            val ssh = JSONObject(engine.sshStatus())
                            val sshRequired = ssh.optBoolean("enabled", true) && ssh.optBoolean("provisioned", false)
                            check(!sshRequired || ssh.optBoolean("healthy", false)) {
                                "candidate SSH guardian is not healthy"
                            }
                            promotedGuard.recordCandidateReady(
                                expectedRevision = expected,
                                sshHealthy = ssh.optBoolean("healthy", false),
                                sshProvisioned = ssh.optBoolean("provisioned", false)
                            )
                            candidateGuardRef.getAndSet(null)?.close()
                            role.set("candidate_ready")
                            JSONObject()
                                .put("ready", true)
                                .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                                .put("pid", Process.myPid())
                                .put("statusSchema", status.optInt("schema", -1))
                                .put("ssh", ssh)
                                .toString()
                        }
                        EmbeddedGuardianProtocol.OP_HANDOFF_PROMOTE -> {
                            check(role.get() == "candidate_ready") {
                                "handoff_promote requires READY candidate"
                            }
                            val json = JSONObject(payload)
                            val expected = json.optInt("expectedRevision", -1)
                            check(expected == EmbeddedGuardianProtocol.ENGINE_REVISION) {
                                "candidate revision mismatch"
                            }
                            val primaryPort = json.optInt("primaryPort", -1)
                            val primaryToken = json.optString("primaryToken")
                            check(TOKEN.matches(primaryToken)) { "invalid promoted token" }
                            serverControl.rebind(primaryPort, primaryToken)
                            role.set("primary")
                            primaryGuardRef.get()?.recordListening(primaryPort)
                            JSONObject()
                                .put("primaryBound", true)
                                .put("ready", true)
                                .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                                .put("port", primaryPort)
                                .put("pid", Process.myPid())
                                .toString()
                        }
                        EmbeddedGuardianProtocol.OP_HANDOFF -> {
                            check(role.get() == "primary") { "handoff requires primary role" }
                            val guard = primaryGuardRef.get() ?: error("primary guard missing")
                            val expectedRevision = runCatching { JSONObject(payload).optInt("expectedRevision", -1) }
                                .getOrDefault(-1)
                            guard.recordHandoffScheduled(expectedRevision, runCatching {
                                JSONObject(payload).optString("reason")
                            }.getOrDefault(""))
                            val value = EmbeddedGuardianTransactionalHandoff.execute(
                                payload = payload,
                                primaryPort = serverControl.port,
                                primaryToken = serverControl.token(),
                                engine = engine,
                                primaryGuard = guard,
                                serverControl = serverControl
                            )
                            val outcome = JSONObject(value)
                            if (outcome.optBoolean("accepted", false) && outcome.optBoolean("ready", false)) {
                                stopping.set(true)
                                shutdownAfterResponse = true
                            }
                            value
                        }
                        EmbeddedGuardianProtocol.OP_STOP -> {
                            if (role.get() == "primary") engine.stop() else engine.stopForHandoff()
                        }
                        EmbeddedGuardianProtocol.OP_DESTROY -> {
                            // Explicit destruction of the primary engine is a
                            // managed shutdown and therefore also stops the SSH
                            // daemon. Candidate teardown preserves SSH so a
                            // failed handoff cannot destroy the rescue layer.
                            val value = if (role.get() == "primary") {
                                engine.stop()
                            } else {
                                engine.stopForHandoff()
                            }
                            stopping.set(true)
                            shutdownAfterResponse = true
                            value
                        }
                        else -> error("unsupported operation")
                    }
                    EmbeddedGuardianProtocol.success(result)
                }.getOrElse(EmbeddedGuardianProtocol::failure)
                writer.write(response)
                writer.newLine()
                writer.flush()
            }
        } catch (error: IOException) {
            System.err.println(
                "Luonnotar embedded client session ended: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
        } finally {
            if (shutdownAfterResponse) runCatching { serverControl.close() }
        }
    }

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index].removePrefix("--")
            val value = args.getOrNull(index + 1) ?: error("missing value for $key")
            result[key] = value
            index += 2
        }
        return result
    }

    private val TOKEN = Regex("^[a-f0-9]{64}$")
}
