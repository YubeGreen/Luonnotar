package com.yubegreen.luonnotar.notification

enum class ControlledPushDeliveryState {
    UNVERIFIED,
    RECENT,
    STALE,
    CLOCK_INVALID
}

data class ControlledPushDeliveryEvidence(
    val state: ControlledPushDeliveryState,
    val ageMs: Long,
    val delayMs: Long
)

object PushTestDeliveryPolicy {
    const val RECENT_WINDOW_MS = 10 * 60_000L

    fun shouldAccept(
        currentSequence: Long,
        currentSenderEpochMs: Long,
        candidateSequence: Long,
        candidateSenderEpochMs: Long,
        candidateSeenWall: Long
    ): Boolean =
        candidateSequence > 0L &&
            candidateSenderEpochMs > 0L &&
            candidateSeenWall > 0L &&
            (
                candidateSenderEpochMs > currentSenderEpochMs ||
                    (
                        candidateSenderEpochMs == currentSenderEpochMs &&
                            candidateSequence > currentSequence
                        )
                )

    fun evaluate(
        nowWall: Long,
        sequence: Long,
        senderEpochMs: Long,
        seenWall: Long,
        recentWindowMs: Long = RECENT_WINDOW_MS
    ): ControlledPushDeliveryEvidence {
        if (sequence <= 0L || senderEpochMs <= 0L || seenWall <= 0L) {
            return ControlledPushDeliveryEvidence(
                state = ControlledPushDeliveryState.UNVERIFIED,
                ageMs = -1L,
                delayMs = -1L
            )
        }
        if (nowWall < seenWall || seenWall < senderEpochMs) {
            return ControlledPushDeliveryEvidence(
                state = ControlledPushDeliveryState.CLOCK_INVALID,
                ageMs = (nowWall - seenWall).coerceAtLeast(-1L),
                delayMs = seenWall - senderEpochMs
            )
        }
        val ageMs = nowWall - seenWall
        return ControlledPushDeliveryEvidence(
            state = if (ageMs <= recentWindowMs) {
                ControlledPushDeliveryState.RECENT
            } else {
                ControlledPushDeliveryState.STALE
            },
            ageMs = ageMs,
            delayMs = seenWall - senderEpochMs
        )
    }
}
