package com.yubegreen.luonnotar.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTestDeliveryPolicyTest {
    @Test
    fun `newer sender timestamp replaces current evidence even after sequence reset`() {
        assertTrue(
            PushTestDeliveryPolicy.shouldAccept(
                currentSequence = 900,
                currentSenderEpochMs = 100_000,
                candidateSequence = 1,
                candidateSenderEpochMs = 200_000,
                candidateSeenWall = 201_000
            )
        )
    }

    @Test
    fun `same message never overwrites its first observed arrival`() {
        assertFalse(
            PushTestDeliveryPolicy.shouldAccept(
                currentSequence = 10,
                currentSenderEpochMs = 100_000,
                candidateSequence = 10,
                candidateSenderEpochMs = 100_000,
                candidateSeenWall = 101_001
            )
        )
    }

    @Test
    fun `higher sequence at the same coarse sender timestamp is accepted`() {
        assertTrue(
            PushTestDeliveryPolicy.shouldAccept(
                currentSequence = 10,
                currentSenderEpochMs = 100_000,
                candidateSequence = 11,
                candidateSenderEpochMs = 100_000,
                candidateSeenWall = 101_100
            )
        )
    }

    @Test
    fun `older sender timestamp never overwrites current evidence`() {
        assertFalse(
            PushTestDeliveryPolicy.shouldAccept(
                currentSequence = 10,
                currentSenderEpochMs = 200_000,
                candidateSequence = 999,
                candidateSenderEpochMs = 100_000,
                candidateSeenWall = 300_000
            )
        )
    }

    @Test
    fun `recent controlled delivery is reported with age and delay`() {
        val result = PushTestDeliveryPolicy.evaluate(
            nowWall = 200_000,
            sequence = 12,
            senderEpochMs = 100_000,
            seenWall = 105_000,
            recentWindowMs = 100_000
        )
        assertEquals(ControlledPushDeliveryState.RECENT, result.state)
        assertEquals(95_000, result.ageMs)
        assertEquals(5_000, result.delayMs)
    }

    @Test
    fun `stale and invalid clocks are not called current delivery`() {
        assertEquals(
            ControlledPushDeliveryState.STALE,
            PushTestDeliveryPolicy.evaluate(
                nowWall = 300_001,
                sequence = 1,
                senderEpochMs = 100_000,
                seenWall = 200_000,
                recentWindowMs = 100_000
            ).state
        )
        assertEquals(
            ControlledPushDeliveryState.CLOCK_INVALID,
            PushTestDeliveryPolicy.evaluate(
                nowWall = 90_000,
                sequence = 1,
                senderEpochMs = 100_000,
                seenWall = 105_000
            ).state
        )
    }
}
