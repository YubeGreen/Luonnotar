package com.yubegreen.luonnotar.privileged

/**
 * Pure policy for the non-destructive WhatsApp process rebuild tier.
 *
 * The action remains `am kill`, never `force-stop`. Two independent delivery
 * failures are sufficient in the ordinary path. A single critical C2DM/GCM
 * failure may escalate only after the complete five-pass thaw burst has been
 * verified ineffective.
 */
object DeliveryFailureEscalationPolicy {
    data class Decision(val allowed: Boolean, val reason: String)

    const val EPISODE_DEBOUNCE_MS = 10_000L
    const val EVIDENCE_WINDOW_MS = 10 * 60_000L
    const val MIN_SEPARATION_MS = 15_000L
    const val REBUILD_COOLDOWN_MS = 30 * 60_000L
    const val HISTORY_WINDOW_MS = 24L * 60L * 60L * 1_000L
    const val MAX_REBUILDS_PER_24_HOURS = 2

    fun decide(
        packageName: String,
        nowElapsed: Long,
        deliveryEpisodes: List<Long>,
        lastRebuildElapsed: Long,
        rebuildHistory: List<Long>,
        verifiedFrozenAfterBurst: Boolean = false
    ): Decision {
        if (!isRebuildTarget(packageName)) return Decision(false, "unsupported_package")
        if (nowElapsed < 0L) return Decision(false, "invalid_clock")
        if (lastRebuildElapsed > nowElapsed) return Decision(false, "elapsed_clock_reset")
        if (
            lastRebuildElapsed > 0L &&
            nowElapsed - lastRebuildElapsed < REBUILD_COOLDOWN_MS
        ) {
            return Decision(false, "cooldown")
        }

        val historyCutoff = (nowElapsed - HISTORY_WINDOW_MS).coerceAtLeast(0L)
        val recentRebuilds = rebuildHistory.count { it in historyCutoff..nowElapsed }
        if (recentRebuilds >= MAX_REBUILDS_PER_24_HOURS) {
            return Decision(false, "daily_limit")
        }

        val evidenceCutoff = (nowElapsed - EVIDENCE_WINDOW_MS).coerceAtLeast(0L)
        val evidence = deliveryEpisodes.filter { it in evidenceCutoff..nowElapsed }.sorted()
        if (verifiedFrozenAfterBurst && evidence.isNotEmpty()) {
            return Decision(true, "critical_delivery_failure_after_thaw_exhaustion")
        }
        if (evidence.size < 2) return Decision(false, "insufficient_delivery_failures")
        if (evidence.last() - evidence.first() < MIN_SEPARATION_MS) {
            return Decision(false, "same_delivery_episode")
        }
        return Decision(true, "repeated_c2dm_cancelled")
    }

    fun shouldRecordEpisode(previousElapsed: Long?, nowElapsed: Long): Boolean {
        if (nowElapsed < 0L) return false
        if (previousElapsed == null) return true
        if (previousElapsed > nowElapsed) return true
        return nowElapsed - previousElapsed >= EPISODE_DEBOUNCE_MS
    }

    fun isRebuildTarget(packageName: String): Boolean =
        packageName == "com.whatsapp" || packageName == "com.whatsapp.w4b"
}
