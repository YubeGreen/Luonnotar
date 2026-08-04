package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCampaignPolicyTest {
    @Test
    fun criticalPackageRecoveryRequiresNewEpisodeAndVerifiedFreeze() {
        assertFalse(
            RecoveryCampaignPolicy.decideCriticalPackageRebuild(
                nowElapsed = 100_000L,
                lastRebuildElapsed = 0L,
                rebuildHistory = emptyList(),
                verifiedFrozen = false,
                newDeliveryEpisode = true
            ).allowed
        )
        assertFalse(
            RecoveryCampaignPolicy.decideCriticalPackageRebuild(
                nowElapsed = 100_000L,
                lastRebuildElapsed = 0L,
                rebuildHistory = emptyList(),
                verifiedFrozen = true,
                newDeliveryEpisode = false
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideCriticalPackageRebuild(
                nowElapsed = 100_000L,
                lastRebuildElapsed = 0L,
                rebuildHistory = emptyList(),
                verifiedFrozen = true,
                newDeliveryEpisode = true
            ).allowed
        )
    }

    @Test
    fun criticalPackageRecoveryUsesBackoffAfterDailyBudget() {
        assertFalse(
            RecoveryCampaignPolicy.decideCriticalPackageRebuild(
                nowElapsed = 100_000L,
                lastRebuildElapsed = 90_000L,
                rebuildHistory = listOf(90_000L),
                verifiedFrozen = true,
                newDeliveryEpisode = true
            ).allowed
        )
        val full = List(RecoveryCampaignPolicy.PACKAGE_MAX_REBUILDS_PER_24_HOURS) {
            10_000L + it
        }
        assertFalse(
            RecoveryCampaignPolicy.decideCriticalPackageRebuild(
                nowElapsed = 100_000L,
                lastRebuildElapsed = 50_000L,
                rebuildHistory = full,
                verifiedFrozen = true,
                newDeliveryEpisode = true
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideCriticalPackageRebuild(
                nowElapsed = 400_000L,
                lastRebuildElapsed = 100_000L,
                rebuildHistory = full,
                verifiedFrozen = true,
                newDeliveryEpisode = true
            ).allowed
        )
    }

    @Test
    fun gmsCampaignRequiresStrongEvidenceAndHasEmergencyBounds() {
        assertFalse(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 100_000L,
                lastCampaignCompletedElapsed = 0L,
                campaignHistory = emptyList(),
                manual = false,
                strongEvidence = false
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 100_000L,
                lastCampaignCompletedElapsed = 0L,
                campaignHistory = emptyList(),
                manual = false,
                strongEvidence = true
            ).allowed
        )
        assertFalse(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 100_000L,
                lastCampaignCompletedElapsed = 90_000L,
                campaignHistory = listOf(90_000L),
                manual = true,
                strongEvidence = false
            ).allowed
        )
    }

    @Test
    fun successorResetRequiresVerifiedRefreezeAndUsesProgressiveBackoff() {
        assertFalse(
            RecoveryCampaignPolicy.shouldResetPackageSuccessor(
                nowElapsed = 100_000L,
                lastResetElapsed = 0L,
                resetCount = 0,
                verifiedFrozen = false
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldResetPackageSuccessor(
                nowElapsed = 100_000L,
                lastResetElapsed = 0L,
                resetCount = 0,
                verifiedFrozen = true
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldResetPackageSuccessor(
                nowElapsed = 103_000L,
                lastResetElapsed = 100_000L,
                resetCount = 1,
                verifiedFrozen = true
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldResetPackageSuccessor(
                nowElapsed = 110_000L,
                lastResetElapsed = 100_000L,
                resetCount = RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_MAX_RESETS,
                verifiedFrozen = true
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldResetPackageSuccessor(
                nowElapsed = 120_000L,
                lastResetElapsed = 100_000L,
                resetCount = 5,
                verifiedFrozen = true
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldResetPackageSuccessor(
                nowElapsed = 140_000L,
                lastResetElapsed = 100_000L,
                resetCount = 5,
                verifiedFrozen = true
            )
        )
    }

    @Test
    fun gmsCampaignRetriesOnlyWhenDegradedAndBounded() {
        assertTrue(
            RecoveryCampaignPolicy.shouldResetGmsAgain(
                nowElapsed = 100_000L,
                lastResetElapsed = 0L,
                resetCount = 0,
                anyGmsFrozen = true,
                transportHealthy = false
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldResetGmsAgain(
                nowElapsed = 105_000L,
                lastResetElapsed = 100_000L,
                resetCount = 1,
                anyGmsFrozen = true,
                transportHealthy = false
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldResetGmsAgain(
                nowElapsed = 120_000L,
                lastResetElapsed = 100_000L,
                resetCount = RecoveryCampaignPolicy.GMS_MAX_RESETS_PER_CAMPAIGN,
                anyGmsFrozen = true,
                transportHealthy = false
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldResetGmsAgain(
                nowElapsed = 120_000L,
                lastResetElapsed = 100_000L,
                resetCount = RecoveryCampaignPolicy.GMS_VIVO_MAX_RESETS_PER_CAMPAIGN,
                anyGmsFrozen = true,
                transportHealthy = false,
                maxResetCount = RecoveryCampaignPolicy.GMS_VIVO_MAX_RESETS_PER_CAMPAIGN
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldResetGmsAgain(
                nowElapsed = 120_000L,
                lastResetElapsed = 100_000L,
                resetCount = 1,
                anyGmsFrozen = false,
                transportHealthy = true
            )
        )
    }

    @Test
    fun verifiedGmsOutageUsesAdaptiveRetryInsteadOfThirtyMinuteDeadZone() {
        assertEquals(
            2 * 60_000L,
            RecoveryCampaignPolicy.gmsAutomaticRetryIntervalMs(1)
        )
        assertEquals(
            5 * 60_000L,
            RecoveryCampaignPolicy.gmsAutomaticRetryIntervalMs(2)
        )
        assertEquals(
            15 * 60_000L,
            RecoveryCampaignPolicy.gmsAutomaticRetryIntervalMs(3)
        )
        assertEquals(
            30 * 60_000L,
            RecoveryCampaignPolicy.gmsAutomaticRetryIntervalMs(4)
        )

        assertFalse(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 219_000L,
                lastCampaignCompletedElapsed = 100_000L,
                campaignHistory = listOf(10_000L),
                manual = false,
                strongEvidence = true,
                consecutiveFailureCount = 1
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 220_000L,
                lastCampaignCompletedElapsed = 100_000L,
                campaignHistory = listOf(10_000L),
                manual = false,
                strongEvidence = true,
                consecutiveFailureCount = 1
            ).allowed
        )
        assertFalse(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 999_000L,
                lastCampaignCompletedElapsed = 100_000L,
                campaignHistory = listOf(10_000L),
                manual = false,
                strongEvidence = true,
                consecutiveFailureCount = 3
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 1_000_000L,
                lastCampaignCompletedElapsed = 100_000L,
                campaignHistory = listOf(10_000L),
                manual = false,
                strongEvidence = true,
                consecutiveFailureCount = 3
            ).allowed
        )
    }

    @Test
    fun gmsForceStopHasGlobalTenMinuteAndDailyBudgets() {
        assertTrue(
            RecoveryCampaignPolicy.decideGmsForceStop(
                nowElapsed = 1_000_000L,
                forceStopHistory = emptyList()
            ).allowed
        )
        assertFalse(
            RecoveryCampaignPolicy.decideGmsForceStop(
                nowElapsed = 1_000_000L,
                forceStopHistory = listOf(500_001L)
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideGmsForceStop(
                nowElapsed = 1_100_000L,
                forceStopHistory = listOf(500_000L)
            ).allowed
        )
        val full = List(RecoveryCampaignPolicy.GMS_FORCE_STOP_MAX_PER_24_HOURS) {
            100_000L + it * RecoveryCampaignPolicy.GMS_FORCE_STOP_MIN_INTERVAL_MS
        }
        assertFalse(
            RecoveryCampaignPolicy.decideGmsForceStop(
                nowElapsed = full.last() + RecoveryCampaignPolicy.GMS_FORCE_STOP_MIN_INTERVAL_MS,
                forceStopHistory = full
            ).allowed
        )
    }

    @Test
    fun deliveryProtectionIsBoundedAndEscalatesOnlyOnSecondVerifiedEpisode() {
        assertEquals(
            145_000L,
            RecoveryCampaignPolicy.deliveryProtectionDeadline(
                nowElapsed = 100_000L,
                startedElapsed = 100_000L,
                currentDeadlineElapsed = 0L,
                newDeliveryEpisode = true
            )
        )
        assertEquals(
            175_000L,
            RecoveryCampaignPolicy.deliveryProtectionDeadline(
                nowElapsed = 145_000L,
                startedElapsed = 100_000L,
                currentDeadlineElapsed = 145_000L,
                newDeliveryEpisode = true
            )
        )
        assertEquals(
            190_000L,
            RecoveryCampaignPolicy.deliveryProtectionDeadline(
                nowElapsed = 180_000L,
                startedElapsed = 100_000L,
                currentDeadlineElapsed = 175_000L,
                newDeliveryEpisode = true
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldEscalateDeliveryProtectionKill(
                nowElapsed = 120_000L,
                startedElapsed = 100_000L,
                deliveryEpisodeCount = 1,
                killCount = 0,
                verifiedFrozen = true
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldEscalateDeliveryProtectionKill(
                nowElapsed = 120_000L,
                startedElapsed = 100_000L,
                deliveryEpisodeCount = 2,
                killCount = 0,
                verifiedFrozen = true
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldEscalateDeliveryProtectionKill(
                nowElapsed = 120_000L,
                startedElapsed = 100_000L,
                deliveryEpisodeCount = 2,
                killCount = 1,
                verifiedFrozen = true
            )
        )
    }

    @Test
    fun vivoGmsForceStopIsDelayedAndBudgeted() {
        assertEquals(
            2,
            RecoveryCampaignPolicy.gmsMaxResetsPerCampaign(
                BackgroundPolicyVendorFamily.VIVO
            )
        )
        assertEquals(
            1,
            RecoveryCampaignPolicy.gmsMaxForceStopsPerCampaign(
                BackgroundPolicyVendorFamily.VIVO
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                resetCount = 1,
                refreezeCount = 0,
                forceStopCount = 0
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                resetCount = 2,
                refreezeCount = 0,
                forceStopCount = 0
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                resetCount = 3,
                refreezeCount = 2,
                forceStopCount = 1
            )
        )
    }

    @Test
    fun vivoGmsCampaignWaitsForPreconnectionAndStopsAfterForceStop() {
        assertEquals(
            20_000L,
            RecoveryCampaignPolicy.gmsInitialResetDelayMs(
                BackgroundPolicyVendorFamily.VIVO
            )
        )
        assertEquals(
            45_000L,
            RecoveryCampaignPolicy.gmsPostResetWaitMs(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                resetCount = 1,
                forceStopCount = 0
            )
        )
        assertEquals(
            Long.MAX_VALUE,
            RecoveryCampaignPolicy.gmsPostResetWaitMs(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                resetCount = 2,
                forceStopCount = 1
            )
        )
        assertEquals(
            RecoveryCampaignPolicy.GMS_MIN_RESET_INTERVAL_MS,
            RecoveryCampaignPolicy.gmsPostResetWaitMs(
                vendorFamily = BackgroundPolicyVendorFamily.XIAOMI,
                resetCount = 1,
                forceStopCount = 0
            )
        )
    }

    @Test
    fun vivoTransportFlappingEscalatesAfterThreeCollapsesOrThirtySecondsWithoutStability() {
        assertTrue(
            RecoveryCampaignPolicy.shouldEscalateGmsTransportFlapping(
                nowElapsed = 119_000L,
                phaseStartedElapsed = 100_000L,
                longestContinuousTransportMs = 7_000L,
                collapseWindowStartedElapsed = 100_000L,
                collapseCountInWindow = 3
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldEscalateGmsTransportFlapping(
                nowElapsed = 121_000L,
                phaseStartedElapsed = 100_000L,
                longestContinuousTransportMs = 7_000L,
                collapseWindowStartedElapsed = 100_000L,
                collapseCountInWindow = 3
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldEscalateGmsTransportFlapping(
                nowElapsed = 130_000L,
                phaseStartedElapsed = 100_000L,
                longestContinuousTransportMs = 14_999L,
                collapseWindowStartedElapsed = 0L,
                collapseCountInWindow = 0
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldEscalateGmsTransportFlapping(
                nowElapsed = 130_000L,
                phaseStartedElapsed = 100_000L,
                longestContinuousTransportMs = 15_000L,
                collapseWindowStartedElapsed = 0L,
                collapseCountInWindow = 0
            )
        )
    }

    @Test
    fun nonVivoGmsForceStopStillRequiresEscalationAndBudget() {
        assertTrue(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.XIAOMI,
                resetCount = 2,
                refreezeCount = 0,
                forceStopCount = 0
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.AOSP,
                resetCount = 1,
                refreezeCount = 1,
                forceStopCount = 0
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.AOSP,
                resetCount = 1,
                refreezeCount = 0,
                forceStopCount = 0
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.XIAOMI,
                resetCount = 4,
                refreezeCount = 4,
                forceStopCount = RecoveryCampaignPolicy.GMS_DEFAULT_MAX_FORCE_STOPS_PER_CAMPAIGN
            )
        )
    }

    @Test
    fun packageSuccessorAvoidsTerminalStopAppAndEscalatesVendorResetAtFive() {
        assertEquals(
            RecoveryCampaignPolicy.PackageResetStrategy.KILL,
            RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                BackgroundPolicyVendorFamily.XIAOMI,
                nextResetCount = 1,
                refreezeCount = 1
            )
        )
        assertEquals(
            RecoveryCampaignPolicy.PackageResetStrategy.KILL,
            RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                BackgroundPolicyVendorFamily.XIAOMI,
                nextResetCount = 3,
                refreezeCount = 3
            )
        )
        assertEquals(
            RecoveryCampaignPolicy.PackageResetStrategy.FORCE_STOP_UNSTOP,
            RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                BackgroundPolicyVendorFamily.XIAOMI,
                nextResetCount = 5,
                refreezeCount = 5
            )
        )
        assertEquals(
            RecoveryCampaignPolicy.PackageResetStrategy.KILL,
            RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                BackgroundPolicyVendorFamily.AOSP,
                nextResetCount = 5,
                refreezeCount = 5
            )
        )
    }

    @Test
    fun circuitDeliveryRescueRequiresActiveCircuitFreezeAndCooldown() {
        assertFalse(
            RecoveryCampaignPolicy.shouldAttemptCircuitDeliveryRescue(
                nowElapsed = 100_000L,
                circuitUntilElapsed = 200_000L,
                lastRescueElapsed = 0L,
                verifiedFrozen = false
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldAttemptCircuitDeliveryRescue(
                nowElapsed = 200_000L,
                circuitUntilElapsed = 200_000L,
                lastRescueElapsed = 0L,
                verifiedFrozen = true
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldAttemptCircuitDeliveryRescue(
                nowElapsed = 100_000L,
                circuitUntilElapsed = 200_000L,
                lastRescueElapsed = 0L,
                verifiedFrozen = true
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldAttemptCircuitDeliveryRescue(
                nowElapsed = 150_000L,
                circuitUntilElapsed = 300_000L,
                lastRescueElapsed = 100_000L,
                verifiedFrozen = true
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldAttemptCircuitDeliveryRescue(
                nowElapsed = 220_000L,
                circuitUntilElapsed = 300_000L,
                lastRescueElapsed = 100_000L,
                verifiedFrozen = true
            )
        )
    }

    @Test
    fun absentSuccessorGetsPulsedThenBackgroundLaunchedWithBounds() {
        assertTrue(
            RecoveryCampaignPolicy.shouldPulseAbsentPackageSuccessor(
                nowElapsed = 100_000L,
                absentSinceElapsed = 100_000L,
                lastPulseElapsed = 0L
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldPulseAbsentPackageSuccessor(
                nowElapsed = 105_000L,
                absentSinceElapsed = 100_000L,
                lastPulseElapsed = 100_000L
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldPulseAbsentPackageSuccessor(
                nowElapsed = 110_000L,
                absentSinceElapsed = 100_000L,
                lastPulseElapsed = 100_000L
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldBackgroundLaunchAbsentPackageSuccessor(
                nowElapsed = 110_000L,
                absentSinceElapsed = 100_000L,
                lastLaunchElapsed = 0L,
                launchCount = 0
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldBackgroundLaunchAbsentPackageSuccessor(
                nowElapsed = 115_000L,
                absentSinceElapsed = 100_000L,
                lastLaunchElapsed = 0L,
                launchCount = 0
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldBackgroundLaunchAbsentPackageSuccessor(
                nowElapsed = 200_000L,
                absentSinceElapsed = 100_000L,
                lastLaunchElapsed = 0L,
                launchCount = RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_MAX_BACKGROUND_LAUNCHES
            )
        )
    }

    @Test
    fun managedPackageWakeIsAbsentOnlyAndRateLimited() {
        assertTrue(
            RecoveryCampaignPolicy.shouldWakeManagedPackage(
                nowElapsed = 100_000L,
                lastWakeElapsed = 0L,
                processPresent = false
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldWakeManagedPackage(
                nowElapsed = 100_000L,
                lastWakeElapsed = 0L,
                processPresent = true
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldWakeManagedPackage(
                nowElapsed = 200_000L,
                lastWakeElapsed = 100_000L,
                processPresent = false
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldWakeManagedPackage(
                nowElapsed = 500_000L,
                lastWakeElapsed = 100_000L,
                processPresent = false
            )
        )
    }

    @Test
    fun vendorRefreezeCircuitBreakerStopsRunawayResetLoop() {
        assertFalse(
            RecoveryCampaignPolicy.shouldOpenPackageSuccessorCircuit(
                vendorFamily = BackgroundPolicyVendorFamily.XIAOMI,
                resetCount = 4,
                refreezeCount = 20
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldOpenPackageSuccessorCircuit(
                vendorFamily = BackgroundPolicyVendorFamily.AOSP,
                resetCount = 20,
                refreezeCount = 20
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldOpenPackageSuccessorCircuit(
                vendorFamily = BackgroundPolicyVendorFamily.XIAOMI,
                resetCount = RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_CIRCUIT_BREAKER_RESETS,
                refreezeCount = RecoveryCampaignPolicy.PACKAGE_SUCCESSOR_CIRCUIT_BREAKER_REFREEZES
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldOpenPackageSuccessorCircuit(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                resetCount = 12,
                refreezeCount = 12
            )
        )
    }

    @Test
    fun frozenManagedPackageWakeRequiresPersistenceAndCooldown() {
        assertFalse(
            RecoveryCampaignPolicy.shouldAttemptFrozenManagedPackageWake(
                nowElapsed = 110_000L,
                frozenSinceElapsed = 100_000L,
                lastWakeElapsed = 0L
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldAttemptFrozenManagedPackageWake(
                nowElapsed = 115_000L,
                frozenSinceElapsed = 100_000L,
                lastWakeElapsed = 0L
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldAttemptFrozenManagedPackageWake(
                nowElapsed = 200_000L,
                frozenSinceElapsed = 100_000L,
                lastWakeElapsed = 150_000L
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldAttemptFrozenManagedPackageWake(
                nowElapsed = 450_000L,
                frozenSinceElapsed = 100_000L,
                lastWakeElapsed = 150_000L
            )
        )
    }

    @Test
    fun vivoHoldsContinuousAnchorWhenForceStopGateIsClosed() {
        assertTrue(
            RecoveryCampaignPolicy.shouldHoldAnchorInsteadOfFallbackStopApp(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                nextResetCount = 2,
                forceStopWanted = true,
                forceStopAllowed = false
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldHoldAnchorInsteadOfFallbackStopApp(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                nextResetCount = 2,
                forceStopWanted = true,
                forceStopAllowed = true
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldHoldAnchorInsteadOfFallbackStopApp(
                vendorFamily = BackgroundPolicyVendorFamily.XIAOMI,
                nextResetCount = 2,
                forceStopWanted = true,
                forceStopAllowed = false
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldHoldAnchorInsteadOfFallbackStopApp(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                nextResetCount = 1,
                forceStopWanted = false,
                forceStopAllowed = false
            )
        )
    }

}
