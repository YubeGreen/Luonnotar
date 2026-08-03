package com.yubegreen.luonnotar.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTestNotificationParserTest {
    @Test
    fun parsesOnlyTheControlledPushTestFormat() {
        val parsed = requireNotNull(
            PushTestNotificationParser.parse(
                "PUSH_TEST_899  2026-07-26 00:34:21"
            )
        )
        assertEquals(899L, parsed.sequence)
        assertEquals("2026-07-26 00:34:21", parsed.senderLocalTime)
        assertEquals("Pacific/Auckland", parsed.senderZoneId)
        assertTrue(parsed.senderEpochMs > 0L)
    }

    @Test
    fun parsesMillisecondPrecisionAndPreservesSecondPrecisionCompatibility() {
        val wholeSecond = requireNotNull(
            PushTestNotificationParser.parse(
                "PUSH_TEST_899  2026-07-26 00:34:21"
            )
        )
        val millisecond = requireNotNull(
            PushTestNotificationParser.parse(
                "PUSH_TEST_899  2026-07-26 00:34:21.123"
            )
        )

        assertEquals(1000L, wholeSecond.senderPrecisionMs)
        assertEquals(1L, millisecond.senderPrecisionMs)
        assertEquals(123L, millisecond.senderEpochMs - wholeSecond.senderEpochMs)
        assertEquals(
            "2026-07-26 00:34:21.123",
            millisecond.senderLocalTime
        )
    }

    @Test
    fun newestControlledMessageWinsWhenMessagingHistoryContainsABacklog() {
        val parsed = requireNotNull(
            PushTestNotificationParser.parseLatest(
                listOf(
                    "PUSH_TEST_41  2026-07-26 00:34:21",
                    "ordinary message",
                    "PUSH_TEST_42  2026-07-26 00:35:21"
                )
            )
        )
        assertEquals(42L, parsed.sequence)
        assertEquals("2026-07-26 00:35:21", parsed.senderLocalTime)
    }

    @Test
    fun unicodeSpacesAndInvisibleFormatCharactersAreNormalized() {
        val parsed = requireNotNull(
            PushTestNotificationParser.parse(
                "\u200EPUSH_TEST_77\u00A0\u200B 2026-07-28\u202F21:15:09\u200F"
            )
        )
        assertEquals(77L, parsed.sequence)
        assertEquals("2026-07-28 21:15:09", parsed.senderLocalTime)
    }

    @Test
    fun titleCanBeDiagnosedButNeverBecomesDeliveryEvidence() {
        val diagnostic = PushTestNotificationParser.parseLatestCandidates(
            listOf(
                PushTestCandidate(
                    PushTestCandidateSource.EXTRA_TITLE,
                    "PUSH_TEST_88 2026-07-28 21:15:09"
                ),
                PushTestCandidate(
                    PushTestCandidateSource.EXTRA_TEXT,
                    "ordinary message body"
                )
            )
        )
        assertNull(diagnostic.notification)
        assertTrue(diagnostic.controlledPrefixObserved)
        assertEquals(
            "valid_pattern_in_diagnostic_only_field",
            diagnostic.rejectionReason
        )
    }

    @Test
    fun messagingStyleSourceIsPreservedWithoutPersistingMessageText() {
        val diagnostic = PushTestNotificationParser.parseLatestCandidates(
            listOf(
                PushTestCandidate(
                    PushTestCandidateSource.EXTRA_MESSAGES,
                    "PUSH_TEST_90 2026-07-28 21:15:09"
                )
            )
        )
        val parsed = requireNotNull(diagnostic.notification)
        assertEquals(90L, parsed.sequence)
        assertEquals(
            PushTestCandidateSource.EXTRA_MESSAGES,
            diagnostic.matchedSource
        )
        assertEquals(listOf("EXTRA_MESSAGES"), diagnostic.candidateSourcesPresent)
        assertTrue(diagnostic.controlledPrefixObserved)
    }

    @Test
    fun ordinaryNotificationBodyIsRejectedAndCannotBeStoredAsTestEvidence() {
        assertNull(PushTestNotificationParser.parse("普通 WhatsApp 聊天内容"))
        assertNull(PushTestNotificationParser.parse("PUSH_TEST_899 hello"))
    }
}
