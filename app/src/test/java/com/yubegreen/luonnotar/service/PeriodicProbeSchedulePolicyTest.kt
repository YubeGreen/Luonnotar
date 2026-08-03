package com.yubegreen.luonnotar.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeriodicProbeSchedulePolicyTest {
    @Test
    fun newlyEnabledTransportsDoNotLetDnsStarveHttps() {
        assertEquals(
            PeriodicProbeKind.HTTPS,
            PeriodicProbeSchedulePolicy.selectMostOverdue(
                nowElapsed = 600_000L,
                intervalMs = 300_000L,
                lastDnsAttemptElapsed = 0L,
                lastHttpsAttemptElapsed = 0L,
                lastMtalkAttemptElapsed = 0L,
                dnsEnabled = true,
                httpsEnabled = true,
                mtalkEnabled = true
            )
        )
    }

    @Test
    fun zeroTimestampTieRotatesAfterEachTransportRuns() {
        val now = 600_000L
        val interval = 300_000L
        assertEquals(
            PeriodicProbeKind.MTALK,
            PeriodicProbeSchedulePolicy.selectMostOverdue(
                nowElapsed = now,
                intervalMs = interval,
                lastDnsAttemptElapsed = 0L,
                lastHttpsAttemptElapsed = now,
                lastMtalkAttemptElapsed = 0L,
                dnsEnabled = true,
                httpsEnabled = true,
                mtalkEnabled = true
            )
        )
        assertEquals(
            PeriodicProbeKind.DNS,
            PeriodicProbeSchedulePolicy.selectMostOverdue(
                nowElapsed = now,
                intervalMs = interval,
                lastDnsAttemptElapsed = 0L,
                lastHttpsAttemptElapsed = now,
                lastMtalkAttemptElapsed = now,
                dnsEnabled = true,
                httpsEnabled = true,
                mtalkEnabled = true
            )
        )
    }

    @Test
    fun selectsTransportWithLargestOverdueAge() {
        assertEquals(
            PeriodicProbeKind.DNS,
            PeriodicProbeSchedulePolicy.selectMostOverdue(
                nowElapsed = 1_000_000L,
                intervalMs = 300_000L,
                lastDnsAttemptElapsed = 100_000L,
                lastHttpsAttemptElapsed = 500_000L,
                lastMtalkAttemptElapsed = 650_000L,
                dnsEnabled = true,
                httpsEnabled = true,
                mtalkEnabled = true
            )
        )
    }

    @Test
    fun returnsNullWhenNothingIsDue() {
        assertNull(
            PeriodicProbeSchedulePolicy.selectMostOverdue(
                nowElapsed = 1_000_000L,
                intervalMs = 300_000L,
                lastDnsAttemptElapsed = 900_000L,
                lastHttpsAttemptElapsed = 900_000L,
                lastMtalkAttemptElapsed = 900_000L,
                dnsEnabled = true,
                httpsEnabled = true,
                mtalkEnabled = true
            )
        )
    }
}
