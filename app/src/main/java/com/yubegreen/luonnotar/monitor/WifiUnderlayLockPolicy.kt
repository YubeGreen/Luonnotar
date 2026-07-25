package com.yubegreen.luonnotar.monitor

data class WifiUnderlayHistory(
    val lastExplicitUnderlay: String = "NONE",
    val lastWifiSeenElapsed: Long = 0L,
    val unknownSinceElapsed: Long = 0L
)

data class WifiUnderlayLockDecision(
    val shouldHoldLock: Boolean,
    val history: WifiUnderlayHistory,
    val reason: String,
    val unknownDurationMs: Long
)

object WifiUnderlayLockPolicy {
    const val WIFI_UNKNOWN_GRACE_MS = 120_000L
    private val EXPLICIT_UNDERLAYS =
        setOf("NONE", "WIFI", "CELLULAR", "ETHERNET")

    fun restoreHistory(
        storedHistory: WifiUnderlayHistory,
        storedBootId: String?,
        runtimeBootId: String?,
        currentBootId: String?,
        nowElapsed: Long
    ): WifiUnderlayHistory {
        if (
            currentBootId.isNullOrBlank() ||
            currentBootId == "unavailable" ||
            storedBootId != currentBootId ||
            runtimeBootId != currentBootId
        ) {
            return WifiUnderlayHistory()
        }
        return WifiUnderlayHistory(
            lastExplicitUnderlay =
                storedHistory.lastExplicitUnderlay.takeIf {
                    it in EXPLICIT_UNDERLAYS
                } ?: "NONE",
            lastWifiSeenElapsed =
                storedHistory.lastWifiSeenElapsed.takeIf {
                    it > 0L && it <= nowElapsed
                } ?: 0L,
            unknownSinceElapsed =
                storedHistory.unknownSinceElapsed.takeIf {
                    it > 0L && it <= nowElapsed
                } ?: 0L
        )
    }

    fun decide(
        guardianActive: Boolean,
        observedUnderlay: String,
        nowElapsed: Long,
        lockCurrentlyHeld: Boolean,
        history: WifiUnderlayHistory
    ): WifiUnderlayLockDecision {
        if (!guardianActive) {
            return WifiUnderlayLockDecision(
                shouldHoldLock = false,
                history = history.copy(unknownSinceElapsed = 0L),
                reason = "guardian_inactive",
                unknownDurationMs = 0L
            )
        }
        return when (observedUnderlay) {
            "WIFI" -> WifiUnderlayLockDecision(
                shouldHoldLock = true,
                history = WifiUnderlayHistory(
                    lastExplicitUnderlay = "WIFI",
                    lastWifiSeenElapsed = nowElapsed,
                    unknownSinceElapsed = 0L
                ),
                reason = "explicit_wifi",
                unknownDurationMs = 0L
            )
            "CELLULAR", "ETHERNET" -> WifiUnderlayLockDecision(
                shouldHoldLock = false,
                history = WifiUnderlayHistory(
                    lastExplicitUnderlay = observedUnderlay,
                    lastWifiSeenElapsed = 0L,
                    unknownSinceElapsed = 0L
                ),
                reason = "explicit_${observedUnderlay.lowercase()}",
                unknownDurationMs = 0L
            )
            else -> {
                val unknownSince = history.unknownSinceElapsed.takeIf { it > 0L }
                    ?: nowElapsed
                val unknownDuration = (nowElapsed - unknownSince).coerceAtLeast(0L)
                val recentWifi =
                    history.lastExplicitUnderlay == "WIFI" &&
                        history.lastWifiSeenElapsed > 0L &&
                        nowElapsed - history.lastWifiSeenElapsed <= WIFI_UNKNOWN_GRACE_MS
                WifiUnderlayLockDecision(
                    shouldHoldLock = lockCurrentlyHeld || recentWifi,
                    history = history.copy(unknownSinceElapsed = unknownSince),
                    reason = when {
                        lockCurrentlyHeld -> "unknown_keep_existing_lock"
                        recentWifi -> "unknown_recent_wifi_grace"
                        else -> "unknown_without_wifi_evidence"
                    },
                    unknownDurationMs = unknownDuration
                )
            }
        }
    }
}
