package com.yubegreen.luonnotar.privileged.embedded

/** Rejects a responsive socket whose privileged engine stopped publishing state. */
internal object EmbeddedEngineStatusFreshnessPolicy {
    const val MAX_STATUS_AGE_MS = 45_000L

    fun ageMs(nowElapsed: Long, snapshotElapsed: Long): Long =
        if (snapshotElapsed > 0L && nowElapsed >= snapshotElapsed) {
            nowElapsed - snapshotElapsed
        } else {
            Long.MAX_VALUE
        }

    fun isFresh(
        nowElapsed: Long,
        snapshotElapsed: Long,
        maxAgeMs: Long = MAX_STATUS_AGE_MS
    ): Boolean = maxAgeMs >= 0L && ageMs(nowElapsed, snapshotElapsed) <= maxAgeMs
}
