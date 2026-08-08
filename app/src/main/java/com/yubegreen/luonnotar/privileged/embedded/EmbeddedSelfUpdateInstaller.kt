package com.yubegreen.luonnotar.privileged.embedded

import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Narrow shell-UID self-update path.
 *
 * r270 deliberately does not manufacture an ActivityThread/Context. The engine
 * already runs as uid 2000, so it talks to package services through their Binder
 * interfaces and only uses the public PackageInstaller session wrapper for the
 * streaming/session surface. The result receiver is a tiny in-process
 * IIntentSender Binder endpoint; no Activity, PendingIntent or BroadcastReceiver
 * is required.
 *
 * Security boundary remains intentionally narrow: only a newer Luonnotar APK,
 * with the same signing lineage, from the dedicated staging directory can reach
 * PackageInstaller. This is not a generic APK installer.
 */
internal object EmbeddedSelfUpdateInstaller {
    const val TARGET_PACKAGE = EmbeddedSelfUpdatePolicy.TARGET_PACKAGE
    const val STAGING_ROOT = EmbeddedSelfUpdatePolicy.STAGING_ROOT
    const val MAX_APK_BYTES = EmbeddedSelfUpdatePolicy.MAX_APK_BYTES

    private const val SHELL_PACKAGE = "com.android.shell"
    private const val SHELL_UID = 2000
    private const val FINAL_RESULT_TIMEOUT_MS = 45_000L
    private const val IINTENT_SENDER_DESCRIPTOR = "android.content.IIntentSender"
    private const val IINTENT_SENDER_SEND_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION
    private val permissionApprovalScheduleMs = longArrayOf(
        5L, 10L, 20L, 40L, 80L, 160L, 320L, 640L, 1_000L, 1_500L
    )

    private val activeSessionId = AtomicInteger(-1)
    private val activeInstaller = AtomicReference<PackageInstaller?>(null)
    private val frameworkServicesCache = AtomicReference<FrameworkServices?>(null)

    private data class FrameworkServices(
        val packageManagerBinder: Any,
        val packageInstallerBinder: Any,
        val installer: PackageInstaller,
        val userId: Int
    )

    private data class CandidateArchive(
        val packageName: String,
        val versionCode: Long,
        val signerDigests: Set<String>
    )

    private data class SessionPayloadReadback(
        val verified: Boolean,
        val failureCode: String,
        val stage: String,
        val names: List<String>,
        val bytes: Long,
        val sha256: String,
        val magic: String,
        val expectedBytes: Long,
        val expectedSha256: String,
        val expectedMagic: String,
        val diagnostic: String,
        val reason: String
    )

    data class Result(
        val ok: Boolean,
        val code: String,
        val message: String,
        val sessionId: Int = -1,
        val versionCode: Long = -1L,
        val apkSize: Long = -1L,
        val durationMs: Long = 0L,
        val permissionApprovalAttempt: Int = 0,
        val permissionApprovalElapsed: Long = -1L,
        val sessionReadbackVerified: Boolean = false,
        val sessionReadbackBytes: Long = -1L,
        val sessionReadbackSha256: String = "",
        val sourceSha256: String = "",
        val sessionReadbackMagic: String = "",
        val sourceMagic: String = "",
        val sessionEntryNames: String = "",
        val sessionReadbackStage: String = "",
        val sessionReadbackDiagnostic: String = ""
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
            .put("sessionReadbackVerified", sessionReadbackVerified)
            .put("sessionReadbackBytes", sessionReadbackBytes)
            .put("sessionReadbackSha256", sessionReadbackSha256)
            .put("sourceSha256", sourceSha256)
            .put("sessionReadbackMagic", sessionReadbackMagic)
            .put("sourceMagic", sourceMagic)
            .put("sessionEntryNames", sessionEntryNames)
            .put("sessionReadbackStage", sessionReadbackStage)
            .put("sessionReadbackDiagnostic", sessionReadbackDiagnostic.take(800))
            .toString()
    }

    fun install(apkPath: String): Result {
        val started = SystemClock.elapsedRealtime()
        require(Process.myUid() == SHELL_UID || Process.myUid() == 0) {
            "self update requires shell/root uid"
        }
        log("self_update_request", "path=${safePathForLog(apkPath)} backend=binder_native")

        val services = runCatching { frameworkServices() }.getOrElse { error ->
            return failure(
                "SILENT_UPDATE_UNSUPPORTED",
                "package Binder services unavailable: ${rootCauseSummary(error)}",
                started
            )
        }

        val stagingDir = File(STAGING_ROOT)
        ensureStagingDirectory(stagingDir)
        val staged = snapshotSource(apkPath, stagingDir)
        var sessionId = -1
        var installer: PackageInstaller? = null
        try {
            log("self_update_validation_start", "staged=${staged.name} bytes=${staged.length()} backend=binder_native")
            val archive = parseCandidateArchive(staged)
                ?: return failure("APK_INVALID", "unable to parse staged APK", started)
            val installed = installedInfo(services)
            val validation = validateArchive(archive, installed, staged.length())
            if (validation != null) return failure(validation.first, validation.second, started)
            val newVersion = archive.versionCode
            val sourceSha256 = sha256File(staged)
            val sourceMagic = fileMagic(staged)
            log(
                "self_update_validation_success",
                "versionCode=$newVersion bytes=${staged.length()} signers=${archive.signerDigests.size} " +
                    "sha256=$sourceSha256 magic=$sourceMagic"
            )

            installer = services.installer
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(TARGET_PACKAGE)
                if (Build.VERSION.SDK_INT >= 31) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            sessionId = installer.createSession(params)
            activeInstaller.set(installer)
            activeSessionId.set(sessionId)
            log("self_update_session_created", "session=$sessionId versionCode=$newVersion backend=binder_native")

            installer.openSession(sessionId).use { writeSession ->
                FileInputStream(staged).use { input ->
                    writeSession.openWrite("base.apk", 0L, staged.length()).use { output ->
                        log("self_update_write_start", "session=$sessionId bytes=${staged.length()}")
                        input.copyTo(output, DEFAULT_BUFFER_SIZE)
                        writeSession.fsync(output)
                    }
                }
                log("self_update_write_complete", "session=$sessionId bytes=${staged.length()}")
            }
            log("self_update_session_closed_after_write", "session=$sessionId")

            // OriginOS experiment: the Session proxy used for openWrite/fsync can still answer
            // getNames() yet return DeadObjectException specifically from openRead(). Reacquire
            // the session Binder after the write phase so verification and commit use a fresh
            // IPackageInstallerSession proxy. No permission/commit policy is changed here.
            installer.openSession(sessionId).use { session ->
                log("self_update_session_reopened_after_write", "session=$sessionId")
                val readback = verifySessionPayload(
                    session = session,
                    installer = services.installer,
                    sessionId = sessionId,
                    expectedBytes = staged.length(),
                    expectedSha256 = sourceSha256,
                    expectedMagic = sourceMagic
                )
                val readbackNames = readback.names.joinToString(",")
                if (!readback.verified) {
                    log(
                        "self_update_session_readback_failed",
                        "session=$sessionId names=$readbackNames bytes=${readback.bytes}/${readback.expectedBytes} " +
                            "sha256=${readback.sha256}/${readback.expectedSha256} " +
                            "magic=${readback.magic}/${readback.expectedMagic} reason=${readback.reason.take(220)}"
                    )
                    runCatching { services.installer.abandonSession(sessionId) }
                    return Result(
                        ok = false,
                        code = readback.failureCode.ifBlank { "SESSION_READBACK_MISMATCH" },
                        message = readback.reason,
                        sessionId = sessionId,
                        versionCode = newVersion,
                        apkSize = staged.length(),
                        durationMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L),
                        sessionReadbackVerified = false,
                        sessionReadbackBytes = readback.bytes,
                        sessionReadbackSha256 = readback.sha256,
                        sourceSha256 = readback.expectedSha256,
                        sessionReadbackMagic = readback.magic,
                        sourceMagic = readback.expectedMagic,
                        sessionEntryNames = readbackNames,
                        sessionReadbackStage = readback.stage,
                        sessionReadbackDiagnostic = readback.diagnostic
                    )
                }
                log(
                    "self_update_session_readback_verified",
                    "session=$sessionId names=$readbackNames bytes=${readback.bytes} " +
                        "sha256=${readback.sha256} magic=${readback.magic}"
                )

                val final = commitAndAwait(services, session, sessionId)
                val duration = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                val result = final.copy(
                    sessionId = sessionId,
                    versionCode = newVersion,
                    apkSize = staged.length(),
                    durationMs = duration,
                    sessionReadbackVerified = true,
                    sessionReadbackBytes = readback.bytes,
                    sessionReadbackSha256 = readback.sha256,
                    sourceSha256 = readback.expectedSha256,
                    sessionReadbackMagic = readback.magic,
                    sourceMagic = readback.expectedMagic,
                    sessionEntryNames = readbackNames,
                    sessionReadbackStage = readback.stage,
                    sessionReadbackDiagnostic = readback.diagnostic
                )
                if (result.ok) {
                    val installedAfter = runCatching { installedInfo(services) }.getOrNull()
                    val installedAfterVersion = installedAfter?.let(::versionCodeOf) ?: -1L
                    if (installedAfterVersion >= newVersion) {
                        log(
                            "self_update_install_success",
                            "session=$sessionId versionCode=$newVersion installedVersion=$installedAfterVersion durationMs=$duration"
                        )
                    } else {
                        log(
                            "self_update_install_success_unverified",
                            "session=$sessionId candidateVersion=$newVersion installedVersion=$installedAfterVersion durationMs=$duration"
                        )
                    }
                } else {
                    log(
                        "self_update_install_failure",
                        "session=$sessionId code=${result.code} durationMs=$duration message=${result.message.take(240)}"
                    )
                }
                return result
            }
        } catch (error: Throwable) {
            if (sessionId >= 0 && installer != null) {
                runCatching { installer.abandonSession(sessionId) }
            }
            val root = unwrapInvocation(error)
            val code = when (root) {
                is SecurityException -> "PERMISSION_APPROVAL_FAILED"
                else -> "INSTALL_FAILED"
            }
            return failure(code, rootCauseSummary(error), started, sessionId)
        } finally {
            activeSessionId.compareAndSet(sessionId, -1)
            activeInstaller.compareAndSet(installer, null)
            runCatching { staged.delete() }
        }
    }

    private fun verifySessionPayload(
        session: PackageInstaller.Session,
        installer: PackageInstaller,
        sessionId: Int,
        expectedBytes: Long,
        expectedSha256: String,
        expectedMagic: String
    ): SessionPayloadReadback {
        var names = emptyList<String>()
        var total = 0L
        var sha256 = ""
        var magic = ""

        fun failed(stage: String, code: String, error: Throwable): SessionPayloadReadback {
            val root = rootCauseSummary(error)
            log(
                "self_update_session_readback_stage",
                "session=$sessionId stage=${stage}_failed error=${root.take(260)}"
            )
            val diagnostic = probeSessionAfterReadbackFailure(installer, sessionId)
            log(
                "self_update_session_readback_probe",
                "session=$sessionId failedStage=$stage $diagnostic"
            )
            return SessionPayloadReadback(
                verified = false,
                failureCode = code,
                stage = stage,
                names = names,
                bytes = total.takeIf { it > 0L } ?: -1L,
                sha256 = sha256,
                magic = magic,
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
                expectedMagic = expectedMagic,
                diagnostic = diagnostic,
                reason = "session readback failed at $stage: $root; $diagnostic"
            )
        }

        log("self_update_session_readback_stage", "session=$sessionId stage=get_names_before")
        names = try {
            session.names.toList().sorted()
        } catch (error: Throwable) {
            return failed("get_names", "SESSION_READBACK_GET_NAMES_FAILED", error)
        }
        val namesText = names.joinToString(",")
        log(
            "self_update_session_readback_stage",
            "session=$sessionId stage=get_names_ok names=$namesText"
        )
        if ("base.apk" !in names) {
            return SessionPayloadReadback(
                verified = false,
                failureCode = "SESSION_READBACK_BASE_MISSING",
                stage = "get_names",
                names = names,
                bytes = -1L,
                sha256 = "",
                magic = "",
                expectedBytes = expectedBytes,
                expectedSha256 = expectedSha256,
                expectedMagic = expectedMagic,
                diagnostic = "sessionInfo=${sessionInfoProbe(installer, sessionId)}",
                reason = "PackageInstaller session does not contain base.apk"
            )
        }

        log("self_update_session_readback_stage", "session=$sessionId stage=open_read_before")
        val input = try {
            session.openRead("base.apk")
        } catch (error: Throwable) {
            return failed("open_read", "SESSION_READBACK_OPEN_READ_FAILED", error)
        }
        log("self_update_session_readback_stage", "session=$sessionId stage=open_read_ok")

        input.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val firstBytes = ByteArray(4)

            log("self_update_session_readback_stage", "session=$sessionId stage=first_read_before")
            val firstCount = try {
                stream.read(firstBytes)
            } catch (error: Throwable) {
                return failed("first_read", "SESSION_READBACK_FIRST_READ_FAILED", error)
            }
            if (firstCount > 0) {
                digest.update(firstBytes, 0, firstCount)
                total += firstCount.toLong()
                magic = firstBytes.copyOf(firstCount).toHex()
            } else {
                magic = ""
            }
            log(
                "self_update_session_readback_stage",
                "session=$sessionId stage=first_read_ok count=$firstCount magic=$magic"
            )

            log("self_update_session_readback_stage", "session=$sessionId stage=full_digest_before")
            try {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    total += read.toLong()
                }
            } catch (error: Throwable) {
                return failed("full_digest", "SESSION_READBACK_FULL_DIGEST_FAILED", error)
            }
            sha256 = digest.digest().toHex()
            log(
                "self_update_session_readback_stage",
                "session=$sessionId stage=full_digest_ok bytes=$total sha256=$sha256"
            )
        }

        val problems = buildList {
            if (total != expectedBytes) add("byte count mismatch $total != $expectedBytes")
            if (sha256 != expectedSha256) add("sha256 mismatch")
            if (magic != expectedMagic) add("file magic mismatch $magic != $expectedMagic")
        }
        return SessionPayloadReadback(
            verified = problems.isEmpty(),
            failureCode = if (problems.isEmpty()) "" else "SESSION_READBACK_CONTENT_MISMATCH",
            stage = if (problems.isEmpty()) "verified" else "content_compare",
            names = names,
            bytes = total,
            sha256 = sha256,
            magic = magic,
            expectedBytes = expectedBytes,
            expectedSha256 = expectedSha256,
            expectedMagic = expectedMagic,
            diagnostic = "sessionInfo=${sessionInfoProbe(installer, sessionId)}",
            reason = problems.joinToString("; ").ifBlank { "verified" }
        )
    }

    private fun probeSessionAfterReadbackFailure(
        installer: PackageInstaller,
        sessionId: Int
    ): String {
        val parts = mutableListOf<String>()
        parts += "sessionInfo=${sessionInfoProbe(installer, sessionId)}"

        val reopen = runCatching { installer.openSession(sessionId) }
        val reopened = reopen.getOrNull()
        if (reopened == null) {
            parts += "reopen=failed:${rootCauseSummary(reopen.exceptionOrNull()!!).take(180)}"
            return parts.joinToString(";")
        }

        parts += "reopen=ok"
        reopened.use { probe ->
            val namesProbe = runCatching { probe.names.toList().sorted().joinToString(",") }
            val probedNames = namesProbe.getOrNull()
            if (probedNames != null) {
                parts += "reopenNames=ok:$probedNames"
            } else {
                parts += "reopenNames=failed:${rootCauseSummary(namesProbe.exceptionOrNull()!!).take(180)}"
            }
        }
        return parts.joinToString(";")
    }

    private fun sessionInfoProbe(installer: PackageInstaller, sessionId: Int): String =
        runCatching {
            if (installer.getSessionInfo(sessionId) == null) "missing" else "present"
        }.getOrElse { error -> "failed:${rootCauseSummary(error).take(180)}" }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun fileMagic(file: File): String {
        val bytes = ByteArray(4)
        val count = FileInputStream(file).use { input -> input.read(bytes) }.coerceAtLeast(0)
        return bytes.copyOf(count).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    fun abandonActiveSession() {
        val id = activeSessionId.getAndSet(-1)
        val installer = activeInstaller.getAndSet(null)
        if (id >= 0 && installer != null) {
            runCatching { installer.abandonSession(id) }
            log("self_update_session_abandoned", "session=$id reason=engine_shutdown")
        }
    }

    private fun commitAndAwait(
        services: FrameworkServices,
        session: PackageInstaller.Session,
        sessionId: Int
    ): Result {
        val latch = CountDownLatch(1)
        val finalStatus = AtomicInteger(Int.MIN_VALUE)
        val finalMessage = AtomicReference("")
        val approvalAttempt = AtomicInteger(0)
        val approvalElapsed = AtomicLong(-1L)
        val committedAt = SystemClock.elapsedRealtime()
        val finished = AtomicBoolean(false)
        val pendingSeen = AtomicBoolean(false)

        fun finish(status: Int, message: String) {
            if (finished.compareAndSet(false, true)) {
                finalStatus.set(status)
                finalMessage.set(message)
                latch.countDown()
            }
        }

        val resultBinder = ResultIntentSenderBinder(
            onIntent = { intent ->
                val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                    ?: Int.MIN_VALUE
                val message = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    pendingSeen.set(true)
                    log("self_update_permission_pending", "session=$sessionId message=${message.take(220)}")
                    approvePermission(
                        services,
                        sessionId,
                        approvalAttempt,
                        approvalElapsed,
                        committedAt,
                        source = "pending_callback"
                    )
                } else {
                    finish(status, message)
                }
            },
            onProtocolFailure = { error ->
                val message = "result Binder callback failed: ${rootCauseSummary(error)}"
                log("self_update_result_callback_error", "session=$sessionId message=${message.take(260)}")
                finish(PackageInstaller.STATUS_FAILURE, message)
            }
        )
        val statusReceiver = intentSenderForBinder(resultBinder)
        val approvalExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "luonnotar-self-update-approval").apply { isDaemon = true }
        }.apply {
            removeOnCancelPolicy = true
            executeExistingDelayedTasksAfterShutdownPolicy = false
            continueExistingPeriodicTasksAfterShutdownPolicy = false
        }

        try {
            log("self_update_commit", "session=$sessionId callback=binder_native")
            session.commit(statusReceiver)

            // OriginOS may abort an install before the public pending-user-action
            // callback reaches us. Reassert acceptance on a short bounded schedule.
            // A successful early call does not suppress later attempts: some OEM
            // builds accept the Binder call before the session has entered the
            // permission state and otherwise turn that first success into a no-op.
            permissionApprovalScheduleMs.forEach { delayMs ->
                approvalExecutor.schedule({
                    if (!finished.get()) {
                        approvePermission(
                            services,
                            sessionId,
                            approvalAttempt,
                            approvalElapsed,
                            committedAt,
                            source = if (pendingSeen.get()) "scheduled_after_pending" else "scheduled_race"
                        )
                    }
                }, delayMs, TimeUnit.MILLISECONDS)
            }

            if (!latch.await(FINAL_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                runCatching { services.installer.abandonSession(sessionId) }
                return Result(
                    false,
                    "TIMEOUT",
                    "PackageInstaller final result timeout",
                    permissionApprovalAttempt = approvalAttempt.get(),
                    permissionApprovalElapsed = approvalElapsed.get()
                )
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
            approvalExecutor.shutdownNow()
        }
    }

    private fun approvePermission(
        services: FrameworkServices,
        sessionId: Int,
        attemptCounter: AtomicInteger,
        firstAcceptedElapsed: AtomicLong,
        committedAt: Long,
        source: String
    ) {
        val attempt = attemptCounter.incrementAndGet()
        val result = runCatching {
            val method = Class.forName("android.content.pm.IPackageInstaller")
                .getMethod(
                    "setPermissionsResult",
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
            method.invoke(services.packageInstallerBinder, sessionId, true)
        }
        if (result.isSuccess) {
            val elapsed = (SystemClock.elapsedRealtime() - committedAt).coerceAtLeast(0L)
            if (firstAcceptedElapsed.compareAndSet(-1L, elapsed)) {
                log(
                    "self_update_permission_approval_call_accepted",
                    "session=$sessionId attempt=$attempt elapsedMs=$elapsed source=$source"
                )
            } else {
                log(
                    "self_update_permission_approval_reasserted",
                    "session=$sessionId attempt=$attempt elapsedMs=$elapsed source=$source"
                )
            }
        } else {
            log(
                "self_update_permission_retry",
                "session=$sessionId attempt=$attempt source=$source error=${rootCauseSummary(result.exceptionOrNull()).take(220)}"
            )
        }
    }

    private fun frameworkServices(): FrameworkServices {
        frameworkServicesCache.get()?.let { return it }
        val appGlobals = Class.forName("android.app.AppGlobals")
        val packageManagerBinder = appGlobals.getMethod("getPackageManager").invoke(null)
            ?: error("AppGlobals.getPackageManager returned null")
        val packageManagerInterface = Class.forName("android.content.pm.IPackageManager")
        val packageInstallerBinder = packageManagerInterface
            .getMethod("getPackageInstaller")
            .invoke(packageManagerBinder)
            ?: error("IPackageManager.getPackageInstaller returned null")
        val userId = 0 // shell/root privileged engine is bound to Android system user 0
        val installer = constructPackageInstaller(packageInstallerBinder, userId)
        val services = FrameworkServices(
            packageManagerBinder = packageManagerBinder,
            packageInstallerBinder = packageInstallerBinder,
            installer = installer,
            userId = userId
        )
        frameworkServicesCache.compareAndSet(null, services)
        val resolved = frameworkServicesCache.get() ?: services
        log(
            "self_update_binder_services_ready",
            "uid=${Process.myUid()} userId=${resolved.userId} installerPackage=$SHELL_PACKAGE"
        )
        return resolved
    }

    private fun constructPackageInstaller(packageInstallerBinder: Any, userId: Int): PackageInstaller {
        val binderInterface = Class.forName("android.content.pm.IPackageInstaller")
        val constructors = PackageInstaller::class.java.declaredConstructors

        // Android 12+ / current Android 16 shape:
        // PackageInstaller(IPackageInstaller, String, String attributionTag, int userId)
        constructors.firstOrNull { constructor ->
            val types = constructor.parameterTypes
            types.size == 4 &&
                types[0] == binderInterface &&
                types[1] == String::class.java &&
                types[2] == String::class.java &&
                types[3] == Int::class.javaPrimitiveType
        }?.let { constructor ->
            constructor.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return constructor.newInstance(packageInstallerBinder, SHELL_PACKAGE, null, userId) as PackageInstaller
        }

        // Older framework fallback that still needs no Context.
        constructors.firstOrNull { constructor ->
            val types = constructor.parameterTypes
            types.size == 3 &&
                types[0] == binderInterface &&
                types[1] == String::class.java &&
                types[2] == Int::class.javaPrimitiveType
        }?.let { constructor ->
            constructor.isAccessible = true
            return constructor.newInstance(packageInstallerBinder, SHELL_PACKAGE, userId) as PackageInstaller
        }

        error(
            "no context-free PackageInstaller constructor: " +
                constructors.joinToString { it.parameterTypes.joinToString(prefix = "(", postfix = ")") { type -> type.name } }
        )
    }

    private fun installedInfo(services: FrameworkServices): PackageInfo {
        val iface = Class.forName("android.content.pm.IPackageManager")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val modern = runCatching {
            iface.getMethod(
                "getPackageInfo",
                String::class.java,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(
                services.packageManagerBinder,
                TARGET_PACKAGE,
                flags.toLong(),
                services.userId
            ) as? PackageInfo
        }.getOrNull()
        if (modern != null) return modern

        @Suppress("DEPRECATION")
        return iface.getMethod(
            "getPackageInfo",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).invoke(
            services.packageManagerBinder,
            TARGET_PACKAGE,
            legacySigningFlags(),
            services.userId
        ) as? PackageInfo ?: error("installed package not found through IPackageManager")
    }

    private fun parseCandidateArchive(apk: File): CandidateArchive? {
        val parser = Class.forName("android.content.pm.PackageParser")
        val flags = packageParserFlag(parser, "PARSE_MUST_BE_APK", 1) or
            packageParserFlag(parser, "PARSE_COLLECT_CERTIFICATES", 1 shl 5)
        val lite = parser.getMethod(
            "parseApkLite",
            File::class.java,
            Int::class.javaPrimitiveType
        ).invoke(null, apk, flags) ?: return null

        val liteClass = lite.javaClass
        val packageName = liteClass.getField("packageName").get(lite) as? String ?: return null
        val versionCode = liteClass.getMethod("getLongVersionCode").invoke(lite) as? Long ?: return null
        val signingDetails = liteClass.getField("signingDetails").get(lite) ?: return null
        val digests = linkedSetOf<String>()
        signatureArrayField(signingDetails, "signatures").forEach { digests += digestSignature(it) }
        signatureArrayField(signingDetails, "pastSigningCertificates").forEach { digests += digestSignature(it) }
        return CandidateArchive(packageName, versionCode, digests)
    }

    private fun packageParserFlag(parser: Class<*>, name: String, fallback: Int): Int =
        runCatching { parser.getField(name).getInt(null) }.getOrDefault(fallback)

    private fun signatureArrayField(signingDetails: Any, fieldName: String): Array<Signature> {
        val value = runCatching {
            signingDetails.javaClass.getField(fieldName).get(signingDetails)
        }.getOrNull() ?: return emptyArray()
        @Suppress("UNCHECKED_CAST")
        return value as? Array<Signature> ?: emptyArray()
    }

    private fun digestSignature(signature: Signature): String =
        MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private class ResultIntentSenderBinder(
        private val onIntent: (Intent?) -> Unit,
        private val onProtocolFailure: (Throwable) -> Unit
    ) : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(IINTENT_SENDER_DESCRIPTOR)
                return true
            }
            if (code != IINTENT_SENDER_SEND_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            return try {
                data.enforceInterface(IINTENT_SENDER_DESCRIPTOR)
                data.readInt() // result code
                val intent = readIntent(data)
                data.readString() // resolvedType
                data.readStrongBinder() // whitelistToken
                data.readStrongBinder() // finishedReceiver
                data.readString() // requiredPermission
                readBundle(data) // options
                onIntent(intent)
                true
            } catch (error: Throwable) {
                onProtocolFailure(error)
                true
            }
        }

        private fun readIntent(parcel: Parcel): Intent? =
            if (parcel.readInt() != 0) Intent.CREATOR.createFromParcel(parcel) else null

        private fun readBundle(parcel: Parcel): Bundle? =
            if (parcel.readInt() != 0) Bundle.CREATOR.createFromParcel(parcel) else null
    }

    private fun intentSenderForBinder(binder: IBinder): IntentSender {
        val parcel = Parcel.obtain()
        try {
            parcel.writeStrongBinder(binder)
            parcel.setDataPosition(0)
            return requireNotNull(IntentSender.CREATOR.createFromParcel(parcel)) {
                "unable to create binder-native IntentSender"
            }
        } finally {
            parcel.recycle()
        }
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
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,
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

    @Suppress("DEPRECATION")
    private fun legacySigningFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    private fun validateArchive(
        archive: CandidateArchive,
        installed: PackageInfo,
        apkSize: Long
    ): Pair<String, String>? {
        val decision = EmbeddedSelfUpdatePolicy.validate(
            packageName = archive.packageName,
            candidateVersionCode = archive.versionCode,
            installedVersionCode = versionCodeOf(installed),
            candidateSignerDigests = archive.signerDigests,
            installedSignerDigests = signerDigests(installed),
            apkSize = apkSize
        )
        return if (decision.allowed) null else decision.code to decision.message
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptySet()
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                signing.signingCertificateHistory
            }
        } else {
            info.signatures ?: emptyArray()
        }
        return signers.mapTo(linkedSetOf(), ::digestSignature)
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

    private fun unwrapInvocation(error: Throwable): Throwable {
        var current = error
        var depth = 0
        while (depth++ < 6) {
            current = when {
                current is InvocationTargetException && current.targetException != null -> current.targetException
                current.cause != null && current.cause !== current -> current.cause!!
                else -> return current
            }
        }
        return current
    }

    private fun rootCauseSummary(error: Throwable?): String {
        if (error == null) return "unknown"
        val chain = ArrayList<String>(4)
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 4) {
            val unwrapped = if (current is InvocationTargetException && current.targetException != null) {
                current.targetException
            } else {
                current
            }
            val item = buildString {
                append(unwrapped.javaClass.simpleName.ifBlank { unwrapped.javaClass.name })
                val message = unwrapped.message.orEmpty().trim()
                if (message.isNotEmpty()) append(": ").append(message.take(220))
            }
            if (chain.lastOrNull() != item) chain += item
            current = unwrapped.cause?.takeIf { it !== unwrapped }
        }
        return chain.joinToString(" <- ").ifBlank { error.javaClass.name }
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
