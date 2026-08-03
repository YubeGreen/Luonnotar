package com.yubegreen.luonnotar.privileged

/**
 * Bounds the event-driven recovery campaigns introduced in 2.4.0.
 *
 * Verified delivery outages must not turn into a permanent dead end after a
 * daily budget is consumed. Normal recovery remains tightly rate-limited;
 * after the budget, it changes to a long backoff and the successor guard uses
 * progressively stronger process-reset strategies.
 */
object RecoveryCampaignPolicy {
    const val DAY_MS = 24L * 60L * 60L * 1_000L

    const val PACKAGE_CRITICAL_MIN_INTERVAL_MS = 15_000L
    const val PACKAGE_MAX_REBUILDS_PER_24_HOURS = 48
    const val PACKAGE_POST_LIMIT_BACKOFF_MS = 2 * 60_000L
    const val PACKAGE_SUCCESSOR_GUARD_INTERVAL_MS = 5_000L
    const val PACKAGE_SUCCESSOR_GUARD_DURATION_MS = DAY_MS
    const val PACKAGE_SUCCESSOR_STABLE_MS = 30_000L
    const val PACKAGE_SUCCESSOR_FAST_RESETS = 4
    const val PACKAGE_SUCCESSOR_MAX_RESETS = 96
    const val PACKAGE_SUCCESSOR_FAST_RESET_INTERVAL_MS = 5_000L
    const val PACKAGE_SUCCESSOR_SHORT_BACKOFF_MS = 30_000L
    const val PACKAGE_SUCCESSOR_MEDIUM_BACKOFF_MS = 60_000L
    const val PACKAGE_SUCCESSOR_LONG_BACKOFF_MS = 2 * 60_000L
    const val PACKAGE_SUCCESSOR_MAX_BACKOFF_MS = 5 * 60_000L
    const val PACKAGE_SUCCESSOR_ABSENCE_PULSE_INTERVAL_MS = 10_000L
    const val PACKAGE_SUCCESSOR_BACKGROUND_LAUNCH_DELAY_MS = 15_000L
    const val PACKAGE_SUCCESSOR_BACKGROUND_LAUNCH_RETRY_MS = 60_000L
    const val PACKAGE_SUCCESSOR_MAX_BACKGROUND_LAUNCHES = 12
    const val PACKAGE_SUCCESSOR_CIRCUIT_BREAKER_RESETS = 5
    const val PACKAGE_SUCCESSOR_CIRCUIT_BREAKER_REFREEZES = 5
    const val PACKAGE_SUCCESSOR_CIRCUIT_BREAKER_COOLDOWN_MS = 30 * 60_000L
    const val PACKAGE_SUCCESSOR_CIRCUIT_RESCUE_HOLD_MS = 8_000L
    const val MANAGED_PACKAGE_WAKE_INTERVAL_MS = 5 * 60_000L
    const val MANAGED_PACKAGE_FROZEN_WAKE_DELAY_MS = 15_000L
    const val MANAGED_PACKAGE_FROZEN_WAKE_INTERVAL_MS = 5 * 60_000L
    const val MANAGED_PACKAGE_FROZEN_FOREGROUND_HOLD_MS = 8_000L

    const val GMS_CAMPAIGN_TICK_MS = 2_000L
    const val GMS_CAMPAIGN_DURATION_MS = 3 * 60_000L
    const val GMS_CAMPAIGN_STABLE_MS = 15_000L
    const val GMS_MIN_RESET_INTERVAL_MS = 10_000L
    const val GMS_MAX_RESETS_PER_CAMPAIGN = 8
    const val GMS_EMERGENCY_COOLDOWN_MS = 2 * 60_000L
    const val GMS_VIVO_RETRY_COOLDOWN_MS = 30_000L
    const val GMS_POST_LIMIT_BACKOFF_MS = 10 * 60_000L
    const val GMS_MAX_EMERGENCY_CAMPAIGNS_PER_24_HOURS = 12

    enum class PackageResetStrategy {
        KILL,
        STOP_APP,
        FORCE_STOP_UNSTOP
    }

    data class PackageDecision(val allowed: Boolean, val reason: String)
    data class GmsCampaignDecision(val allowed: Boolean, val reason: String)

    fun decideCriticalPackageRebuild(
        nowElapsed: Long,
        lastRebuildElapsed: Long,
        rebuildHistory: List<Long>,
        verifiedFrozen: Boolean,
        newDeliveryEpisode: Boolean
    ): PackageDecision {
        if (nowElapsed < 0L) return PackageDecision(false, "invalid_clock")
        if (!newDeliveryEpisode) return PackageDecision(false, "same_delivery_episode")
        if (!verifiedFrozen) return PackageDecision(false, "freeze_not_verified")
        if (lastRebuildElapsed > nowElapsed) return PackageDecision(false, "elapsed_clock_reset")

        val cutoff = (nowElapsed - DAY_MS).coerceAtLeast(0L)
        val recent = rebuildHistory.count { it in cutoff..nowElapsed }
        val interval = if (recent >= PACKAGE_MAX_REBUILDS_PER_24_HOURS) {
            PACKAGE_POST_LIMIT_BACKOFF_MS
        } else {
            PACKAGE_CRITICAL_MIN_INTERVAL_MS
        }
        if (lastRebuildElapsed > 0L && nowElapsed - lastRebuildElapsed < interval) {
            return PackageDecision(
                false,
                if (recent >= PACKAGE_MAX_REBUILDS_PER_24_HOURS) {
                    "critical_post_limit_backoff"
                } else {
                    "critical_min_interval"
                }
            )
        }
        return PackageDecision(
            true,
            if (recent >= PACKAGE_MAX_REBUILDS_PER_24_HOURS) {
                "verified_delivery_post_limit_retry"
            } else {
                "verified_frozen_delivery_failure"
            }
        )
    }

    fun decideGmsCampaign(
        nowElapsed: Long,
        lastCampaignElapsed: Long,
        campaignHistory: List<Long>,
        manual: Boolean,
        strongEvidence: Boolean,
        preferredRetryIntervalMs: Long = GMS_EMERGENCY_COOLDOWN_MS
    ): GmsCampaignDecision {
        if (nowElapsed < 0L) return GmsCampaignDecision(false, "invalid_clock")
        if (!manual && !strongEvidence) {
            return GmsCampaignDecision(false, "strong_evidence_missing")
        }
        if (lastCampaignElapsed > nowElapsed) {
            return GmsCampaignDecision(false, "elapsed_clock_reset")
        }

        val cutoff = (nowElapsed - DAY_MS).coerceAtLeast(0L)
        val recent = campaignHistory.count { it in cutoff..nowElapsed }
        val overNormalLimit = recent >= GMS_MAX_EMERGENCY_CAMPAIGNS_PER_24_HOURS
        val retryInterval = when {
            manual -> preferredRetryIntervalMs.coerceAtLeast(0L)
            overNormalLimit && strongEvidence -> GMS_POST_LIMIT_BACKOFF_MS
            else -> preferredRetryIntervalMs.coerceAtLeast(0L)
        }
        if (lastCampaignElapsed > 0L && nowElapsed - lastCampaignElapsed < retryInterval) {
            return GmsCampaignDecision(
                false,
                if (overNormalLimit && !manual) {
                    "campaign_post_limit_backoff"
                } else {
                    "campaign_cooldown"
                }
            )
        }

        return GmsCampaignDecision(
            true,
            when {
                manual && overNormalLimit -> "manual_campaign_limit_override"
                manual -> "manual_campaign"
                overNormalLimit -> "verified_outage_post_limit_retry"
                else -> "verified_frozen_mcs_missing"
            }
        )
    }

    fun shouldUseForceStopForGms(
        vendorFamily: BackgroundPolicyVendorFamily,
        resetCount: Int,
        refreezeCount: Int
    ): Boolean =
        vendorFamily == BackgroundPolicyVendorFamily.VIVO ||
            resetCount > 1 ||
            refreezeCount > 0

    fun packageSuccessorResetIntervalMs(resetCount: Int): Long = when {
        resetCount < PACKAGE_SUCCESSOR_FAST_RESETS -> PACKAGE_SUCCESSOR_FAST_RESET_INTERVAL_MS
        resetCount < 8 -> PACKAGE_SUCCESSOR_SHORT_BACKOFF_MS
        resetCount < 12 -> PACKAGE_SUCCESSOR_MEDIUM_BACKOFF_MS
        resetCount < 20 -> PACKAGE_SUCCESSOR_LONG_BACKOFF_MS
        else -> PACKAGE_SUCCESSOR_MAX_BACKOFF_MS
    }

    @Suppress("UNUSED_PARAMETER")
    fun packageSuccessorResetStrategy(
        vendorFamily: BackgroundPolicyVendorFamily,
        nextResetCount: Int,
        refreezeCount: Int
    ): PackageResetStrategy = when {
        nextResetCount >= 5 &&
            (vendorFamily == BackgroundPolicyVendorFamily.XIAOMI ||
                vendorFamily == BackgroundPolicyVendorFamily.VIVO) ->
            PackageResetStrategy.FORCE_STOP_UNSTOP
        else -> PackageResetStrategy.KILL
    }



    fun shouldOpenPackageSuccessorCircuit(
        vendorFamily: BackgroundPolicyVendorFamily,
        resetCount: Int,
        refreezeCount: Int
    ): Boolean =
        vendorFamily in setOf(
            BackgroundPolicyVendorFamily.XIAOMI,
            BackgroundPolicyVendorFamily.VIVO
        ) &&
            resetCount >= PACKAGE_SUCCESSOR_CIRCUIT_BREAKER_RESETS &&
            refreezeCount >= PACKAGE_SUCCESSOR_CIRCUIT_BREAKER_REFREEZES

    fun shouldAttemptFrozenManagedPackageWake(
        nowElapsed: Long,
        frozenSinceElapsed: Long,
        lastWakeElapsed: Long
    ): Boolean {
        if (
            nowElapsed < 0L ||
            frozenSinceElapsed <= 0L ||
            frozenSinceElapsed > nowElapsed ||
            lastWakeElapsed > nowElapsed
        ) {
            return false
        }
        if (nowElapsed - frozenSinceElapsed < MANAGED_PACKAGE_FROZEN_WAKE_DELAY_MS) {
            return false
        }
        if (lastWakeElapsed <= 0L) return true
        return nowElapsed - lastWakeElapsed >= MANAGED_PACKAGE_FROZEN_WAKE_INTERVAL_MS
    }

    fun shouldPulseAbsentPackageSuccessor(
        nowElapsed: Long,
        absentSinceElapsed: Long,
        lastPulseElapsed: Long
    ): Boolean {
        if (nowElapsed < 0L || absentSinceElapsed <= 0L || absentSinceElapsed > nowElapsed) {
            return false
        }
        if (lastPulseElapsed <= 0L || lastPulseElapsed > nowElapsed) return true
        return nowElapsed - lastPulseElapsed >= PACKAGE_SUCCESSOR_ABSENCE_PULSE_INTERVAL_MS
    }

    fun shouldBackgroundLaunchAbsentPackageSuccessor(
        nowElapsed: Long,
        absentSinceElapsed: Long,
        lastLaunchElapsed: Long,
        launchCount: Int
    ): Boolean {
        if (
            nowElapsed < 0L ||
            absentSinceElapsed <= 0L ||
            absentSinceElapsed > nowElapsed ||
            launchCount >= PACKAGE_SUCCESSOR_MAX_BACKGROUND_LAUNCHES
        ) {
            return false
        }
        if (nowElapsed - absentSinceElapsed < PACKAGE_SUCCESSOR_BACKGROUND_LAUNCH_DELAY_MS) {
            return false
        }
        if (lastLaunchElapsed <= 0L || lastLaunchElapsed > nowElapsed) return true
        return nowElapsed - lastLaunchElapsed >= PACKAGE_SUCCESSOR_BACKGROUND_LAUNCH_RETRY_MS
    }

    fun shouldWakeManagedPackage(
        nowElapsed: Long,
        lastWakeElapsed: Long,
        processPresent: Boolean
    ): Boolean {
        if (processPresent || nowElapsed < 0L || lastWakeElapsed > nowElapsed) return false
        if (lastWakeElapsed <= 0L) return true
        return nowElapsed - lastWakeElapsed >= MANAGED_PACKAGE_WAKE_INTERVAL_MS
    }

    fun shouldResetPackageSuccessor(
        nowElapsed: Long,
        lastResetElapsed: Long,
        resetCount: Int,
        verifiedFrozen: Boolean
    ): Boolean {
        if (!verifiedFrozen || nowElapsed < 0L || lastResetElapsed > nowElapsed) return false
        if (resetCount >= PACKAGE_SUCCESSOR_MAX_RESETS) return false
        if (lastResetElapsed <= 0L) return true
        return nowElapsed - lastResetElapsed >= packageSuccessorResetIntervalMs(resetCount)
    }

    fun shouldResetGmsAgain(
        nowElapsed: Long,
        lastResetElapsed: Long,
        resetCount: Int,
        anyGmsFrozen: Boolean,
        transportHealthy: Boolean
    ): Boolean {
        if (nowElapsed < 0L || lastResetElapsed > nowElapsed) return false
        if (resetCount >= GMS_MAX_RESETS_PER_CAMPAIGN) return false
        if (transportHealthy && !anyGmsFrozen) return false
        if (lastResetElapsed <= 0L) return true
        return nowElapsed - lastResetElapsed >= GMS_MIN_RESET_INTERVAL_MS
    }

    fun campaignStable(
        nowElapsed: Long,
        stableSinceElapsed: Long,
        anyGmsFrozen: Boolean,
        transportHealthy: Boolean
    ): Boolean =
        stableSinceElapsed > 0L &&
            stableSinceElapsed <= nowElapsed &&
            !anyGmsFrozen &&
            transportHealthy &&
            nowElapsed - stableSinceElapsed >= GMS_CAMPAIGN_STABLE_MS
}
