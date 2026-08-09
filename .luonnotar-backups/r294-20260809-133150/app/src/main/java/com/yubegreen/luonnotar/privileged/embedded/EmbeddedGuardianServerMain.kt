package com.yubegreen.luonnotar.privileged.embedded

import android.os.Process
import com.yubegreen.luonnotar.privileged.PrivilegedGuardianUserService
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
        val instanceGuard = EmbeddedEngineInstanceGuard.acquire(
            startReason = parsed["reason"].orEmpty().ifBlank { "app_process" }
        ) ?: run {
            System.err.println("Luonnotar duplicate embedded guardian rejected; pid=${Process.myPid()}")
            exitProcess(73)
        }
        val engine = PrivilegedGuardianUserService()
        val stopping = AtomicBoolean(false)
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "luonnotar-embedded-client").apply { isDaemon = true }
        }
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(EmbeddedGuardianProtocol.HOST, port), 16)
        }
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { EmbeddedSelfUpdateCoordinator.shutdown() }
            runCatching { engine.stop() }
            runCatching { server.close() }
            runCatching { instanceGuard.close() }
        })
        instanceGuard.recordListening(port)
        println("Luonnotar embedded guardian listening on ${server.localSocketAddress}; uid=${Process.myUid()}")
        while (!stopping.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            executor.execute { handle(socket, token, port, engine, instanceGuard, stopping, server) }
        }
        executor.shutdown()
        runCatching { EmbeddedSelfUpdateCoordinator.shutdown() }
        runCatching { engine.stop() }
        runCatching { instanceGuard.close() }
        exitProcess(0)
    }

    private fun handle(
        socket: Socket,
        token: String,
        port: Int,
        engine: PrivilegedGuardianUserService,
        instanceGuard: EmbeddedEngineInstanceGuard,
        stopping: AtomicBoolean,
        server: ServerSocket
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
                    if (request.optString("token") != token) error("authentication failed")
                    val operation = request.optString("operation")
                    val payload = request.optString("payload")
                    val result = when (operation) {
                        EmbeddedGuardianProtocol.OP_PING -> JSONObject()
                            .put("engine", "LuonnotarEmbeddedGuardian")
                            .put("uid", Process.myUid())
                            .put("pid", Process.myPid())
                            .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                            .put("handoffSupported", true)
                            .toString()
                        EmbeddedGuardianProtocol.OP_CONFIGURE -> engine.configureAndStart(payload)
                        EmbeddedGuardianProtocol.OP_STATUS -> engine.getStatusJson()
                        EmbeddedGuardianProtocol.OP_CYCLE -> engine.runCycle()
                        EmbeddedGuardianProtocol.OP_RECOVER_GMS -> engine.recoverGms()
                        EmbeddedGuardianProtocol.OP_BACKGROUND_POLICY -> engine.applyBackgroundPolicy(payload)
                        EmbeddedGuardianProtocol.OP_INSTALL_SELF_UPDATE ->
                            EmbeddedSelfUpdateCoordinator.start(payload)
                        EmbeddedGuardianProtocol.OP_SELF_UPDATE_STATUS ->
                            EmbeddedSelfUpdateCoordinator.status()
                        EmbeddedGuardianProtocol.OP_HANDOFF -> {
                            val value = EmbeddedGuardianHandoffLauncher.schedule(
                                payload = payload,
                                port = port,
                                token = token
                            )
                            val accepted = JSONObject(value)
                            instanceGuard.recordHandoffScheduled(
                                expectedRevision = accepted.optInt("expectedRevision", -1),
                                reason = runCatching { JSONObject(payload).optString("reason") }.getOrDefault("")
                            )
                            engine.stop()
                            stopping.set(true)
                            shutdownAfterResponse = true
                            value
                        }
                        EmbeddedGuardianProtocol.OP_STOP -> engine.stop()
                        EmbeddedGuardianProtocol.OP_DESTROY -> {
                            val value = engine.stop()
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
            // A caller may time out and close its socket while a long engine operation
            // is still finishing. AndroidRuntime treats an uncaught exception on this
            // app_process worker as process-fatal, so a broken client connection must
            // never escape the worker thread.
            System.err.println(
                "Luonnotar embedded client session ended: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            )
        } finally {
            if (shutdownAfterResponse) runCatching { server.close() }
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
