package com.yubegreen.luonnotar.privileged

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Shell-readable diagnostics that survive app-process death and do not depend
 * on the authenticated embedded-engine socket. No endpoint token or command
 * line is written here.
 */
internal class GuardianDiagnosticStore(
    private val statusFile: File = File(STATUS_PATH),
    private val eventsFile: File = File(EVENTS_PATH),
    private val maxEventsBytes: Long = MAX_EVENTS_BYTES
) {
    fun writeStatus(json: String): Boolean = runCatching {
        atomicWrite(statusFile, json.toByteArray(Charsets.UTF_8))
        true
    }.getOrDefault(false)

    fun appendEvent(event: JSONObject): Boolean = runCatching {
        eventsFile.parentFile?.mkdirs()
        rotateIfNeeded()
        FileOutputStream(eventsFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append(event.toString())
            writer.newLine()
            writer.flush()
        }
        eventsFile.setReadable(true, false)
        true
    }.getOrDefault(false)

    private fun rotateIfNeeded() {
        if (!eventsFile.exists() || eventsFile.length() < maxEventsBytes) return
        val previous = File(eventsFile.parentFile, eventsFile.name + ".1")
        previous.delete()
        if (!eventsFile.renameTo(previous)) eventsFile.delete()
        previous.setReadable(true, false)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        temporary.setReadable(true, false)
        if (!temporary.renameTo(target)) {
            target.delete()
            check(temporary.renameTo(target)) { "atomic rename failed" }
        }
        target.setReadable(true, false)
    }

    companion object {
        const val STATUS_PATH = "/data/local/tmp/luonnotar-guardian-status.json"
        const val EVENTS_PATH = "/data/local/tmp/luonnotar-guardian-events.log"
        private const val MAX_EVENTS_BYTES = 2L * 1024L * 1024L
    }
}
