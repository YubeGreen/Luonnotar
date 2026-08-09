package com.yubegreen.luonnotar.privileged

/**
 * Shell-side policy for keeping the app-UID -> Termux RUN_COMMAND bridge usable.
 *
 * The actual Termux command is still dispatched from Luonnotar's normal app UID;
 * the UID 2000 guardian only reasserts and verifies the package permission that
 * authorizes that bridge. Termux's own allow-external-apps=true preference remains
 * a Termux-local user choice and is never written through the shell engine.
 */
internal object TermuxRunCommandPermissionPolicy {
    const val LUONNOTAR_PACKAGE = "com.yubegreen.luonnotar"
    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    private val GRANTED_LINE = Regex(
        "(?m)^\\s*" + Regex.escape(RUN_COMMAND_PERMISSION) + ":\\s*granted=true\\b"
    )

    fun permissionGranted(packageDump: String): Boolean =
        GRANTED_LINE.containsMatchIn(packageDump)
}
