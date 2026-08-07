package com.yubegreen.luonnotar.privileged.embedded

internal object EmbeddedGuardianStarterCommand {
    const val PROCESS_NAME = "luonnotar_privileged_engine"
    private const val LOG_PATH = "/data/local/tmp/luonnotar-embedded-guardian.log"

    fun build(
        apkPath: String,
        mainClass: String,
        identity: EmbeddedGuardianStore.EndpointIdentity
    ): String {
        require(apkPath.isNotBlank()) { "empty APK path" }
        require(mainClass.matches(CLASS_NAME)) { "invalid main class" }
        require(identity.port in 1024..65535) { "invalid engine port" }
        require(identity.token.matches(TOKEN)) { "invalid engine token" }
        val q = EmbeddedGuardianProtocol::shellQuote
        return buildString {
            // Do not use `pkill -f`: the starter shell itself contains the process name
            // and can accidentally kill its own command line on some Android builds.
            append("old_pid=\$(pidof ").append(PROCESS_NAME).append(" 2>/dev/null || true); ")
            append("if [ -n \"\$old_pid\" ]; then ")
            append("kill \$old_pid >/dev/null 2>&1 || true; ")
            append("for i in 1 2 3 4 5; do ")
            append("[ -z \"\$(pidof ").append(PROCESS_NAME)
                .append(" 2>/dev/null || true)\" ] && break; sleep 0.2; done; ")
            append("left=\$(pidof ").append(PROCESS_NAME).append(" 2>/dev/null || true); ")
            append("[ -n \"\$left\" ] && kill -9 \$left >/dev/null 2>&1 || true; ")
            append("fi; ")
            append("export CLASSPATH=").append(q(apkPath)).append("; ")
            append("(")
            append("exec /system/bin/app_process /system/bin --nice-name=").append(PROCESS_NAME).append(' ')
            append(q(mainClass)).append(" --port ").append(identity.port)
            append(" --token ").append(q(identity.token))
            append(" --reason ").append(q("starter_command"))
            append(" </dev/null >>").append(q(LOG_PATH)).append(" 2>&1")
            append(") & echo \$!")
        }
    }

    private val TOKEN = Regex("^[a-f0-9]{64}$")
    private val CLASS_NAME = Regex("^[A-Za-z_][A-Za-z0-9_$.]*$")
}
