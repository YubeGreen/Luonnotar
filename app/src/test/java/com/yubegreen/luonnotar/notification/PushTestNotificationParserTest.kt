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
    fun ordinaryNotificationBodyIsRejectedAndCannotBeStoredAsTestEvidence() {
        assertNull(PushTestNotificationParser.parse("普通 WhatsApp 聊天内容"))
        assertNull(PushTestNotificationParser.parse("PUSH_TEST_899 hello"))
    }
}
