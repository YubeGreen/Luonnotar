package com.yubegreen.luonnotar.privileged.embedded

internal object ShellSshGuardianPolicy {
    private val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L, 300_000L)
    private val AUTHORIZED_KEY = Regex(
        "^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)|sk-ssh-ed25519@openssh\\.com)\\s+[A-Za-z0-9+/=]+(?:\\s+.*)?$"
    )

    fun recoveryBackoffMs(consecutiveFailures: Int): Long {
        val index = (consecutiveFailures - 1).coerceIn(0, BACKOFF_MS.lastIndex)
        return BACKOFF_MS[index]
    }

    fun isAuthorizedKey(value: String): Boolean = AUTHORIZED_KEY.matches(value.trim())
}
