package com.yubegreen.luonnotar.privileged.embedded

import java.io.File

internal object EmbeddedSelfUpdatePolicy {
    const val TARGET_PACKAGE = "com.yubegreen.luonnotar"
    const val STAGING_ROOT = "/data/local/tmp/luonnotar-self-update"
    const val MAX_APK_BYTES = 256L * 1024L * 1024L

    data class Decision(val allowed: Boolean, val code: String, val message: String)

    fun validate(
        packageName: String,
        candidateVersionCode: Long,
        installedVersionCode: Long,
        candidateSignerDigests: Set<String>,
        installedSignerDigests: Set<String>,
        apkSize: Long
    ): Decision {
        if (packageName != TARGET_PACKAGE) {
            return Decision(false, "REJECT_PACKAGE_MISMATCH", "package=$packageName")
        }
        if (candidateVersionCode <= installedVersionCode) {
            return Decision(
                false,
                "REJECT_VERSION_DOWNGRADE",
                "installed=$installedVersionCode candidate=$candidateVersionCode"
            )
        }
        if (apkSize !in 1..MAX_APK_BYTES) {
            return Decision(false, "APK_INVALID", "size=$apkSize")
        }
        if (candidateSignerDigests.isEmpty() || candidateSignerDigests != installedSignerDigests) {
            return Decision(false, "REJECT_SIGNATURE_MISMATCH", "signing certificate mismatch")
        }
        return Decision(true, "OK", "")
    }

    fun pathLooksAllowed(path: String): Boolean =
        path.startsWith("$STAGING_ROOT/") &&
            path.endsWith(".apk", ignoreCase = true) &&
            File(path).name.isNotBlank()
}
