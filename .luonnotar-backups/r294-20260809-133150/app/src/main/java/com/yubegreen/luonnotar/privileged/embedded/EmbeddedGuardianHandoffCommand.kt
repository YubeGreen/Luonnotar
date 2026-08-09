package com.yubegreen.luonnotar.privileged.embedded

/**
 * Builds the detached shell used by an old UID 2000 engine to hand ownership
 * to a new app_process loaded from the currently installed APK.
 *
 * The child waits for the exact old PID/start-time pair to disappear before
 * starting. This avoids racing the kernel singleton file lock and also avoids
 * waiting on an unrelated process if the PID is reused.
 */
internal object EmbeddedGuardianHandoffCommand {
    fun build(
        apkPath: String,
        mainClass: String,
        identity: EmbeddedGuardianStore.EndpointIdentity,
        oldPid: Int,
        oldStartTicks: Long,
        expectedRevision: Int,
        reason: String
    ): String {
        require(apkPath.isNotBlank() && apkPath.startsWith('/')) { "invalid APK path" }
        require(mainClass.matches(CLASS_NAME)) { "invalid main class" }
        require(identity.port in 1024..65535) { "invalid engine port" }
        require(identity.token.matches(TOKEN)) { "invalid engine token" }
        require(oldPid > 1) { "invalid old pid" }
        require(oldStartTicks > 0L) { "invalid old start ticks" }
        require(expectedRevision > 0) { "invalid expected revision" }

        val q = EmbeddedGuardianProtocol::shellQuote
        return buildString {
            append("old_pid=").append(oldPid).append("; ")
            append("old_start=").append(oldStartTicks).append("; ")
            append("wait_i=0; ")
            append("while [ -r /proc/\$old_pid/stat ]; do ")
            append("stat=\$(cat /proc/\$old_pid/stat 2>/dev/null) || break; ")
            append("rest=\${stat#*) }; set -- \$rest; cur_start=\${20:-0}; ")
            append("[ \"\$cur_start\" = \"\$old_start\" ] || break; ")
            append("wait_i=\$((wait_i+1)); [ \"\$wait_i\" -ge 100 ] && exit 75; ")
            append("sleep 0.1; done; ")
            append("sleep 0.1; ")
            append("export CLASSPATH=").append(q(apkPath)).append("; ")
            append("exec /system/bin/app_process /system/bin --nice-name=")
                .append(EmbeddedGuardianStarterCommand.PROCESS_NAME).append(' ')
            append(q(mainClass)).append(" --port ").append(identity.port)
            append(" --token ").append(q(identity.token))
            append(" --reason ").append(q("hot_handoff:$reason:expected_r$expectedRevision"))
        }
    }

    private val TOKEN = Regex("^[a-f0-9]{64}$")
    private val CLASS_NAME = Regex("^[A-Za-z_][A-Za-z0-9_$.]*$")
}
