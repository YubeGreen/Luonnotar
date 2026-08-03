package com.yubegreen.luonnotar.privileged.embedded

import android.content.Context
import com.flyfishxu.kadb.cert.KadbCert
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent ADB host identity for Kadb 1.3.x.
 *
 * Kadb 1.3.x keeps the active certificate and private key in process memory,
 * so Luonnotar persists both byte arrays in device-protected app storage and
 * restores them before every local pairing/connection flow.
 */
internal object EmbeddedAdbIdentity {
    private const val IDENTITY_DIR = "embedded_adb"
    private const val CERT_FILE = "adbcert.der"
    private const val KEY_FILE = "adbkey.pk8"
    private const val TEN_YEARS_MS = 3650L * 24L * 60L * 60L * 1000L

    private val configured = AtomicBoolean(false)

    fun ensure(context: Context) {
        if (configured.get()) return
        synchronized(this) {
            if (configured.get()) return

            val directBoot = context.createDeviceProtectedStorageContext()
            val directory = directBoot.noBackupFilesDir.resolve(IDENTITY_DIR).apply {
                check(isDirectory || mkdirs()) { "cannot create ADB identity directory" }
            }
            val certFile = directory.resolve(CERT_FILE)
            val keyFile = directory.resolve(KEY_FILE)

            val restored = runCatching {
                if (!certFile.isFile || !keyFile.isFile) return@runCatching false
                val cert = certFile.readBytes()
                val key = keyFile.readBytes()
                check(cert.isNotEmpty() && key.isNotEmpty()) { "empty persisted ADB identity" }
                KadbCert.set(cert, key)
                true
            }.getOrDefault(false)

            if (!restored) {
                certFile.delete()
                keyFile.delete()
                val (cert, key) = KadbCert.get(
                    cn = "Luonnotar",
                    ou = "Luonnotar",
                    o = "Luonnotar",
                    l = "Auckland",
                    st = "Auckland",
                    c = "NZ",
                    notAfter = System.currentTimeMillis() + TEN_YEARS_MS
                )
                atomicWrite(certFile, cert)
                atomicWrite(keyFile, key)
            }

            configured.set(true)
        }
    }

    fun reset(context: Context) {
        synchronized(this) {
            val directBoot = context.createDeviceProtectedStorageContext()
            val directory = directBoot.noBackupFilesDir.resolve(IDENTITY_DIR)
            directory.resolve(CERT_FILE).delete()
            directory.resolve(KEY_FILE).delete()
            configured.set(false)
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        check(bytes.isNotEmpty()) { "refusing to persist empty ADB identity" }
        val temporary = File(target.parentFile, ".${target.name}.${android.os.Process.myPid()}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                error("cannot replace ${target.name}")
            }
            check(temporary.renameTo(target)) { "cannot commit ${target.name}" }
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(false, false)
            target.setWritable(true, true)
        } finally {
            temporary.delete()
        }
    }
}
