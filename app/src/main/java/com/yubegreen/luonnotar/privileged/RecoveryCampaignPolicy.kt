package com.yubegreen.luonnotar.privileged

/**
 * Bounds the event-driven recovery campaigns introduced in 2.4.0.
 *
 * Verified delivery outages must not turn into either a reset storm or a
 * long confirmed dead zone. Recovery is bounded by adaptive retry intervals,
 * a separate destructive-action budget, and short post-recovery protection
 * leases that defend the exact window in which vendor freezers tend to relapse.
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
    const val PACKAGE_SUCCESSOR_CIRCUIT_DELIVERY_RESCUE_INTERVAL_MS = 2 * 60_000L
    const val PACKAGE_DELIVERY_PROTECTION_BASE_MS = 45_000L
    const val PACKAGE_DELIVERY_PROTECTION_EXTENSION_MS = 30_000L
    const val PACKAGE_DELIVERY_PROTECTION_MAX_MS = 90_000L
    const val PACKAGE_DELIVERY_PROTECTION_TICK_MS = 1_000L
    const val PACKAGE_DELIVERY_PROTECTION_KILL_MIN_ELAPSED_MS = 10_000L
    const val PACKAGE_DELIVERY_PROTECTION_MAX_KILLS = 1
    const val MANAGED_PACKAGE_WAKE_INTERVAL_MS = 5 * 60_000L
    const val MANAGED_PACKAGE_FROZEN_WAKE_DELAY_MS = 15_000L
    const val MANAGED_PACKAGE_FROZEN_WAKE_INTERVAL_MS = 5 * 60_000L
    const val MANAGED_PACKAGE_FROZEN_FOREGROUND_HOLD_MS = 8_000L

    const val GMS_CAMPAIGN_TICK_MS = 2_000L
    const val GMS_CAMPAIGN_DURATION_MS = 4 * 60_000L
    const val GMS_CAMPAIGN_STABLE_MS = 60_000L
    const val GMS_STABILIZATION_LEASE_MS = 120_000L
    const val GMS_PRECONNECTION_LEASE_REFRESH_MS = 30_000L
    const val GMS_VIVO_INITIAL_PRECONNECTION_WAIT_MS = 20_000L
    const val GMS_VIVO_AFTER_STOP_APP_WAIT_MS = 45_000L
    const val GMS_VIVO_AFTER_FORCE_STOP_WAIT_MS = 120_000L
    const val GMS_STABILIZATION_DEGRADED_GRACE_MS = 20_000L
    const val GMS_TRANSPORT_COLLAPSE_WINDOW_MS = 20_000L
    const val GMS_TRANSPORT_COLLAPSE_LIMIT = 3
    const val GMS_PHASE_EVALUATION_MS = 30_000L
    const val GMS_PHASE_REQUIRED_CONTINUOUS_HEALTHY_MS = 15_000L
    const val GMS_MIN_RESET_INTERVAL_MS = 10_000L
    const val GMS_MAX_RESETS_PER_CAMPAIGN = 4
    const val GMS_VIVO_MAX_RESETS_PER_CAMPAIGN = 2
    const val GMS_DEFAULT_MAX_FORCE_STOPS_PER_CAMPAIGN = 2
    const val GMS_VIVO_MAX_FORCE_STOPS_PER_CAMPAIGN = 1
    const val GMS_EMERGENCY_COOLDOWN_MS = 2 * 60_000L
    const val GMS_RETRY_AFTER_ONE_FAILURE_MS = 2 * 60_000L
    const val GMS_RETRY_AFTER_TWO_FAILURES_MS = 5 * 60_000L
    const val GMS_RETRY_AFTER_THREE_FAILURES_MS = 15 * 60_000L
    const val GMS_RETRY_AFTER_REPEATED_FAILURES_MS = 30 * 60_000L
    // r264: adaptive cooldown must not create a confirmed-delivery dead zone on
    // VIVO. A continuous missing-MCS episode with verified frozen GMS gets one
    // bounded cooldown bypass after this deadline. The caller is responsible
    // for allowing it only once per continuous transport-missing episode.
    const val GMS_VIVO_VERIFIED_OUTAGE_DEADLINE_MS = 30_000L
    const val GMS_VIVO_POST_SUCCESS_PROTECTION_MS = 120_000L
    const val GMS_VIVO_POST_SUCCESS_OUTAGE_DEADLINE_MS = 15_000L
    const val GMS_HARD_CAMPAIGN_LIMIT_PER_24_HOURS = 48
    const val GMS_FORCE_STOP_MIN_INTERVAL_MS = 10 * 60_000L
    const val GMS_FORCE_STOP_MAX_PER_24_HOURS = 6

    enum class PackageResetStrategy {
        KILL,
        STOP_APP,
        FORCE_STOP_UNSTOP
    }

    data class PackageDecision(val allowed: Boolean, val reason: String)
    data class GmsCampaignDecision(val allowed: Boolean, val reason: String)
    data class GmsForceStopDecision(val allowed: Boolean, val reason: String)

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
        lastCampaignCompletedElapsed: Long,
        campaignHistory: List<Long>,
        manual: Boolean,
        strongEvidence: Boolean,
        consecutiveFailureCount: Int = 0,
        verifiedOutageDeadlineReached: Boolean = false
    ): GmsCampaignDecision {
        if (nowElapsed < 0L) return GmsCampaignDecision(false, "invalid_clock")
        if (!manual && !strongEvidence) {
            return GmsCampaignDecision(false, "strong_evidence_missing")
        }
        if (lastCampaignCompletedElapsed > nowElapsed) {
            return GmsCampaignDecision(false, "elapsed_clock_reset")
        }

        val cutoff = (nowElapsed - DAY_MS).coerceAtLeast(0L)
        val recent = campaignHistory.count { it in cutoff..nowElapsed }
        if (!manual && recent >= GMS_HARD_CAMPAIGN_LIMIT_PER_24_HOURS) {
            return GmsCampaignDecision(false, "campaign_hard_daily_limit")
        }
        val retryInterval = if (manual) {
            GMS_EMERGENCY_COOLDOWN_MS
        } else {
            gmsAutomaticRetryIntervalMs(consecutiveFailureCount)
        }
        val insideRetryCooldown =
            lastCampaignCompletedElapsed > 0L &&
                nowElapsed - lastCampaignCompletedElapsed < retryInterval
        if (insideRetryCooldown && !verifiedOutageDeadlineReached) {
            return GmsCampaignDecision(false, "campaign_adaptive_cooldown")
        }

        return GmsCampaignDecision(
            true,
            when {
                manual -> "manual_campaign"
                insideRetryCooldown && verifiedOutageDeadlineReached ->
                    "verified_outage_deadline_rescue"
                consecutiveFailureCount <= 0 -> "verified_frozen_mcs_missing"
                else -> "verified_outage_adaptive_retry_$consecutiveFailureCount"
            }
        )
    }

    fun gmsAutomaticRetryIntervalMs(consecutiveFailureCount: Int): Long = when {
        consecutiveFailureCount <= 1 -> GMS_RETRY_AFTER_ONE_FAILURE_MS
        consecutiveFailureCount == 2 -> GMS_RETRY_AFTER_TWO_FAILURES_MS
        consecutiveFailureCount == 3 -> GMS_RETRY_AFTER_THREE_FAILURES_MS
        else -> GMS_RETRY_AFTER_REPEATED_FAILURES_MS
    }

    fun gmsVerifiedOutageDeadlineMs(postSuccessProtectionActive: Boolean): Long =
        if (postSuccessProtectionActive) {
            GMS_VIVO_POST_SUCCESS_OUTAGE_DEADLINE_MS
        } else {
            GMS_VIVO_VERIFIED_OUTAGE_DEADLINE_MS
        }

    fun shouldBypassGmsAdaptiveCooldown(
        vendorFamily: BackgroundPolicyVendorFamily,
        strongEvidence: Boolean,
        nowElapsed: Long,
        transportMissingSinceElapsed: Long,
        lastBypassedMissingEpisodeElapsed: Long,
        postSuccessProtectionActive: Boolean
    ): Boolean {
        if (vendorFamily != BackgroundPolicyVendorFamily.VIVO || !strongEvidence) return false
        if (nowElapsed < 0L) return false
        if (transportMissingSinceElapsed <= 0L || transportMissingSinceElapsed > nowElapsed) {
            return false
        }
        if (lastBypassedMissingEpisodeElapsed == transportMissingSinceElapsed) return false
        return nowElapsed - transportMissingSinceElapsed >=
            gmsVerifiedOutageDeadlineMs(postSuccessProtectionActive)
    }

    fun decideGmsForceStop(
        nowElapsed: Long,
        forceStopHistory: List<Long>
    ): GmsForceStopDecision {
        if (nowElapsed < 0L) return GmsForceStopDecision(false, "invalid_clock")
        val cutoff = (nowElapsed - DAY_MS).coerceAtLeast(0L)
        val recent = forceStopHistory.filter { it in cutoff..nowElapsed }
        if (recent.size >= GMS_FORCE_STOP_MAX_PER_24_HOURS) {
            return GmsForceStopDecision(false, "force_stop_daily_budget")
        }
        val last = recent.maxOrNull() ?: 0L
        if (last > 0L && nowElapsed - last < GMS_FORCE_STOP_MIN_INTERVAL_MS) {
            return GmsForceStopDecision(false, "force_stop_min_interval")
        }
        return GmsForceStopDecision(true, "force_stop_budget_available")
    }

    fun gmsMaxResetsPerCampaign(
        vendorFamily: BackgroundPolicyVendorFamily
    ): Int = if (vendorFamily == BackgroundPolicyVendorFamily.VIVO) {
        GMS_VIVO_MAX_RESETS_PER_CAMPAIGN
    } else {
        GMS_MAX_RESETS_PER_CAMPAIGN
    }

    fun gmsMaxForceStopsPerCampaign(
        vendorFamily: BackgroundPolicyVendorFamily
    ): Int = if (vendorFamily == BackgroundPolicyVendorFamily.VIVO) {
        GMS_VIVO_MAX_FORCE_STOPS_PER_CAMPAIGN
    } else {
        GMS_DEFAULT_MAX_FORCE_STOPS_PER_CAMPAIGN
    }

    fun gmsInitialResetDelayMs(
        vendorFamily: BackgroundPolicyVendorFamily
    ): Long = if (vendorFamily == BackgroundPolicyVendorFamily.VIVO) {
        GMS_VIVO_INITIAL_PRECONNECTION_WAIT_MS
    } else {
        0L
    }

    fun gmsPostResetWaitMs(
        vendorFamily: BackgroundPolicyVendorFamily,
        resetCount: Int,
        forceStopCount: Int
    ): Long = if (vendorFamily == BackgroundPolicyVendorFamily.VIVO) {
        when {
            forceStopCount > 0 -> Long.MAX_VALUE
            resetCount <= 1 -> GMS_VIVO_AFTER_STOP_APP_WAIT_MS
            else -> GMS_VIVO_AFTER_FORCE_STOP_WAIT_MS
        }
    } else {
        GMS_MIN_RESET_INTERVAL_MS
    }

    fun shouldUseForceStopForGms(
        vendorFamily: BackgroundPolicyVendorFamily,
        resetCount: Int,
        refreezeCount: Int,
        forceStopCount: Int
    ): Boolean {
        if (forceStopCount >= gmsMaxForceStopsPerCampaign(vendorFamily)) return false
        return when (vendorFamily) {
            BackgroundPolicyVendorFamily.VIVO ->
                resetCount >= 2
            else ->
                resetCount > 1 || refreezeCount > 0
        }
    }

    fun shouldHoldAnchorInsteadOfFallbackStopApp(
        vendorFamily: BackgroundPolicyVendorFamily,
        nextResetCount: Int,
        forceStopWanted: Boolean,
        forceStopAllowed: Boolean
    ): Boolean =
        vendorFamily == BackgroundPolicyVendorFamily.VIVO &&
            nextResetCount >= 2 &&
            forceStopWanted &&
            !forceStopAllowed

    fun shouldEscalateGmsTransportFlapping(
        nowElapsed: Long,
        phaseStartedElapsed: Long,
        longestContinuousTransportMs: Long,
        collapseWindowStartedElapsed: Long,
        collapseCountInWindow: Int
    ): Boolean {
        if (
            nowElapsed < 0L ||
            phaseStartedElapsed <= 0L ||
            phaseStartedElapsed > nowElapsed ||
            longestContinuousTransportMs < 0L ||
            collapseCountInWindow < 0
        ) {
            return false
        }
        val repeatedCollapse =
            collapseCountInWindow >= GMS_TRANSPORT_COLLAPSE_LIMIT &&
                collapseWindowStartedElapsed in 1L..nowElapsed &&
                nowElapsed - collapseWindowStartedElapsed <=
                    GMS_TRANSPORT_COLLAPSE_WINDOW_MS
        val neverReachedUsefulStability =
            nowElapsed - phaseStartedElapsed >= GMS_PHASE_EVALUATION_MS &&
                longestContinuousTransportMs <
                    GMS_PHASE_REQUIRED_CONTINUOUS_HEALTHY_MS
        return repeatedCollapse || neverReachedUsefulStability
    }

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

    fun deliveryProtectionDeadline(
        nowElapsed: Long,
        startedElapsed: Long,
        currentDeadlineElapsed: Long,
        newDeliveryEpisode: Boolean
    ): Long {
        if (nowElapsed < 0L || startedElapsed < 0L) return 0L
        val hardDeadline = startedElapsed + PACKAGE_DELIVERY_PROTECTION_MAX_MS
        val desired = if (currentDeadlineElapsed <= 0L) {
            nowElapsed + PACKAGE_DELIVERY_PROTECTION_BASE_MS
        } else if (newDeliveryEpisode) {
            maxOf(currentDeadlineElapsed, nowElapsed + PACKAGE_DELIVERY_PROTECTION_EXTENSION_MS)
        } else {
            currentDeadlineElapsed
        }
        return minOf(desired, hardDeadline)
    }

    fun shouldEscalateDeliveryProtectionKill(
        nowElapsed: Long,
        startedElapsed: Long,
        deliveryEpisodeCount: Int,
        killCount: Int,
        verifiedFrozen: Boolean
    ): Boolean =
        verifiedFrozen &&
            deliveryEpisodeCount >= 2 &&
            killCount < PACKAGE_DELIVERY_PROTECTION_MAX_KILLS &&
            startedElapsed in 0L..nowElapsed &&
            nowElapsed - startedElapsed >= PACKAGE_DELIVERY_PROTECTION_KILL_MIN_ELAPSED_MS

    fun shouldAttemptCircuitDeliveryRescue(
        nowElapsed: Long,
        circuitUntilElapsed: Long,
        lastRescueElapsed: Long,
        verifiedFrozen: Boolean
    ): Boolean {
        if (
            !verifiedFrozen ||
            nowElapsed < 0L ||
            circuitUntilElapsed <= nowElapsed ||
            lastRescueElapsed > nowElapsed
        ) {
            return false
        }
        if (lastRescueElapsed <= 0L) return true
        return nowElapsed - lastRescueElapsed >=
            PACKAGE_SUCCESSOR_CIRCUIT_DELIVERY_RESCUE_INTERVAL_MS
    }

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
        transportHealthy: Boolean,
        maxResetCount: Int = GMS_MAX_RESETS_PER_CAMPAIGN
    ): Boolean {
        if (nowElapsed < 0L || lastResetElapsed > nowElapsed) return false
        if (resetCount >= maxResetCount.coerceAtLeast(0)) return false
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
