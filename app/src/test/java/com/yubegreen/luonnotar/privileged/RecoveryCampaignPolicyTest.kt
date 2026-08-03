package com.yubegreen.luonnotar.privileged

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
        val full = List(RecoveryCampaignPolicy.PACKAGE_MAX_REBUILDS_PER_24_HOURS) { 10_000L + it }
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
                lastCampaignElapsed = 0L,
                campaignHistory = emptyList(),
                manual = false,
                strongEvidence = false
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 100_000L,
                lastCampaignElapsed = 0L,
                campaignHistory = emptyList(),
                manual = false,
                strongEvidence = true
            ).allowed
        )
        assertFalse(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 100_000L,
                lastCampaignElapsed = 90_000L,
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
                resetCount = 1,
                anyGmsFrozen = false,
                transportHealthy = true
            )
        )
    }
    @Test
    fun verifiedGmsOutageUsesBackoffInsteadOfPermanentDailyBlock() {
        val full = List(RecoveryCampaignPolicy.GMS_MAX_EMERGENCY_CAMPAIGNS_PER_24_HOURS) {
            100_000L + it * 1_000L
        }
        assertFalse(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 200_000L,
                lastCampaignElapsed = 190_000L,
                campaignHistory = full,
                manual = false,
                strongEvidence = true,
                preferredRetryIntervalMs = RecoveryCampaignPolicy.GMS_VIVO_RETRY_COOLDOWN_MS
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 900_000L,
                lastCampaignElapsed = 200_000L,
                campaignHistory = full,
                manual = false,
                strongEvidence = true,
                preferredRetryIntervalMs = RecoveryCampaignPolicy.GMS_VIVO_RETRY_COOLDOWN_MS
            ).allowed
        )
        assertTrue(
            RecoveryCampaignPolicy.decideGmsCampaign(
                nowElapsed = 250_000L,
                lastCampaignElapsed = 200_000L,
                campaignHistory = full,
                manual = true,
                strongEvidence = false,
                preferredRetryIntervalMs = RecoveryCampaignPolicy.GMS_VIVO_RETRY_COOLDOWN_MS
            ).allowed
        )
    }

    @Test
    fun vivoAndRefrozenSuccessorsEscalateToForceStop() {
        assertTrue(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.VIVO,
                resetCount = 1,
                refreezeCount = 0
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.XIAOMI,
                resetCount = 2,
                refreezeCount = 0
            )
        )
        assertTrue(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.AOSP,
                resetCount = 1,
                refreezeCount = 1
            )
        )
        assertFalse(
            RecoveryCampaignPolicy.shouldUseForceStopForGms(
                vendorFamily = BackgroundPolicyVendorFamily.AOSP,
                resetCount = 1,
                refreezeCount = 0
            )
        )
    }

    @Test
    fun packageSuccessorAvoidsTerminalStopAppAndEscalatesVendorResetAtFive() {
        assertTrue(
            RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                BackgroundPolicyVendorFamily.XIAOMI,
                nextResetCount = 1,
                refreezeCount = 1
            ) == RecoveryCampaignPolicy.PackageResetStrategy.KILL
        )
        assertTrue(
            RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                BackgroundPolicyVendorFamily.XIAOMI,
                nextResetCount = 3,
                refreezeCount = 3
            ) == RecoveryCampaignPolicy.PackageResetStrategy.KILL
        )
        assertTrue(
            RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                BackgroundPolicyVendorFamily.XIAOMI,
                nextResetCount = 5,
                refreezeCount = 5
            ) == RecoveryCampaignPolicy.PackageResetStrategy.FORCE_STOP_UNSTOP
        )
        assertTrue(
            RecoveryCampaignPolicy.packageSuccessorResetStrategy(
                BackgroundPolicyVendorFamily.AOSP,
                nextResetCount = 5,
                refreezeCount = 5
            ) == RecoveryCampaignPolicy.PackageResetStrategy.KILL
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

}
