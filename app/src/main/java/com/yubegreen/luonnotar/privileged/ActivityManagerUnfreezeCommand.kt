package com.yubegreen.luonnotar.privileged

/** Selects the lowest-overhead ActivityManager shell entry point available. */
internal object ActivityManagerUnfreezeCommand {
    enum class Backend(val statusName: String) {
        CMD_ACTIVITY("cmd_activity"),
        AM("am")
    }

    fun build(
        processName: String,
        sticky: Boolean,
        backend: Backend
    ): List<String> {
        require(GuardianEngineConfig.isSafeProcessName(processName)) {
            "unsafe process name"
        }
        return buildList {
            when (backend) {
                Backend.CMD_ACTIVITY -> addAll(listOf("cmd", "activity", "unfreeze"))
                Backend.AM -> addAll(listOf("am", "unfreeze"))
            }
            if (sticky) add("--sticky")
            add(processName)
            if (backend == Backend.AM) addAll(listOf("--user", "0"))
        }
    }

    fun shell(
        processName: String,
        sticky: Boolean,
        backend: Backend
    ): String = build(processName, sticky, backend).joinToString(" ")


    fun shellWithAmFallback(
        processName: String,
        sticky: Boolean,
        backend: Backend
    ): String {
        val primary = shell(processName, sticky, backend)
        if (backend != Backend.CMD_ACTIVITY) return primary
        val fallback = shell(processName, sticky, Backend.AM)
        return "$primary || $fallback"
    }
}
