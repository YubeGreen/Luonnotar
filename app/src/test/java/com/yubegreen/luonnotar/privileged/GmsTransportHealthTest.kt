package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsTransportHealthTest {
    @Test
    fun parsesDedicatedEstablishedMcsPortsOnly() {
        val raw = """
            ESTAB 0 0 100.117.209.84:47610 142.251.10.188:5228
            CLOSE-WAIT 0 0 100.117.209.84:40000 142.251.10.188:5229
            ESTAB 0 0 [fd7a:115c:a1e0::1]:40001 [2001:4860:4842:400::]:443
            ESTAB 0 0 192.168.1.2:40003 142.251.10.188:443 users:(("com.google.android.gms.persistent",pid=123,fd=9))
            ESTABLISHED 0 0 192.168.1.2:40002 142.251.10.188:5230
        """.trimIndent()

        assertEquals(setOf(443, 5228, 5230), GmsTransportSocketParser.establishedMcsPorts(raw))
    }

    @Test
    fun parsesBadAuthenticationAndConnectAttemptLogs() {
        val bad = GmsTransportLogSignalParser.parse(
            "E AuthPII: getToken() -> BAD_AUTHENTICATION. App: com.google.android.gms"
        )
        val connect = GmsTransportLogSignalParser.parse(
            "D Linux: [Posix_connect Debug]Process com.google.android.gms.persistent :5228"
        )

        assertEquals(GmsTransportLogSignalKind.BAD_AUTHENTICATION, bad?.kind)
        assertEquals(GmsTransportLogSignalKind.MCS_CONNECT_ATTEMPT, connect?.kind)
        assertNotNull(connect)
    }

    @Test
    fun recentBadAuthenticationPlusSustainedMissingTransportAllowsRecovery() {
        val decision = GmsTransportHealthPolicy.decide(
            automaticEnabled = true,
            nowElapsed = 1_000_000L,
            probe = GmsTransportProbe(true, emptySet(), "none"),
            gmsPersistentRunning = true,
            consecutiveMissing = 4,
            missingSinceElapsed = 900_000L,
            lastHealthyElapsed = 0L,
            lastBadAuthenticationElapsed = 950_000L,
            lastConnectAttemptElapsed = 0L,
            evidenceWindowMs = 10 * 60_000L,
            missingAfterBadAuthMs = 90_000L,
            transportLostMs = 4 * 60_000L
        )

        assertTrue(decision.recover)
        assertEquals("mcs_missing_after_bad_auth", decision.reason)
    }

    @Test
    fun missingSocketAloneDoesNotImmediatelyRestartGms() {
        val decision = GmsTransportHealthPolicy.decide(
            automaticEnabled = true,
            nowElapsed = 1_000_000L,
            probe = GmsTransportProbe(true, emptySet(), "none"),
            gmsPersistentRunning = true,
            consecutiveMissing = 2,
            missingSinceElapsed = 970_000L,
            lastHealthyElapsed = 900_000L,
            lastBadAuthenticationElapsed = 0L,
            lastConnectAttemptElapsed = 0L,
            evidenceWindowMs = 10 * 60_000L,
            missingAfterBadAuthMs = 90_000L,
            transportLostMs = 4 * 60_000L
        )

        assertFalse(decision.recover)
    }
    @Test
    fun stalledReconnectAfterKnownHealthyTransportAllowsRecovery() {
        val decision = GmsTransportHealthPolicy.decide(
            automaticEnabled = true,
            nowElapsed = 1_000_000L,
            probe = GmsTransportProbe(true, emptySet(), "none"),
            gmsPersistentRunning = true,
            consecutiveMissing = 8,
            missingSinceElapsed = 700_000L,
            lastHealthyElapsed = 650_000L,
            lastBadAuthenticationElapsed = 0L,
            lastConnectAttemptElapsed = 950_000L,
            evidenceWindowMs = 10 * 60_000L,
            missingAfterBadAuthMs = 90_000L,
            transportLostMs = 4 * 60_000L
        )

        assertTrue(decision.recover)
        assertEquals("mcs_reconnect_stalled", decision.reason)
    }

}
