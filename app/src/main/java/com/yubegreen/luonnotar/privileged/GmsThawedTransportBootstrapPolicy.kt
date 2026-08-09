package com.yubegreen.luonnotar.privileged

/**
 * r295 separates "make GMS runnable" from "make FCM/MCS connected".
 *
 * The live OriginOS failure that motivated this policy had both GMS processes
 * physically thawed and important (procState=FGS/service) while MCS remained
 * absent for more than an hour and BAD_AUTHENTICATION continued. Repeating the
 * freezer/process-reset campaign in that state only burned destructive budget.
 *
 * This policy therefore owns only the thawed-transport phase. It is deliberately
 * conservative: reconnect broadcasts + the existing Binder importance lease are
 * the normal actions. One stop-app soft reset is permitted only for a stalled
 * MCS reconnect with no recent authentication failure. BAD_AUTHENTICATION is a
 * reason to preserve a stable process/network window, never a reason to force-stop.
 */
object GmsThawedTransportBootstrapPolicy {
    enum class Phase(val wireName: String) {
        RECONNECT("reconnect"),
        AUTH_SETTLE("auth_settle"),
        POST_SOFT_RESET("post_soft_reset"),
        STABLE("stable")
    }

    data class StartDecision(
        val start: Boolean,
        val reason: String,
        val authSuspected: Boolean = false,
        val allowSoftReset: Boolean = false
    )

    data class TickDecision(
        val phase: Phase,
        val sendReconnect: Boolean = false,
        val refreshLease: Boolean = false,
        val softReset: Boolean = false,
        val finishResult: String? = null,
        val reason: String
    )

    const val TICK_MS = 3_000L
    const val HEALTHY_STABLE_MS = 8_000L
    const val RECONNECT_INTERVAL_MS = 12_000L
    const val LEASE_REFRESH_MS = 30_000L
    const val MAX_DURATION_MS = 6 * 60_000L
    const val START_MIN_MISSING_MS = 15_000L
    const val SUSTAINED_START_MISSING_MS = 90_000L
    const val BAD_AUTH_RECENT_MS = 10 * 60_000L
    const val BAD_AUTH_QUIET_BEFORE_SOFT_RESET_MS = 90_000L
    const val MCS_ATTEMPT_RECENT_MS = 2 * 60_000L
    const val NETWORK_TRANSITION_RECENT_MS = 60_000L
    const val FREEZER_HANDOFF_MISSING_MS = 6_000L
    const val SOFT_RESET_AFTER_MS = 2 * 60_000L
    const val POST_SOFT_RESET_GRACE_MS = 45_000L
    const val AUTH_STALLED_BACKOFF_MS = 2 * 60_000L
    const val TRANSPORT_STALLED_BACKOFF_MS = 60_000L
    const val REFROZEN_BACKOFF_MS = 15_000L
    const val PROCESS_MISSING_BACKOFF_MS = 30_000L

    fun decideStart(
        isVivo: Boolean,
        vendorEmergencyEnabled: Boolean,
        nowElapsed: Long,
        transportObservable: Boolean,
        transportHealthy: Boolean,
        consecutiveMissing: Int,
        missingSinceElapsed: Long,
        mainRunning: Boolean,
        persistentRunning: Boolean,
        physicallyFrozen: Boolean,
        recoveryCampaignActive: Boolean,
        bootstrapActive: Boolean,
        backoffUntilElapsed: Long,
        lastBadAuthenticationElapsed: Long,
        lastMcsConnectAttemptElapsed: Long,
        lastNetworkTransitionElapsed: Long,
        recentFastFreezerEvidence: Boolean
    ): StartDecision {
        if (!isVivo) return StartDecision(false, "not_vivo")
        if (!vendorEmergencyEnabled) return StartDecision(false, "vendor_emergency_disabled")
        if (nowElapsed < 0L) return StartDecision(false, "invalid_clock")
        if (bootstrapActive) return StartDecision(false, "already_active")
        if (recoveryCampaignActive) return StartDecision(false, "freezer_campaign_active")
        if (nowElapsed < backoffUntilElapsed) return StartDecision(false, "backoff")
        if (!transportObservable) return StartDecision(false, "transport_unobservable")
        if (transportHealthy) return StartDecision(false, "transport_healthy")
        if (!mainRunning || !persistentRunning) return StartDecision(false, "gms_process_missing")
        if (physicallyFrozen) return StartDecision(false, "gms_physically_frozen")
        if (missingSinceElapsed <= 0L || missingSinceElapsed > nowElapsed) {
            return StartDecision(false, "missing_window_not_established")
        }

        val missingFor = nowElapsed - missingSinceElapsed
        if (missingFor < START_MIN_MISSING_MS) return StartDecision(false, "missing_too_short")

        val badAuthRecent = recent(lastBadAuthenticationElapsed, nowElapsed, BAD_AUTH_RECENT_MS)
        val mcsAttemptRecent = recent(lastMcsConnectAttemptElapsed, nowElapsed, MCS_ATTEMPT_RECENT_MS)
        val networkTransitionRecent = recent(
            lastNetworkTransitionElapsed,
            nowElapsed,
            NETWORK_TRANSITION_RECENT_MS
        )

        if (badAuthRecent && consecutiveMissing >= 2) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_missing_after_bad_auth",
                authSuspected = true,
                // BAD_AUTH itself never triggers the reset. A single stop-app
                // becomes eligible only after the auth error has stayed quiet
                // for BAD_AUTH_QUIET_BEFORE_SOFT_RESET_MS.
                allowSoftReset = true
            )
        }
        if (networkTransitionRecent && consecutiveMissing >= 1) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_missing_after_network_transition",
                authSuspected = false,
                // Network handoff can strand a long-lived MCS socket even when
                // the new Android network is VALIDATED. Give reconnect/lease a
                // full quiet window first, then permit one bounded stop-app.
                allowSoftReset = true
            )
        }
        if (recentFastFreezerEvidence && consecutiveMissing >= 2) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_missing_after_fast_freezer",
                authSuspected = false,
                // A previous physical freeze is a causal clue, but once both
                // cgroups read thawed the transport phase owns recovery. One
                // late soft reset is allowed; repeated freezer resets are not.
                allowSoftReset = true
            )
        }
        if (mcsAttemptRecent && consecutiveMissing >= 2 && missingFor >= 30_000L) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_reconnect_stalled",
                authSuspected = false,
                allowSoftReset = true
            )
        }
        if (consecutiveMissing >= 3 && missingFor >= SUSTAINED_START_MISSING_MS) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_sustained_missing",
                authSuspected = badAuthRecent,
                allowSoftReset = mcsAttemptRecent && !badAuthRecent
            )
        }
        return StartDecision(false, "insufficient_thawed_transport_evidence")
    }

    fun decideTick(
        nowElapsed: Long,
        startedElapsed: Long,
        deadlineElapsed: Long,
        transportObservable: Boolean,
        transportHealthy: Boolean,
        physicallyFrozen: Boolean,
        mainRunning: Boolean,
        persistentRunning: Boolean,
        healthySinceElapsed: Long,
        lastReconnectElapsed: Long,
        lastLeaseRefreshElapsed: Long,
        lastBadAuthenticationElapsed: Long,
        baselineBadAuthenticationCount: Long,
        currentBadAuthenticationCount: Long,
        authSuspected: Boolean,
        allowSoftReset: Boolean,
        softResetCount: Int,
        postSoftResetUntilElapsed: Long,
        deliveryObservedElapsed: Long
    ): TickDecision {
        if (!mainRunning || !persistentRunning) {
            return TickDecision(
                phase = Phase.RECONNECT,
                finishResult = "process_missing",
                reason = "gms_process_missing"
            )
        }
        if (physicallyFrozen) {
            return TickDecision(
                phase = Phase.RECONNECT,
                finishResult = "refrozen",
                reason = "freezer_owns_recovery"
            )
        }
        if (!transportObservable) {
            return TickDecision(
                phase = Phase.RECONNECT,
                finishResult = "transport_unobservable",
                reason = "transport_unobservable"
            )
        }
        if (deliveryObservedElapsed >= startedElapsed && deliveryObservedElapsed <= nowElapsed) {
            return TickDecision(
                phase = Phase.STABLE,
                finishResult = "delivery_observed",
                reason = "controlled_push_delivery_observed"
            )
        }
        if (transportHealthy) {
            val stableFor = if (healthySinceElapsed in 1..nowElapsed) {
                nowElapsed - healthySinceElapsed
            } else {
                0L
            }
            return TickDecision(
                phase = Phase.STABLE,
                refreshLease = due(lastLeaseRefreshElapsed, nowElapsed, LEASE_REFRESH_MS),
                finishResult = if (stableFor >= HEALTHY_STABLE_MS) "stable_transport_verified" else null,
                reason = if (stableFor >= HEALTHY_STABLE_MS) "transport_stable" else "stability_window"
            )
        }

        val observedNewBadAuth = currentBadAuthenticationCount > baselineBadAuthenticationCount
        val badAuthActive = recent(
            lastBadAuthenticationElapsed,
            nowElapsed,
            BAD_AUTH_QUIET_BEFORE_SOFT_RESET_MS
        )
        val authActive = badAuthActive && (observedNewBadAuth || authSuspected)
        if (nowElapsed >= deadlineElapsed) {
            return TickDecision(
                phase = if (authActive) Phase.AUTH_SETTLE else Phase.RECONNECT,
                finishResult = if (authActive) "auth_stalled" else "transport_stalled",
                reason = "bootstrap_deadline"
            )
        }

        val sendReconnect = due(lastReconnectElapsed, nowElapsed, RECONNECT_INTERVAL_MS)
        val refreshLease = due(lastLeaseRefreshElapsed, nowElapsed, LEASE_REFRESH_MS)

        if (postSoftResetUntilElapsed > nowElapsed) {
            return TickDecision(
                phase = Phase.POST_SOFT_RESET,
                sendReconnect = sendReconnect,
                refreshLease = refreshLease,
                reason = "post_soft_reset_grace"
            )
        }

        if (authActive) {
            return TickDecision(
                phase = Phase.AUTH_SETTLE,
                sendReconnect = sendReconnect,
                refreshLease = refreshLease,
                reason = "bad_auth_quiet_settle"
            )
        }

        val elapsed = (nowElapsed - startedElapsed).coerceAtLeast(0L)
        val authQuietLongEnough = lastBadAuthenticationElapsed <= 0L ||
            lastBadAuthenticationElapsed > nowElapsed ||
            nowElapsed - lastBadAuthenticationElapsed >= BAD_AUTH_QUIET_BEFORE_SOFT_RESET_MS
        val softReset = allowSoftReset &&
            softResetCount == 0 &&
            elapsed >= SOFT_RESET_AFTER_MS &&
            authQuietLongEnough

        if (softReset) {
            // The soft reset path itself re-establishes the Binder lease and
            // emits exactly one reconnect after the successor processes are up.
            // Do not also send the ordinary maintenance lease/reconnect in the
            // same tick: that would duplicate actions around the only allowed
            // process reset and make recovery timing harder to reason about.
            return TickDecision(
                phase = Phase.RECONNECT,
                softReset = true,
                reason = "stalled_mcs_soft_reset_once"
            )
        }

        return TickDecision(
            phase = Phase.RECONNECT,
            sendReconnect = sendReconnect,
            refreshLease = refreshLease,
            reason = "reconnect_and_hold"
        )
    }

    fun backoffMs(result: String): Long = when (result) {
        "auth_stalled" -> AUTH_STALLED_BACKOFF_MS
        "refrozen" -> REFROZEN_BACKOFF_MS
        "process_missing" -> PROCESS_MISSING_BACKOFF_MS
        "stable_transport_verified", "delivery_observed" -> 0L
        else -> TRANSPORT_STALLED_BACKOFF_MS
    }

    private fun recent(at: Long, now: Long, window: Long): Boolean =
        at > 0L && at <= now && now - at <= window

    private fun due(last: Long, now: Long, interval: Long): Boolean =
        last <= 0L || last > now || now - last >= interval
}
