package com.yubegreen.luonnotar.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerFaultInjectionPolicyTest {
    @Test fun defaultDurationIsFiveMinutes() {
        assertEquals(
            300_000L,
            NotificationListenerFaultInjectionPolicy.boundedDurationMs(0L)
        )
    }

    @Test fun durationIsBounded() {
        assertEquals(
            150_000L,
            NotificationListenerFaultInjectionPolicy.boundedDurationMs(1L)
        )
        assertEquals(
            600_000L,
            NotificationListenerFaultInjectionPolicy.boundedDurationMs(999_999L)
        )
    }

    @Test fun activeWindowExpires() {
        assertTrue(NotificationListenerFaultInjectionPolicy.isActive(100L, 200L))
        assertFalse(NotificationListenerFaultInjectionPolicy.isActive(200L, 200L))
        assertFalse(NotificationListenerFaultInjectionPolicy.isActive(300L, 200L))
        assertEquals(
            0L,
            NotificationListenerFaultInjectionPolicy.remainingMs(300L, 200L)
        )
    }
}
