package com.yubegreen.luonnotar.privileged

/**
 * Separates Linux process names from Android package names.
 *
 * `am unfreeze` resolves a package UID and then looks up a process with the
 * supplied name. A secondary process such as `com.google.android.gms.persistent`
 * or `com.whatsapp:account_switching` is therefore not a safe package argument.
 */
object GuardianTargetResolver {
    fun ownerPackage(processName: String, packageTargets: List<String>): String? =
        packageTargets
            .asSequence()
            .filter(GuardianEngineConfig::isSafePackageName)
            .distinct()
            .sortedByDescending(String::length)
            .firstOrNull { packageName ->
                processName == packageName ||
                    processName.startsWith("$packageName:") ||
                    processName.startsWith("$packageName.")
            }
            ?: processName.substringBefore(':')
                .takeIf(GuardianEngineConfig::isSafePackageName)

    /** AOSP `am unfreeze` can safely target only the package's main process. */
    fun canUseActivityManagerUnfreeze(processName: String, ownerPackage: String?): Boolean =
        ownerPackage != null && processName == ownerPackage
}
