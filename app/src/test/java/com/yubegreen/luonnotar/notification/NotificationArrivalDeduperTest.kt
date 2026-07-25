package com.yubegreen.luonnotar.notification

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationArrivalDeduperTest {
    @Test
    fun `new key is a new arrival`() {
        val result = NotificationArrivalDeduper.classify(
            emptyList(),
            "com.whatsapp",
            "abc",
            100L
        )
        assertEquals(NotificationArrivalKind.NEW, result.kind)
        assertEquals(listOf("com.whatsapp|abc|100"), result.recentFingerprints)
    }

    @Test
    fun `same key with different post time is an update`() {
        val result = NotificationArrivalDeduper.classify(
            listOf("com.whatsapp|abc|100"),
            "com.whatsapp",
            "abc",
            200L
        )
        assertEquals(NotificationArrivalKind.UPDATE, result.kind)
        assertEquals("com.whatsapp|abc|200", result.recentFingerprints.first())
        assertEquals(1, result.recentFingerprints.size)
    }

    @Test
    fun `same key and post time is a duplicate`() {
        val history = listOf("com.whatsapp|abc|100")
        val result = NotificationArrivalDeduper.classify(
            history,
            "com.whatsapp",
            "abc",
            100L
        )
        assertEquals(NotificationArrivalKind.DUPLICATE, result.kind)
        assertEquals(history, result.recentFingerprints)
    }

    @Test
    fun `removed key can be counted as new when reused`() {
        val recent = NotificationArrivalDeduper.removeKey(
            listOf("com.whatsapp|abc|100", "com.whatsapp|def|200"),
            "com.whatsapp",
            "abc"
        )
        val result = NotificationArrivalDeduper.classify(
            recent,
            "com.whatsapp",
            "abc",
            300L
        )
        assertEquals(NotificationArrivalKind.NEW, result.kind)
    }
}
