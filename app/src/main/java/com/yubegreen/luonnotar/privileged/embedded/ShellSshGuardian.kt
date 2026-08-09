package com.yubegreen.luonnotar.privileged.embedded

import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Local watchdog for the stand-alone shell-owned SSH daemon.
 *
 * Health is intentionally conjunctive: the expected PID must exist, the TCP
 * listener must be visible in /proc/net/tcp{,6}, and a loopback connection must
 * return an SSH protocol identification banner. A mere process match is never
 * treated as healthy.
 */
internal class ShellSshGuardian {
    private var enabled = true
    private var port = 8025
    private var apkPath = ""
    private var consecutiveFailures = 0
    private var restartCount = 0L
    private var lastProbeWallTimeMillis = 0L
    private var lastHealthyWallTimeMillis = 0L
    private var nextRecoveryWallTimeMillis = 0L
    private var lastReason = "never"
    private var lastSnapshot = Snapshot.uninitialized()

    fun configure(enabled: Boolean, port: Int, apkPath: String) {
        this.enabled = enabled
        this.port = port.coerceIn(1024, 65535)
        this.apkPath = apkPath.trim()
        restorePersistentBackoffIfNeeded()
    }

    @Synchronized
    fun reconcile(force: Boolean = false): Snapshot {
        val now = System.currentTimeMillis()
        val provisioned = authorizedKeysProvisioned()
        val initial = probe(now, provisioned)
        lastSnapshot = initial
        persist(initial)
        if (!enabled) {
            lastReason = "disabled"
            return initial.copy(state = "disabled", reason = lastReason).also {
                lastSnapshot = it
                persist(it)
            }
        }
        if (!provisioned) {
            lastReason = "authorized_keys_missing"
            return initial.copy(state = "unprovisioned", reason = lastReason).also {
                lastSnapshot = it
                persist(it)
            }
        }
        if (initial.healthy) {
            consecutiveFailures = 0
            nextRecoveryWallTimeMillis = 0L
            lastHealthyWallTimeMillis = now
            lastReason = "healthy"
            return initial.copy(state = "healthy", reason = lastReason).also {
                lastSnapshot = it
                persist(it)
            }
        }

        if (!force && now < nextRecoveryWallTimeMillis) {
            lastReason = "recovery_backoff"
            return initial.copy(
                state = "degraded",
                reason = lastReason,
                consecutiveFailures = consecutiveFailures,
                nextRecoveryWallTimeMillis = nextRecoveryWallTimeMillis
            ).also {
                lastSnapshot = it
                persist(it)
            }
        }

        val recoveryLock = tryAcquireRecoveryLock()
        if (recoveryLock == null) {
            lastReason = "recovery_owned_by_peer"
            return initial.copy(
                state = "degraded",
                reason = lastReason,
                consecutiveFailures = consecutiveFailures,
                nextRecoveryWallTimeMillis = nextRecoveryWallTimeMillis
            ).also {
                lastSnapshot = it
                persist(it)
            }
        }

        recoveryLock.use {
            // Another engine may have repaired SSH between our initial probe and
            // acquiring the cross-process recovery mutex. Re-probe before any
            // destructive action so transactional handoff never double-restarts
            // the independent rescue daemon.
            val underLock = probe(System.currentTimeMillis(), provisioned)
            if (underLock.healthy) {
                consecutiveFailures = 0
                nextRecoveryWallTimeMillis = 0L
                lastHealthyWallTimeMillis = System.currentTimeMillis()
                lastReason = "healthy_after_peer_recovery"
                return underLock.copy(
                    state = "healthy",
                    reason = lastReason,
                    consecutiveFailures = 0,
                    nextRecoveryWallTimeMillis = 0L
                ).also {
                    lastSnapshot = it
                    persist(it)
                }
            }

            lastReason = "restart_required"
            stopResidualDaemon()
            val launched = launchDaemon()
            if (!launched) {
                recordRecoveryFailure(now)
                return probe(System.currentTimeMillis(), provisioned).copy(
                    state = "degraded",
                    reason = "launch_failed",
                    consecutiveFailures = consecutiveFailures,
                    nextRecoveryWallTimeMillis = nextRecoveryWallTimeMillis
                ).also {
                    lastSnapshot = it
                    persist(it)
                }
            }
            restartCount += 1L

            var verified = probe(System.currentTimeMillis(), provisioned)
            for (attempt in 0 until VERIFY_ATTEMPTS) {
                if (verified.healthy) break
                Thread.sleep(VERIFY_DELAY_MS)
                verified = probe(System.currentTimeMillis(), provisioned)
            }
            if (verified.healthy) {
                consecutiveFailures = 0
                nextRecoveryWallTimeMillis = 0L
                lastHealthyWallTimeMillis = System.currentTimeMillis()
                lastReason = "restart_verified"
                return verified.copy(
                    state = "healthy",
                    reason = lastReason,
                    restartCount = restartCount,
                    consecutiveFailures = 0,
                    nextRecoveryWallTimeMillis = 0L
                ).also {
                    lastSnapshot = it
                    persist(it)
                }
            }

            recordRecoveryFailure(System.currentTimeMillis())
            lastReason = "restart_unverified"
            return verified.copy(
                state = "degraded",
                reason = lastReason,
                restartCount = restartCount,
                consecutiveFailures = consecutiveFailures,
                nextRecoveryWallTimeMillis = nextRecoveryWallTimeMillis
            ).also {
                lastSnapshot = it
                persist(it)
            }
        }
    }

    @Synchronized
    fun probeOnly(): Snapshot {
        val snapshot = probe(System.currentTimeMillis(), authorizedKeysProvisioned())
        lastSnapshot = snapshot
        persist(snapshot)
        return snapshot
    }

    @Synchronized
    fun installAuthorizedKey(raw: String): Snapshot {
        val value = raw.trim()
        require(value.length in 40..16_384) { "authorized key length invalid" }
        require(ShellSshGuardianPolicy.isAuthorizedKey(value)) { "unsupported authorized key format" }
        val dir = File(ShellSshPaths.STATE_DIR)
        dir.mkdirs()
        runCommand("/system/bin/chmod", "700", dir.absolutePath)
        val file = File(dir, ShellSshPaths.AUTHORIZED_KEYS_NAME)
        val existing = if (file.isFile) file.readLines().map(String::trim).filter(String::isNotBlank) else emptyList()
        if (value !in existing) {
            val lines = (existing + value).distinct()
            val temp = File(dir, ShellSshPaths.AUTHORIZED_KEYS_NAME + ".tmp")
            temp.writeText(lines.joinToString("\n", postfix = "\n"))
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "unable to install authorized key" }
            }
        }
        runCommand("/system/bin/chmod", "600", file.absolutePath)

        // Provisioning is a persistence operation, not a synchronous daemon
        // bootstrap transaction. Returning immediately prevents the provider's
        // 5-second engine RPC timeout from reporting a false install failure.
        // The regular 5-second guardian tick performs the actual reconcile.
        consecutiveFailures = 0
        nextRecoveryWallTimeMillis = 0L
        lastReason = "authorized_key_installed"
        val observed = probe(System.currentTimeMillis(), provisioned = true)
        val snapshot = observed.copy(
            state = if (observed.healthy) "healthy" else "degraded",
            reason = if (observed.healthy) "healthy" else lastReason,
            consecutiveFailures = 0,
            nextRecoveryWallTimeMillis = 0L
        )
        lastSnapshot = snapshot
        persist(snapshot)
        return snapshot
    }

    @Synchronized
    fun stopManagedDaemon(reason: String): Snapshot {
        stopResidualDaemon()
        lastReason = reason.take(120)
        val snapshot = probe(System.currentTimeMillis(), authorizedKeysProvisioned()).copy(
            state = "stopped",
            reason = lastReason
        )
        lastSnapshot = snapshot
        persist(snapshot)
        return snapshot
    }

    @Synchronized
    fun snapshot(): Snapshot = lastSnapshot

    private fun probe(now: Long, provisioned: Boolean): Snapshot {
        lastProbeWallTimeMillis = now
        val pids = daemonPids()
        val pidOk = pids.size == 1
        val listenerOk = listenerPresent(port)
        val banner = readSshBanner(port)
        val handshakeOk = banner.startsWith("SSH-2.0-") || banner.startsWith("SSH-1.99-")
        val healthy = enabled && provisioned && pidOk && listenerOk && handshakeOk
        return Snapshot(
            enabled = enabled,
            provisioned = provisioned,
            port = port,
            pids = pids,
            pidOk = pidOk,
            listenerOk = listenerOk,
            handshakeOk = handshakeOk,
            banner = banner.take(160),
            healthy = healthy,
            state = if (healthy) "healthy" else "degraded",
            reason = if (healthy) "healthy" else lastReason,
            restartCount = restartCount,
            consecutiveFailures = consecutiveFailures,
            lastProbeWallTimeMillis = lastProbeWallTimeMillis,
            lastHealthyWallTimeMillis = lastHealthyWallTimeMillis,
            nextRecoveryWallTimeMillis = nextRecoveryWallTimeMillis,
            processUid = Process.myUid()
        )
    }

    private fun authorizedKeysProvisioned(): Boolean {
        val file = File(ShellSshPaths.STATE_DIR, ShellSshPaths.AUTHORIZED_KEYS_NAME)
        if (!file.isFile || file.length() <= 0L) return false
        return runCatching {
            file.useLines { lines -> lines.any { ShellSshGuardianPolicy.isAuthorizedKey(it) } }
        }.getOrDefault(false)
    }

    private fun daemonPids(): List<Int> {
        val process = runCatching {
            ProcessBuilder("/system/bin/pidof", ShellSshPaths.PROCESS_NAME)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return emptyList()
        if (!process.waitFor(500L, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return emptyList()
        }
        return process.inputStream.bufferedReader().readText()
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull(String::toIntOrNull)
            .filter { it > 1 }
            .distinct()
    }

    private fun listenerPresent(port: Int): Boolean {
        val expected = port.toString(16).uppercase().padStart(4, '0')
        return listOf("/proc/net/tcp", "/proc/net/tcp6").any { path ->
            runCatching {
                File(path).useLines { lines ->
                    lines.drop(1).any { line ->
                        val fields = line.trim().split(Regex("\\s+"))
                        val local = fields.getOrNull(1).orEmpty()
                        val state = fields.getOrNull(3).orEmpty()
                        local.substringAfterLast(':', "") == expected && state == "0A"
                    }
                }
            }.getOrDefault(false)
        }
    }

    private fun readSshBanner(port: Int): String = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), HANDSHAKE_TIMEOUT_MS)
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val reader = socket.getInputStream().bufferedReader()
            repeat(4) {
                val line = reader.readLine() ?: return@repeat
                if (line.startsWith("SSH-")) return@use line
            }
            ""
        }
    }.getOrDefault("")

    private fun tryAcquireRecoveryLock(): RecoveryLock? {
        val file = File(ShellSshPaths.STATE_DIR, ShellSshPaths.RECOVERY_LOCK_NAME)
        file.parentFile?.mkdirs()
        val raf = runCatching { RandomAccessFile(file, "rw") }.getOrNull() ?: return null
        val lock = runCatching { raf.channel.tryLock() }.getOrNull()
        if (lock == null) {
            runCatching { raf.close() }
            return null
        }
        return RecoveryLock(raf, lock)
    }

    private class RecoveryLock(
        private val raf: RandomAccessFile,
        private val lock: FileLock
    ) : java.io.Closeable {
        override fun close() {
            runCatching { lock.release() }
            runCatching { raf.close() }
        }
    }

    private fun stopResidualDaemon() {
        daemonPids().forEach { pid -> runCommand("/system/bin/kill", pid.toString()) }
        var stopped = daemonPids().isEmpty()
        repeat(5) {
            if (stopped) return@repeat
            Thread.sleep(100L)
            stopped = daemonPids().isEmpty()
        }
        if (!stopped) {
            daemonPids().forEach { pid -> runCommand("/system/bin/kill", "-9", pid.toString()) }
        }
        // daemon-state.json describes only the live daemon generation; never
        // leave a stale READY record between teardown and verified relaunch.
        runCatching { File(ShellSshPaths.STATE_DIR, ShellSshPaths.DAEMON_STATE_NAME).delete() }
    }

    private fun launchDaemon(): Boolean {
        val sourceApk = resolveApkPath() ?: return false
        val dir = File(ShellSshPaths.STATE_DIR)
        dir.mkdirs()
        runCommand("/system/bin/chmod", "700", dir.absolutePath)
        val log = File(dir, ShellSshPaths.LOG_NAME)
        val q = EmbeddedGuardianProtocol::shellQuote
        val command = buildString {
            append("export CLASSPATH=").append(q(sourceApk)).append("; ")
            append("(")
            append("exec /system/bin/app_process /system/bin --nice-name=")
                .append(ShellSshPaths.PROCESS_NAME).append(' ')
            append(q(ShellSshDaemonMain::class.java.name))
            append(" --port ").append(port)
            append(" --state-dir ").append(q(dir.absolutePath))
            append(" </dev/null >>").append(q(log.absolutePath)).append(" 2>&1")
            append(") & echo $!")
        }
        return runCatching {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(1_000L, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun resolveApkPath(): String? {
        val configured = apkPath.takeIf { it.startsWith('/') && it.endsWith(".apk") }
        if (configured != null && File(configured).canRead()) return configured
        val classpath = System.getenv("CLASSPATH").orEmpty()
            .split(':')
            .firstOrNull { it.startsWith('/') && it.endsWith(".apk") && File(it).canRead() }
        return classpath
    }

    private fun recordRecoveryFailure(now: Long) {
        // Count failed recovery attempts, not the 5-second observation ticks
        // spent waiting inside a backoff window or behind a peer recovery lock.
        consecutiveFailures += 1
        nextRecoveryWallTimeMillis = now +
            ShellSshGuardianPolicy.recoveryBackoffMs(consecutiveFailures)
    }

    private fun restorePersistentBackoffIfNeeded() {
        if (lastProbeWallTimeMillis != 0L) return
        val state = File(ShellSshPaths.STATE_DIR, ShellSshPaths.GUARDIAN_STATE_NAME)
        val json = runCatching { JSONObject(state.readText()) }.getOrNull() ?: return
        consecutiveFailures = json.optInt("consecutiveFailures", 0).coerceAtLeast(0)
        restartCount = json.optLong("restartCount", 0L).coerceAtLeast(0L)
        lastHealthyWallTimeMillis = json.optLong("lastHealthyWallTimeMillis", 0L)
        nextRecoveryWallTimeMillis = json.optLong("nextRecoveryWallTimeMillis", 0L)
    }

    private fun persist(snapshot: Snapshot) {
        runCatching {
            val dir = File(ShellSshPaths.STATE_DIR)
            dir.mkdirs()
            runCommand("/system/bin/chmod", "700", dir.absolutePath)
            val target = File(dir, ShellSshPaths.GUARDIAN_STATE_NAME)
            val temp = File(dir, ShellSshPaths.GUARDIAN_STATE_NAME + ".tmp")
            temp.writeText(snapshot.toJson().toString())
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        }
    }

    private fun runCommand(vararg args: String): Int = runCatching {
        val process = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        if (!process.waitFor(750L, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching 124
        }
        process.exitValue()
    }.getOrDefault(125)

    data class Snapshot(
        val enabled: Boolean,
        val provisioned: Boolean,
        val port: Int,
        val pids: List<Int>,
        val pidOk: Boolean,
        val listenerOk: Boolean,
        val handshakeOk: Boolean,
        val banner: String,
        val healthy: Boolean,
        val state: String,
        val reason: String,
        val restartCount: Long,
        val consecutiveFailures: Int,
        val lastProbeWallTimeMillis: Long,
        val lastHealthyWallTimeMillis: Long,
        val nextRecoveryWallTimeMillis: Long,
        val processUid: Int
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("enabled", enabled)
            .put("provisioned", provisioned)
            .put("port", port)
            .put("pids", pids.joinToString(","))
            .put("pidOk", pidOk)
            .put("listenerOk", listenerOk)
            .put("handshakeOk", handshakeOk)
            .put("banner", banner)
            .put("healthy", healthy)
            .put("state", state)
            .put("reason", reason)
            .put("restartCount", restartCount)
            .put("consecutiveFailures", consecutiveFailures)
            .put("lastProbeWallTimeMillis", lastProbeWallTimeMillis)
            .put("lastHealthyWallTimeMillis", lastHealthyWallTimeMillis)
            .put("nextRecoveryWallTimeMillis", nextRecoveryWallTimeMillis)
            .put("processUid", processUid)

        companion object {
            fun uninitialized() = Snapshot(
                enabled = true,
                provisioned = false,
                port = 8025,
                pids = emptyList(),
                pidOk = false,
                listenerOk = false,
                handshakeOk = false,
                banner = "",
                healthy = false,
                state = "uninitialized",
                reason = "never",
                restartCount = 0L,
                consecutiveFailures = 0,
                lastProbeWallTimeMillis = 0L,
                lastHealthyWallTimeMillis = 0L,
                nextRecoveryWallTimeMillis = 0L,
                processUid = Process.myUid()
            )
        }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 1_000
        private const val VERIFY_ATTEMPTS = 20
        private const val VERIFY_DELAY_MS = 100L
    }
}
