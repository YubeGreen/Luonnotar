package com.yubegreen.luonnotar.service

enum class PeriodicProbeKind {
    DNS,
    HTTPS,
    MTALK
}

/**
 * Selects the most overdue enabled periodic probe.
 *
 * A zero timestamp means the probe has never run and is therefore maximally
 * overdue. Ties intentionally prefer HTTPS, then MTALK, then DNS so that a
 * newly enabled DNS probe cannot starve the other transports forever.
 */
object PeriodicProbeSchedulePolicy {
    fun selectMostOverdue(
        nowElapsed: Long,
        intervalMs: Long,
        lastDnsAttemptElapsed: Long,
        lastHttpsAttemptElapsed: Long,
        lastMtalkAttemptElapsed: Long,
        dnsEnabled: Boolean,
        httpsEnabled: Boolean,
        mtalkEnabled: Boolean
    ): PeriodicProbeKind? {
        val candidates = buildList {
            if (dnsEnabled) add(candidate(PeriodicProbeKind.DNS, lastDnsAttemptElapsed))
            if (httpsEnabled) add(candidate(PeriodicProbeKind.HTTPS, lastHttpsAttemptElapsed))
            if (mtalkEnabled) add(candidate(PeriodicProbeKind.MTALK, lastMtalkAttemptElapsed))
        }.filter { candidate ->
            candidate.lastAttemptElapsed <= 0L ||
                nowElapsed - candidate.lastAttemptElapsed >= intervalMs
        }
        return candidates.maxWithOrNull(
            compareBy<Candidate> { overdueScore(nowElapsed, it.lastAttemptElapsed) }
                .thenBy { tiePriority(it.kind) }
        )?.kind
    }

    private fun candidate(
        kind: PeriodicProbeKind,
        lastAttemptElapsed: Long
    ): Candidate = Candidate(kind, lastAttemptElapsed)

    private fun overdueScore(nowElapsed: Long, lastAttemptElapsed: Long): Long =
        if (lastAttemptElapsed <= 0L) Long.MAX_VALUE else nowElapsed - lastAttemptElapsed

    private fun tiePriority(kind: PeriodicProbeKind): Int = when (kind) {
        PeriodicProbeKind.HTTPS -> 3
        PeriodicProbeKind.MTALK -> 2
        PeriodicProbeKind.DNS -> 1
    }

    private data class Candidate(
        val kind: PeriodicProbeKind,
        val lastAttemptElapsed: Long
    )
}
