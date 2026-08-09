package com.yubegreen.luonnotar.privileged.embedded

import android.os.Process
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.apache.sshd.server.shell.ProcessShellFactory
import org.json.JSONObject
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stand-alone UID 2000 SSH rescue daemon.
 *
 * This process is deliberately separate from the guardian engine. An APK
 * process restart or an engine handoff therefore does not tear down existing
 * SSH sessions. A future guardian can adopt the already-running daemon after
 * validating PID + listener + SSH protocol banner.
 */
object ShellSshDaemonMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val parsed = runCatching { parseArgs(args) }.getOrElse { emptyMap() }
        val stateDir = File(parsed["state-dir"] ?: ShellSshPaths.STATE_DIR)
        val port = parsed["port"]?.toIntOrNull() ?: 0

        try {
            runDaemon(stateDir, port)
        } catch (error: Throwable) {
            // This catch intentionally wraps crypto/bootstrap work as well as
            // server.start(). Static initializer failures used to escape before
            // daemon-state.json existed, leaving only restart_unverified.
            writeLastFailure(stateDir, port, "bootstrap_or_runtime", error)
            throw error
        }
    }

    private fun runDaemon(stateDir: File, parsedPort: Int) {
        val port = parsedPort.takeIf { it in 1024..65535 } ?: error("invalid --port")
        val hostKey = File(stateDir, ShellSshPaths.HOST_KEY_NAME)
        val authorizedKeys = File(stateDir, ShellSshPaths.AUTHORIZED_KEYS_NAME)
        require(authorizedKeys.isFile && authorizedKeys.length() > 0L) {
            "authorized_keys is not provisioned"
        }

        stateDir.mkdirs()
        chmod("700", stateDir)
        chmod("600", authorizedKeys)

        // Must execute before SshServer.setUpDefaultServer(): Apache MINA 2.19
        // initializes ECCurves from the current JCA provider set during static
        // class initialization.
        val cryptoProvider = ShellSshCryptoBootstrap.installAndVerify()

        val stopping = AtomicBoolean(false)
        val server = SshServer.setUpDefaultServer()
        server.setHost("0.0.0.0")
        server.setPort(port)
        server.setKeyPairProvider(SimpleGeneratorHostKeyProvider(Paths.get(hostKey.absolutePath)))

        val keyAuthenticator = AuthorizedKeysAuthenticator(Paths.get(authorizedKeys.absolutePath))
        server.setPublickeyAuthenticator { username, key, session ->
            username == ShellSshPaths.LOGIN_USER && keyAuthenticator.authenticate(username, key, session)
        }
        server.setPasswordAuthenticator(PasswordAuthenticator { _, _, _ -> false })
        server.setShellFactory(
            ProcessShellFactory(
                "/system/bin/sh",
                listOf("/system/bin/sh", "-i")
            )
        )
        server.setCommandFactory(ProcessShellCommandFactory.INSTANCE)

        Runtime.getRuntime().addShutdownHook(Thread {
            if (stopping.compareAndSet(false, true)) {
                runCatching { server.stop(true) }
                writeState(
                    stateDir = stateDir,
                    port = port,
                    event = "stopped",
                    ready = false,
                    error = "",
                    cryptoProvider = cryptoProvider
                )
            }
        })

        try {
            server.start()
            // The generated host key is deliberately persistent across APK /
            // engine revisions. Keep its file private to the shell UID.
            chmod("600", hostKey)
            writeState(
                stateDir = stateDir,
                port = port,
                event = "ready",
                ready = true,
                error = "",
                cryptoProvider = cryptoProvider
            )
            println(
                "Luonnotar shell SSH ready; uid=${Process.myUid()} pid=${Process.myPid()} " +
                    "port=$port user=${ShellSshPaths.LOGIN_USER} crypto=$cryptoProvider"
            )
            while (!stopping.get()) {
                runCatching { Thread.sleep(60_000L) }
            }
        } catch (error: Throwable) {
            writeState(
                stateDir = stateDir,
                port = port,
                event = "failed",
                ready = false,
                error = "${error.javaClass.simpleName}:${error.message.orEmpty()}",
                cryptoProvider = cryptoProvider
            )
            throw error
        } finally {
            if (stopping.compareAndSet(false, true)) {
                runCatching { server.stop(true) }
            }
        }
    }

    private fun chmod(mode: String, file: File) {
        runCatching {
            val process = ProcessBuilder("/system/bin/chmod", mode, file.absolutePath)
                .redirectErrorStream(true)
                .start()
            process.waitFor()
        }
    }

    private fun writeState(
        stateDir: File,
        port: Int,
        event: String,
        ready: Boolean,
        error: String,
        cryptoProvider: String
    ) {
        runCatching {
            stateDir.mkdirs()
            val state = JSONObject()
                .put("schema", 2)
                .put("event", event)
                .put("ready", ready)
                .put("wallTimeMillis", System.currentTimeMillis())
                .put("pid", Process.myPid())
                .put("uid", Process.myUid())
                .put("port", port)
                .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                .put("hostKeyPath", File(stateDir, ShellSshPaths.HOST_KEY_NAME).absolutePath)
                .put("authorizedKeysPath", File(stateDir, ShellSshPaths.AUTHORIZED_KEYS_NAME).absolutePath)
                .put("cryptoProvider", cryptoProvider.take(300))
                .put("error", error.take(500))
            writeJsonAtomic(File(stateDir, ShellSshPaths.DAEMON_STATE_NAME), state)
        }
    }

    private fun writeLastFailure(stateDir: File, port: Int, phase: String, error: Throwable) {
        runCatching {
            stateDir.mkdirs()
            chmod("700", stateDir)
            val causes = mutableListOf<String>()
            var current: Throwable? = error
            val seen = mutableSetOf<Throwable>()
            while (current != null && causes.size < 12 && seen.add(current)) {
                causes += "${current.javaClass.name}:${current.message.orEmpty()}"
                current = current.cause
            }
            val state = JSONObject()
                .put("schema", 1)
                .put("event", "fatal")
                .put("phase", phase)
                .put("wallTimeMillis", System.currentTimeMillis())
                .put("pid", Process.myPid())
                .put("uid", Process.myUid())
                .put("port", port)
                .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                .put("errorClass", error.javaClass.name)
                .put("error", error.message.orEmpty().take(1_000))
                .put("causeChain", causes.joinToString(" <- ").take(4_000))
                .put("stackTrace", error.stackTraceToString().take(16_000))
            writeJsonAtomic(File(stateDir, ShellSshPaths.LAST_FAILURE_NAME), state)
        }
    }

    private fun writeJsonAtomic(target: File, json: JSONObject) {
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "unable to persist ${target.name}" }
        }
        chmod("600", target)
    }

    private fun parseArgs(args: Array<String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val key = args[index].removePrefix("--")
            val value = args.getOrNull(index + 1) ?: error("missing value for $key")
            out[key] = value
            index += 2
        }
        return out
    }
}

internal object ShellSshPaths {
    const val STATE_DIR = "/data/local/tmp/luonnotar-ssh"
    const val AUTHORIZED_KEYS_NAME = "authorized_keys"
    const val HOST_KEY_NAME = "ssh_host_key"
    const val DAEMON_STATE_NAME = "daemon-state.json"
    const val LAST_FAILURE_NAME = "last-failure.json"
    const val GUARDIAN_STATE_NAME = "guardian-state.json"
    const val RECOVERY_LOCK_NAME = "guardian-recovery.lock"
    const val LOG_NAME = "sshd.log"
    const val PROCESS_NAME = "luonnotar_shell_sshd"
    const val LOGIN_USER = "shell"
}
