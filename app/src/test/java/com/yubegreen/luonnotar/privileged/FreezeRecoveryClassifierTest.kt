package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class FreezeRecoveryClassifierTest {
    @Test fun commandAcceptanceIsNotEquivalentToVerifiedThaw() {
        assertEquals(
            FreezeRecoveryVerdict.STILL_FROZEN,
            FreezeRecoveryClassifier.classify(
                beforeFrozen = true,
                afterFrozen = true,
                commandAttempted = true,
                commandAccepted = true
            )
        )
        assertEquals(
            FreezeRecoveryVerdict.VERIFIED_THAWED,
            FreezeRecoveryClassifier.classify(
                beforeFrozen = true,
                afterFrozen = false,
                commandAttempted = true,
                commandAccepted = true
            )
        )
    }

    @Test fun secondaryProcessWithoutWritableCgroupHasNoSafeCommandPath() {
        assertEquals(
            FreezeRecoveryVerdict.NO_SAFE_COMMAND_PATH,
            FreezeRecoveryClassifier.classify(
                beforeFrozen = true,
                afterFrozen = true,
                commandAttempted = false,
                commandAccepted = false
            )
        )
    }

    @Test
    fun rejectedCommandIsNotMisreportedAsVerificationFailure() {
        assertEquals(
            FreezeRecoveryVerdict.COMMAND_FAILED,
            FreezeRecoveryClassifier.classify(
                beforeFrozen = true,
                afterFrozen = true,
                commandAttempted = true,
                commandAccepted = false
            )
        )
    }
    @Test
    fun directCgroupIsFallbackRatherThanUnconditionalSecondWrite() {
        assertEquals(
            false,
            FreezeRecoveryClassifier.shouldTryDirectCgroup(
                enabled = true,
                hasControlFile = true,
                beforeFrozen = true,
                afterActivityManagerFrozen = false,
                activityManagerAttempted = true,
                activityManagerAccepted = true
            )
        )
        assertEquals(
            true,
            FreezeRecoveryClassifier.shouldTryDirectCgroup(
                enabled = true,
                hasControlFile = true,
                beforeFrozen = true,
                afterActivityManagerFrozen = true,
                activityManagerAttempted = true,
                activityManagerAccepted = true
            )
        )
        assertEquals(
            true,
            FreezeRecoveryClassifier.shouldTryDirectCgroup(
                enabled = true,
                hasControlFile = true,
                beforeFrozen = true,
                afterActivityManagerFrozen = null,
                activityManagerAttempted = true,
                activityManagerAccepted = false
            )
        )
        assertEquals(
            true,
            FreezeRecoveryClassifier.shouldTryDirectCgroup(
                enabled = true,
                hasControlFile = true,
                beforeFrozen = true,
                afterActivityManagerFrozen = true,
                activityManagerAttempted = false,
                activityManagerAccepted = false
            )
        )
    }

}
