package com.yubegreen.luonnotar.privileged

/**
 * r296 keeps "make GMS runnable" separate from "make FCM/MCS connected", but
 * fixes two real-device ownership failures observed on OriginOS:
 *
 * 1. fast_freezer can flip cgroup.freeze to 1 for well under one scheduler tick.
 *    A single frozen sample is therefore not a terminal handoff signal.
 * 2. a physically-thawed MCS outage can survive reconnect + stop-app for minutes.
 *    After a long continuous no-healthy window, transport recovery may request one
 *    force-stop/unstop transition, but the service must still pass the existing
 *    global 10-minute / 24-hour force-stop budget before executing it.
 *
 * The ordinary path remains non-destructive: Binder stabilization + bounded
 * GCM_RECONNECT, followed by at most one stop-app soft reset. BAD_AUTHENTICATION
 * keeps the bootstrap in an auth-settle window while it is recent; it is evidence
 * to wait, not an immediate reason to kill GMS.
 */
object GmsThawedTransportBootstrapPolicy {
    enum class Phase(val wireName: String) {
        RECONNECT("reconnect"),
        AUTH_SETTLE("auth_settle"),
        FREEZER_SETTLE("freezer_settle"),
        POST_SOFT_RESET("post_soft_reset"),
        POST_HARD_RESET("post_hard_reset"),
        STABLE("stable")
    }

    data class StartDecision(
        val start: Boolean,
        val reason: String,
        val authSuspected: Boolean = false,
        val allowSoftReset: Boolean = false,
        val allowHardReset: Boolean = false
    )

    data class TickDecision(
        val phase: Phase,
        val sendReconnect: Boolean = false,
        val refreshLease: Boolean = false,
        val softReset: Boolean = false,
        val hardReset: Boolean = false,
        val finishResult: String? = null,
        val reason: String
    )

    data class FinishDecision(
        val success: Boolean,
        val result: String
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

    // fast_freezer edges observed on the target are normally cleared in well
    // under a second. Require a genuinely continuous freeze before giving the
    // transport bootstrap back to the freezer/process campaign.
    const val PHYSICAL_REFREEZE_HANDOFF_MS = 12_000L

    const val SOFT_RESET_AFTER_MS = 2 * 60_000L
    const val POST_SOFT_RESET_GRACE_MS = 45_000L

    // A force-stop is deliberately much later than ordinary rebuilds. Real
    // fast-freezer collapses usually rebuild 5228 in ~3-8 seconds; a hard reset
    // is only requested after three minutes in the same bootstrap and at least
    // one full minute without any healthy transport observation.
    const val HARD_RESET_AFTER_MS = 3 * 60_000L
    const val HARD_RESET_NO_HEALTHY_MS = 60_000L
    const val HARD_RESET_GATE_RETRY_MS = 30_000L
    const val POST_HARD_RESET_GRACE_MS = 60_000L

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
                allowSoftReset = true,
                allowHardReset = true
            )
        }
        if (networkTransitionRecent && consecutiveMissing >= 1) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_missing_after_network_transition",
                authSuspected = false,
                allowSoftReset = true,
                allowHardReset = true
            )
        }
        if (recentFastFreezerEvidence && consecutiveMissing >= 2) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_missing_after_fast_freezer",
                authSuspected = false,
                allowSoftReset = true,
                allowHardReset = true
            )
        }
        if (mcsAttemptRecent && consecutiveMissing >= 2 && missingFor >= 30_000L) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_reconnect_stalled",
                authSuspected = false,
                allowSoftReset = true,
                allowHardReset = true
            )
        }
        if (consecutiveMissing >= 3 && missingFor >= SUSTAINED_START_MISSING_MS) {
            return StartDecision(
                start = true,
                reason = "thawed_mcs_sustained_missing",
                authSuspected = badAuthRecent,
                // r295 incorrectly tied this to a *recent* connect attempt. The
                // live eight-minute outage had a stale attempt timestamp, which
                // made the longest outage the least eligible for escalation.
                allowSoftReset = !badAuthRecent,
                allowHardReset = true
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
        physicalFrozenSinceElapsed: Long = 0L,
        mainRunning: Boolean,
        persistentRunning: Boolean,
        healthySinceElapsed: Long,
        lastHealthyObservedElapsed: Long = 0L,
        lastReconnectElapsed: Long,
        lastLeaseRefreshElapsed: Long,
        lastBadAuthenticationElapsed: Long,
        baselineBadAuthenticationCount: Long,
        currentBadAuthenticationCount: Long,
        authSuspected: Boolean,
        allowSoftReset: Boolean,
        allowHardReset: Boolean = false,
        softResetCount: Int,
        hardResetCount: Int = 0,
        lastHardResetGateCheckElapsed: Long = 0L,
        postSoftResetUntilElapsed: Long,
        postHardResetUntilElapsed: Long = 0L,
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
            val frozenFor = if (
                physicalFrozenSinceElapsed > 0L &&
                physicalFrozenSinceElapsed <= nowElapsed
            ) {
                nowElapsed - physicalFrozenSinceElapsed
            } else {
                0L
            }
            return if (frozenFor >= PHYSICAL_REFREEZE_HANDOFF_MS) {
                TickDecision(
                    phase = Phase.FREEZER_SETTLE,
                    finishResult = "refrozen",
                    reason = "persistent_physical_refreeze"
                )
            } else {
                TickDecision(
                    phase = Phase.FREEZER_SETTLE,
                    refreshLease = due(lastLeaseRefreshElapsed, nowElapsed, LEASE_REFRESH_MS),
                    reason = "transient_physical_refreeze"
                )
            }
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

        if (postHardResetUntilElapsed > nowElapsed) {
            return TickDecision(
                phase = Phase.POST_HARD_RESET,
                sendReconnect = sendReconnect,
                refreshLease = refreshLease,
                reason = "post_hard_reset_grace"
            )
        }
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
            return TickDecision(
                phase = Phase.RECONNECT,
                softReset = true,
                reason = "stalled_mcs_soft_reset_once"
            )
        }

        val noHealthyFor = if (
            lastHealthyObservedElapsed > 0L &&
            lastHealthyObservedElapsed <= nowElapsed
        ) {
            nowElapsed - lastHealthyObservedElapsed
        } else {
            elapsed
        }
        val hardReset = allowHardReset &&
            hardResetCount == 0 &&
            elapsed >= HARD_RESET_AFTER_MS &&
            noHealthyFor >= HARD_RESET_NO_HEALTHY_MS &&
            authQuietLongEnough &&
            due(lastHardResetGateCheckElapsed, nowElapsed, HARD_RESET_GATE_RETRY_MS)

        if (hardReset) {
            return TickDecision(
                phase = Phase.RECONNECT,
                hardReset = true,
                reason = "persistent_thawed_transport_hard_reset_gate"
            )
        }

        return TickDecision(
            phase = Phase.RECONNECT,
            sendReconnect = sendReconnect,
            refreshLease = refreshLease,
            reason = "reconnect_and_hold"
        )
    }

    /**
     * r295 trusted the *requested* stable result even when its final probe had
     * already observed ports=[]; that produced `success=true ... ports=[]`.
     * A socket-based success now requires the final probe itself to be healthy.
     * A real controlled delivery remains stronger evidence and may succeed even
     * if a point-in-time socket sample races a freezer edge.
     */
    fun decideFinish(
        requestedResult: String,
        frozen: Boolean,
        finalTransportObservable: Boolean,
        finalTransportHealthy: Boolean,
        deliveryObserved: Boolean
    ): FinishDecision {
        val deliveryProof = requestedResult == "delivery_observed" || deliveryObserved
        if (deliveryProof) return FinishDecision(true, "delivery_observed")

        if (requestedResult == "stable_transport_verified") {
            return when {
                frozen -> FinishDecision(false, "final_refrozen")
                !finalTransportObservable -> FinishDecision(false, "final_transport_unobservable")
                !finalTransportHealthy -> FinishDecision(false, "final_transport_unhealthy")
                else -> FinishDecision(true, "stable_transport_verified")
            }
        }
        return FinishDecision(false, requestedResult)
    }

    fun backoffMs(result: String): Long = when (result) {
        "auth_stalled" -> AUTH_STALLED_BACKOFF_MS
        "refrozen", "final_refrozen" -> REFROZEN_BACKOFF_MS
        "process_missing" -> PROCESS_MISSING_BACKOFF_MS
        "stable_transport_verified", "delivery_observed" -> 0L
        else -> TRANSPORT_STALLED_BACKOFF_MS
    }

    private fun recent(at: Long, now: Long, window: Long): Boolean =
        at > 0L && at <= now && now - at <= window

    private fun due(last: Long, now: Long, interval: Long): Boolean =
        last <= 0L || last > now || now - last >= interval
}
