package com.yubegreen.luonnotar.privileged.embedded

import android.os.Process
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Cross-process singleton lock and durable lifecycle journal for the shell
 * app_process engine. File locks are released by the kernel even after an
 * unclean process death, unlike PID-file-only schemes.
 */
internal class EmbeddedEngineInstanceGuard private constructor(
    private val randomAccessFile: RandomAccessFile,
    private val fileLock: FileLock,
    private val lifecycleFile: File,
    private val stateFile: File,
    val instanceId: String,
    private val startedWallTimeMillis: Long,
    private val bootId: String,
    private val startReason: String
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val heartbeatThread = thread(
        start = true,
        isDaemon = true,
        name = "luonnotar-engine-heartbeat"
    ) {
        while (!closed.get()) {
            runCatching { Thread.sleep(HEARTBEAT_INTERVAL_MS) }
            if (!closed.get()) appendLifecycle("heartbeat")
        }
    }

    fun recordListening(port: Int) {
        appendLifecycle("listening", mapOf("port" to port))
    }

    fun recordDuplicateRejected(candidatePid: Int = Process.myPid()) {
        appendLifecycle("duplicate_engine_rejected", mapOf("candidatePid" to candidatePid))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        appendLifecycle("clean_stop")
        heartbeatThread.interrupt()
        runCatching { fileLock.release() }
        runCatching { randomAccessFile.close() }
    }

    @Synchronized
    private fun appendLifecycle(event: String, extras: Map<String, Any?> = emptyMap()) {
        rotateIfNeeded(lifecycleFile)
        val json = JSONObject()
            .put("event", event)
            .put("wallTimeMillis", System.currentTimeMillis())
            .put("startedWallTimeMillis", startedWallTimeMillis)
            .put("bootId", bootId)
            .put("engineInstanceId", instanceId)
            .put("pid", Process.myPid())
            .put("uid", Process.myUid())
            .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
            .put("startReason", startReason)
        extras.forEach { (key, value) -> json.put(key, value) }
        runCatching {
            lifecycleFile.parentFile?.mkdirs()
            lifecycleFile.appendText(json.toString() + "\n")
            val state = JSONObject()
                .put("bootId", bootId)
                .put("engineInstanceId", instanceId)
                .put("pid", Process.myPid())
                .put("lastEvent", event)
                .put("lastHeartbeatWallTimeMillis", System.currentTimeMillis())
                .put("cleanStop", event == "clean_stop")
                .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
            val temp = File(stateFile.parentFile, stateFile.name + ".tmp")
            temp.writeText(state.toString())
            if (!temp.renameTo(stateFile)) {
                stateFile.delete()
                temp.renameTo(stateFile)
            }
        }
    }

    companion object {
        private const val LOCK_PATH = "/data/local/tmp/luonnotar-guardian-engine.lock"
        const val LIFECYCLE_PATH = "/data/local/tmp/luonnotar-guardian-lifecycle.log"
        const val STATE_PATH = "/data/local/tmp/luonnotar-guardian-engine-state.json"
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val MAX_LIFECYCLE_BYTES = 1_000_000L

        fun acquire(startReason: String): EmbeddedEngineInstanceGuard? {
            val lockFile = File(LOCK_PATH)
            lockFile.parentFile?.mkdirs()
            val raf = RandomAccessFile(lockFile, "rw")
            val lock = runCatching { raf.channel.tryLock() }.getOrNull()
            if (lock == null) {
                runCatching { raf.close() }
                appendRejectedLifecycle(startReason)
                return null
            }
            val lifecycle = File(LIFECYCLE_PATH)
            val state = File(STATE_PATH)
            val bootId = readBootId()
            val previous = runCatching { JSONObject(state.readText()) }.getOrNull()
            if (
                previous != null &&
                previous.optString("bootId") == bootId &&
                !previous.optBoolean("cleanStop", false)
            ) {
                rotateIfNeeded(lifecycle)
                val lost = JSONObject()
                    .put("event", "previous_engine_disappeared_uncleanly")
                    .put("wallTimeMillis", System.currentTimeMillis())
                    .put("bootId", bootId)
                    .put("previousInstanceId", previous.optString("engineInstanceId"))
                    .put("previousPid", previous.optInt("pid", -1))
                    .put("previousHeartbeatWallTimeMillis", previous.optLong("lastHeartbeatWallTimeMillis", 0L))
                    .put("candidatePid", Process.myPid())
                runCatching { lifecycle.appendText(lost.toString() + "\n") }
            }
            val guard = EmbeddedEngineInstanceGuard(
                randomAccessFile = raf,
                fileLock = lock,
                lifecycleFile = lifecycle,
                stateFile = state,
                instanceId = UUID.randomUUID().toString(),
                startedWallTimeMillis = System.currentTimeMillis(),
                bootId = bootId,
                startReason = startReason.take(120)
            )
            guard.appendLifecycle("engine_started")
            return guard
        }

        private fun appendRejectedLifecycle(startReason: String) {
            val lifecycle = File(LIFECYCLE_PATH)
            rotateIfNeeded(lifecycle)
            val json = JSONObject()
                .put("event", "duplicate_engine_rejected")
                .put("wallTimeMillis", System.currentTimeMillis())
                .put("bootId", readBootId())
                .put("candidatePid", Process.myPid())
                .put("uid", Process.myUid())
                .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                .put("startReason", startReason.take(120))
            runCatching { lifecycle.appendText(json.toString() + "\n") }
        }

        private fun readBootId(): String = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrDefault("unavailable")

        private fun rotateIfNeeded(file: File) {
            if (!file.exists() || file.length() < MAX_LIFECYCLE_BYTES) return
            val first = File(file.parentFile, "${file.name}.1")
            val second = File(file.parentFile, "${file.name}.2")
            runCatching { second.delete() }
            runCatching { first.renameTo(second) }
            runCatching { file.renameTo(first) }
        }
    }
}
