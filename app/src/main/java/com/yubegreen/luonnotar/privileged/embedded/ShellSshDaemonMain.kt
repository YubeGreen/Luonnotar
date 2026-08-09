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
        val parsed = parseArgs(args)
        val port = parsed["port"]?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: error("invalid --port")
        val stateDir = File(parsed["state-dir"] ?: ShellSshPaths.STATE_DIR)
        val hostKey = File(stateDir, ShellSshPaths.HOST_KEY_NAME)
        val authorizedKeys = File(stateDir, ShellSshPaths.AUTHORIZED_KEYS_NAME)
        require(authorizedKeys.isFile && authorizedKeys.length() > 0L) {
            "authorized_keys is not provisioned"
        }

        stateDir.mkdirs()
        chmod("700", stateDir)
        chmod("600", authorizedKeys)
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
                    error = ""
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
                error = ""
            )
            println(
                "Luonnotar shell SSH ready; uid=${Process.myUid()} pid=${Process.myPid()} " +
                    "port=$port user=${ShellSshPaths.LOGIN_USER}"
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
                error = "${error.javaClass.simpleName}:${error.message.orEmpty()}"
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
        error: String
    ) {
        runCatching {
            stateDir.mkdirs()
            val state = JSONObject()
                .put("schema", 1)
                .put("event", event)
                .put("ready", ready)
                .put("wallTimeMillis", System.currentTimeMillis())
                .put("pid", Process.myPid())
                .put("uid", Process.myUid())
                .put("port", port)
                .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                .put("hostKeyPath", File(stateDir, ShellSshPaths.HOST_KEY_NAME).absolutePath)
                .put("authorizedKeysPath", File(stateDir, ShellSshPaths.AUTHORIZED_KEYS_NAME).absolutePath)
                .put("error", error.take(500))
            val target = File(stateDir, ShellSshPaths.DAEMON_STATE_NAME)
            val temp = File(stateDir, ShellSshPaths.DAEMON_STATE_NAME + ".tmp")
            temp.writeText(state.toString())
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
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
    const val GUARDIAN_STATE_NAME = "guardian-state.json"
    const val RECOVERY_LOCK_NAME = "guardian-recovery.lock"
    const val LOG_NAME = "sshd.log"
    const val PROCESS_NAME = "luonnotar_shell_sshd"
    const val LOGIN_USER = "shell"
}
