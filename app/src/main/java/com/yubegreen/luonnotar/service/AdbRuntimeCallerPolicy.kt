package com.yubegreen.luonnotar.service

/** Pure allowlist used by the exported adb runtime ContentProvider. */
internal object AdbRuntimeCallerPolicy {
    fun isAllowed(
        callerUid: Int,
        appUid: Int,
        rootUid: Int,
        systemUid: Int,
        shellUid: Int
    ): Boolean = callerUid == appUid ||
        callerUid == rootUid ||
        callerUid == systemUid ||
        callerUid == shellUid
}
