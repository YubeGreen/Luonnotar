package com.yubegreen.luonnotar.privileged.embedded

import android.os.Process
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** Allows exactly one transactional candidate alongside the current primary. */
internal class EmbeddedCandidateInstanceGuard private constructor(
    private val raf: RandomAccessFile,
    private val lock: FileLock
) : Closeable {
    override fun close() {
        runCatching { lock.release() }
        runCatching { raf.close() }
    }

    companion object {
        private const val LOCK_PATH = "/data/local/tmp/luonnotar-guardian-candidate.lock"

        fun acquire(reason: String): EmbeddedCandidateInstanceGuard? {
            val file = File(LOCK_PATH)
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            val lock = runCatching { raf.channel.tryLock() }.getOrNull()
            if (lock == null) {
                runCatching { raf.close() }
                appendLifecycle("candidate_duplicate_rejected", reason)
                return null
            }
            appendLifecycle("candidate_started", reason)
            return EmbeddedCandidateInstanceGuard(raf, lock)
        }

        private fun appendLifecycle(event: String, reason: String) {
            runCatching {
                File(EmbeddedEngineInstanceGuard.LIFECYCLE_PATH).appendText(
                    JSONObject()
                        .put("event", event)
                        .put("wallTimeMillis", System.currentTimeMillis())
                        .put("pid", Process.myPid())
                        .put("uid", Process.myUid())
                        .put("engineRevision", EmbeddedGuardianProtocol.ENGINE_REVISION)
                        .put("reason", reason.take(120))
                        .toString() + "\n"
                )
            }
        }
    }
}
