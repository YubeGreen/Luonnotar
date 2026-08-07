package com.yubegreen.luonnotar.privileged.embedded

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal shell-UID self-update PoC.
 *
 * Security boundary is intentionally narrow: the engine can only install a
 * newer APK for Luonnotar, signed by the same signer, from a dedicated shell
 * staging directory. It is not a generic APK installer.
 */
internal object EmbeddedSelfUpdateInstaller {
    const val TARGET_PACKAGE = EmbeddedSelfUpdatePolicy.TARGET_PACKAGE
    const val STAGING_ROOT = EmbeddedSelfUpdatePolicy.STAGING_ROOT
    const val MAX_APK_BYTES = EmbeddedSelfUpdatePolicy.MAX_APK_BYTES
    private const val SHELL_PACKAGE = "com.android.shell"
    private const val FINAL_RESULT_TIMEOUT_MS = 45_000L
    private val activeSessionId = AtomicInteger(-1)
    private val activeInstaller = AtomicReference<PackageInstaller?>(null)
    private val shellContextCache = AtomicReference<Context?>(null)

    data class Result(
        val ok: Boolean,
        val code: String,
        val message: String,
        val sessionId: Int = -1,
        val versionCode: Long = -1L,
        val apkSize: Long = -1L,
        val durationMs: Long = 0L,
        val permissionApprovalAttempt: Int = 0,
        val permissionApprovalElapsed: Long = -1L
    ) {
        fun toJson(): String = JSONObject()
            .put("ok", ok)
            .put("code", code)
            .put("message", message.take(800))
            .put("sessionId", sessionId)
            .put("packageName", TARGET_PACKAGE)
            .put("versionCode", versionCode)
            .put("apkSize", apkSize)
            .put("durationMs", durationMs)
            .put("permissionApprovalAttempt", permissionApprovalAttempt)
            .put("permissionApprovalElapsed", permissionApprovalElapsed)
            .toString()
    }

    fun install(apkPath: String): Result {
        val started = SystemClock.elapsedRealtime()
        require(Process.myUid() == Process.SHELL_UID || Process.myUid() == 0) {
            "self update requires shell/root uid"
        }
        log("self_update_request", "path=${safePathForLog(apkPath)}")
        val context = runCatching { shellContext() }.getOrElse { error ->
            return failure(
                "SILENT_UPDATE_UNSUPPORTED",
                "shell context unavailable: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                started
            )
        }
        val stagingDir = File(STAGING_ROOT)
        ensureStagingDirectory(stagingDir)
        val staged = snapshotSource(apkPath, stagingDir)
        var sessionId = -1
        var installer: PackageInstaller? = null
        try {
            log("self_update_validation_start", "staged=${staged.name} bytes=${staged.length()}")
            val archive = archiveInfo(context.packageManager, staged)
                ?: return failure("APK_INVALID", "unable to parse staged APK", started)
            val installed = installedInfo(context.packageManager)
            val validation = validateArchive(archive, installed, staged.length())
            if (validation != null) return failure(validation.first, validation.second, started)
            val newVersion = archive.longVersionCode
            log("self_update_validation_success", "versionCode=$newVersion bytes=${staged.length()}")

            installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(TARGET_PACKAGE)
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            sessionId = installer.createSession(params)
            activeInstaller.set(installer)
            activeSessionId.set(sessionId)
            log("self_update_session_created", "session=$sessionId versionCode=$newVersion")

            installer.openSession(sessionId).use { session ->
                FileInputStream(staged).use { input ->
                    session.openWrite("base.apk", 0L, staged.length()).use { output ->
                        log("self_update_write_start", "session=$sessionId bytes=${staged.length()}")
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        session.fsync(output)
                    }
                }
                log("self_update_write_complete", "session=$sessionId bytes=${staged.length()}")
                val final = commitAndAwait(context, installer, session, sessionId)
                val duration = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                val result = final.copy(
                    sessionId = sessionId,
                    versionCode = newVersion,
                    apkSize = staged.length(),
                    durationMs = duration
                )
                if (result.ok) {
                    log("self_update_install_success", "session=$sessionId versionCode=$newVersion durationMs=$duration")
                } else {
                    log("self_update_install_failure", "session=$sessionId code=${result.code} durationMs=$duration message=${result.message.take(240)}")
                }
                return result
            }
        } catch (error: Throwable) {
            if (sessionId >= 0 && installer != null) {
                runCatching { installer.abandonSession(sessionId) }
            }
            val code = when (error) {
                is SecurityException -> "PERMISSION_APPROVAL_FAILED"
                else -> "INSTALL_FAILED"
            }
            return failure(code, "${error.javaClass.simpleName}: ${error.message.orEmpty()}", started, sessionId)
        } finally {
            activeSessionId.compareAndSet(sessionId, -1)
            activeInstaller.compareAndSet(installer, null)
            runCatching { staged.delete() }
        }
    }

    fun abandonActiveSession() {
        val id = activeSessionId.getAndSet(-1)
        val installer = activeInstaller.getAndSet(null)
        if (id >= 0 && installer != null) {
            runCatching { installer.abandonSession(id) }
            log("self_update_session_abandoned", "session=$id reason=engine_shutdown")
        }
    }

    private fun commitAndAwait(
        context: Context,
        installer: PackageInstaller,
        session: PackageInstaller.Session,
        sessionId: Int
    ): Result {
        val action = "$TARGET_PACKAGE.SELF_UPDATE_RESULT.${Process.myPid()}.${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        val finalStatus = AtomicInteger(Int.MIN_VALUE)
        val finalMessage = AtomicReference("")
        val approvalAttempt = AtomicInteger(0)
        val approvalElapsed = AtomicReference(-1L)
        val committedAt = SystemClock.elapsedRealtime()
        val finished = AtomicBoolean(false)
        val handlerThread = HandlerThread("luonnotar-self-update-result").apply { start() }
        val handler = Handler(handlerThread.looper)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                    ?: Int.MIN_VALUE
                val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    log("self_update_permission_pending", "session=$sessionId message=${message.take(220)}")
                    approvePermission(installer, sessionId, approvalAttempt, approvalElapsed, committedAt)
                    return
                }
                finalStatus.set(status)
                finalMessage.set(message)
                finished.set(true)
                latch.countDown()
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter, null, handler)
        }
        try {
            val intent = Intent(action).setPackage(SHELL_PACKAGE)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            log("self_update_commit", "session=$sessionId")
            session.commit(pendingIntent.intentSender)

            // OriginOS can delay the pending-user-action callback. Use a short,
            // bounded approval schedule; successful approval stops further attempts.
            val delays = longArrayOf(10L, 20L, 50L, 100L, 200L, 400L)
            var cumulative = 0L
            delays.forEach { delay ->
                cumulative += delay
                handler.postDelayed({
                    if (!finished.get() && approvalElapsed.get() < 0L) {
                        approvePermission(
                            installer,
                            sessionId,
                            approvalAttempt,
                            approvalElapsed,
                            committedAt
                        )
                    }
                }, cumulative)
            }

            if (!latch.await(FINAL_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                runCatching { installer.abandonSession(sessionId) }
                return Result(false, "TIMEOUT", "PackageInstaller final result timeout")
            }
            val mapped = mapStatus(finalStatus.get())
            return Result(
                ok = finalStatus.get() == PackageInstaller.STATUS_SUCCESS,
                code = mapped,
                message = finalMessage.get(),
                permissionApprovalAttempt = approvalAttempt.get(),
                permissionApprovalElapsed = approvalElapsed.get()
            )
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            handlerThread.quitSafely()
        }
    }

    private fun approvePermission(
        installer: PackageInstaller,
        sessionId: Int,
        attemptCounter: AtomicInteger,
        approvedElapsed: AtomicReference<Long>,
        committedAt: Long
    ) {
        if (approvedElapsed.get() >= 0L) return
        val attempt = attemptCounter.incrementAndGet()
        val result = runCatching {
            // Hidden/SystemApi on the public PackageInstaller class. The shell UID
            // owns INSTALL_PACKAGES on the target OriginOS build.
            val method = PackageInstaller::class.java.getDeclaredMethod(
                "setPermissionsResult",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }
            method.invoke(installer, sessionId, true)
        }
        if (result.isSuccess) {
            val elapsed = (SystemClock.elapsedRealtime() - committedAt).coerceAtLeast(0L)
            if (approvedElapsed.compareAndSet(-1L, elapsed)) {
                log("self_update_permission_approved", "session=$sessionId attempt=$attempt elapsedMs=$elapsed")
            }
        } else {
            val error = result.exceptionOrNull()
            log(
                "self_update_permission_retry",
                "session=$sessionId attempt=$attempt error=${error?.javaClass?.simpleName}:${error?.message.orEmpty().take(180)}"
            )
        }
    }

    private fun shellContext(): Context {
        shellContextCache.get()?.let { return it }
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getDeclaredMethod("systemMain").apply {
            isAccessible = true
        }.invoke(null)
        val systemContext = activityThread.getDeclaredMethod("getSystemContext").apply {
            isAccessible = true
        }.invoke(systemMain) as Context
        val shell = systemContext.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        shellContextCache.compareAndSet(null, shell)
        return shellContextCache.get() ?: shell
    }

    private fun ensureStagingDirectory(dir: File) {
        if (!dir.exists()) check(dir.mkdirs()) { "unable to create staging root" }
        require(dir.isDirectory) { "staging root is not a directory" }
        runCatching { Os.chmod(dir.absolutePath, 0x1C0) } // 0700
    }

    private fun snapshotSource(path: String, root: File): File {
        require(EmbeddedSelfUpdatePolicy.pathLooksAllowed(path)) {
            "APK path outside self-update staging root"
        }
        val source = File(path)
        val canonicalRoot = root.canonicalFile
        require(source.parentFile?.canonicalFile == canonicalRoot) {
            "APK parent outside self-update staging root"
        }
        val fd = Os.open(
            source.absolutePath,
            OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW,
            0
        )
        try {
            val stat = Os.fstat(fd)
            require((stat.st_mode and OsConstants.S_IFMT) == OsConstants.S_IFREG) {
                "APK is not a regular file"
            }
            val size = stat.st_size
            require(size in 1..MAX_APK_BYTES) { "APK size out of range" }
            val staged = File(canonicalRoot, ".snapshot-${Process.myPid()}-${SystemClock.elapsedRealtime()}.apk")
            FileInputStream(fd).use { input ->
                FileOutputStream(staged).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                    output.fd.sync()
                }
            }
            require(staged.length() == size) { "snapshot size mismatch" }
            runCatching { Os.chmod(staged.absolutePath, 0x180) } // 0600
            return staged
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                TARGET_PACKAGE,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(TARGET_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun validateArchive(
        archive: PackageInfo,
        installed: PackageInfo,
        apkSize: Long
    ): Pair<String, String>? {
        val decision = EmbeddedSelfUpdatePolicy.validate(
            packageName = archive.packageName,
            candidateVersionCode = archive.longVersionCode,
            installedVersionCode = installed.longVersionCode,
            candidateSignerDigests = signerDigests(archive),
            installedSignerDigests = signerDigests(installed),
            apkSize = apkSize
        )
        return if (decision.allowed) null else decision.code to decision.message
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val signers = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners
        } else {
            signing.signingCertificateHistory
        }
        return signers.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun mapStatus(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
        PackageInstaller.STATUS_FAILURE -> "FAILURE"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "FAILURE_ABORTED"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "FAILURE_BLOCKED"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "FAILURE_CONFLICT"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "FAILURE_INCOMPATIBLE"
        PackageInstaller.STATUS_FAILURE_INVALID -> "FAILURE_INVALID"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "FAILURE_STORAGE"
        else -> "UNKNOWN"
    }

    private fun failure(
        code: String,
        message: String,
        started: Long,
        sessionId: Int = -1
    ): Result = Result(
        ok = false,
        code = code,
        message = message,
        sessionId = sessionId,
        durationMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
    )

    private fun safePathForLog(path: String): String =
        if (path.startsWith("$STAGING_ROOT/")) File(path).name else "rejected_outside_staging"

    private fun log(type: String, detail: String) {
        println("$type $detail")
    }
}
