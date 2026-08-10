package com.yubegreen.luonnotar.privileged

/**
 * r297 keeps the short-lived bootstrap worker separate from the longer-lived
 * transport incident. A bootstrap generation may time out and restart; the
 * incident owns destructive-tier consumption and recovery probation across all
 * of those generations.
 *
 * OriginOS repeatedly demonstrated that an 8-second 5228 window is only proof
 * that the socket came back, not that the outage is over. r297 therefore uses
 * three levels of evidence:
 *
 * 1. 8 seconds of continuous 5228 health => socket_recovered only.
 * 2. 120 seconds of recovery probation, tolerating collapses shorter than 30s
 *    => incident_recovered.
 * 3. A controlled delivery after incident start => strongest immediate proof.
 *
 * Soft/hard reset counts passed to this policy are incident-scoped. The service
 * must not reset them merely because a new bootstrap generation starts.
 */
object GmsThawedTransportBootstrapPolicy {
    enum class Phase(val wireName: String) {
        RECONNECT("reconnect"),
        AUTH_SETTLE("auth_settle"),
        FREEZER_SETTLE("freezer_settle"),
        POST_SOFT_RESET("post_soft_reset"),
        POST_HARD_RESET("post_hard_reset"),
        RECOVERY_PROBATION("recovery_probation"),
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

    // 8s is deliberately retained as a socket-level signal only. It is no
    // longer sufficient to close the outage incident.
    const val SOCKET_RECOVERED_STABLE_MS = 8_000L
    @Deprecated("Use SOCKET_RECOVERED_STABLE_MS; this is not an incident success threshold")
    const val HEALTHY_STABLE_MS = SOCKET_RECOVERED_STABLE_MS

    // Long-run acceptance window. Short OriginOS fast_freezer collapses are
    // allowed inside the probation, but a >=30s collapse invalidates it.
    const val INCIDENT_RECOVERY_PROBATION_MS = 2 * 60_000L
    const val INCIDENT_MAX_TRANSIENT_COLLAPSE_MS = 30_000L

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

    // A single frozen sample is not a terminal ownership handoff.
    const val PHYSICAL_REFREEZE_HANDOFF_MS = 12_000L

    const val SOFT_RESET_AFTER_MS = 2 * 60_000L
    const val POST_SOFT_RESET_GRACE_MS = 45_000L

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
                allowSoftReset = !badAuthRecent,
                allowHardReset = true
            )
        }
        return StartDecision(false, "insufficient_thawed_transport_evidence")
    }

    /**
     * [startedElapsed] is the *incident* start, not the current bootstrap
     * generation start. [deadlineElapsed] remains generation-scoped.
     */
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
        incidentProbationStartedElapsed: Long = 0L,
        incidentCurrentOutageSinceElapsed: Long = 0L,
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
            if (stableFor < SOCKET_RECOVERED_STABLE_MS) {
                return TickDecision(
                    phase = Phase.STABLE,
                    refreshLease = due(lastLeaseRefreshElapsed, nowElapsed, LEASE_REFRESH_MS),
                    reason = "socket_stability_window"
                )
            }
            val probationFor = if (
                incidentProbationStartedElapsed > 0L &&
                incidentProbationStartedElapsed <= nowElapsed
            ) {
                nowElapsed - incidentProbationStartedElapsed
            } else {
                0L
            }
            return TickDecision(
                phase = Phase.RECOVERY_PROBATION,
                refreshLease = due(lastLeaseRefreshElapsed, nowElapsed, LEASE_REFRESH_MS),
                finishResult = if (probationFor >= INCIDENT_RECOVERY_PROBATION_MS) {
                    "incident_recovered"
                } else {
                    null
                },
                reason = if (probationFor >= INCIDENT_RECOVERY_PROBATION_MS) {
                    "incident_recovery_probation_satisfied"
                } else {
                    "socket_recovered_probation"
                }
            )
        }

        val observedNewBadAuth = currentBadAuthenticationCount > baselineBadAuthenticationCount
        val badAuthActive = recent(
            lastBadAuthenticationElapsed,
            nowElapsed,
            BAD_AUTH_QUIET_BEFORE_SOFT_RESET_MS
        )
        val authActive = badAuthActive && (observedNewBadAuth || authSuspected)

        // Once a recovery probation has begun, a short collapse is allowed to
        // cross a bootstrap generation deadline. A >=30s collapse invalidates
        // the probation in the service before this policy is called, so the
        // ordinary deadline becomes active again.
        val probationShortCollapse =
            incidentProbationStartedElapsed > 0L &&
                incidentCurrentOutageSinceElapsed > 0L &&
                incidentCurrentOutageSinceElapsed <= nowElapsed &&
                nowElapsed - incidentCurrentOutageSinceElapsed < INCIDENT_MAX_TRANSIENT_COLLAPSE_MS
        if (nowElapsed >= deadlineElapsed && !probationShortCollapse) {
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
                reason = "incident_stalled_mcs_soft_reset_once"
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
                reason = "incident_persistent_transport_hard_reset_gate"
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
     * A socket-based incident success still requires the final probe to be
     * healthy. Controlled delivery remains stronger than a point-in-time socket
     * race and can close the incident even if a freezer edge wins the final
     * sample.
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

        if (requestedResult == "incident_recovered" || requestedResult == "stable_transport_verified") {
            return when {
                frozen -> FinishDecision(false, "final_refrozen")
                !finalTransportObservable -> FinishDecision(false, "final_transport_unobservable")
                !finalTransportHealthy -> FinishDecision(false, "final_transport_unhealthy")
                else -> FinishDecision(true, "incident_recovered")
            }
        }
        return FinishDecision(false, requestedResult)
    }

    fun backoffMs(result: String): Long = when (result) {
        "auth_stalled" -> AUTH_STALLED_BACKOFF_MS
        "refrozen", "final_refrozen" -> REFROZEN_BACKOFF_MS
        "process_missing" -> PROCESS_MISSING_BACKOFF_MS
        "incident_recovered", "stable_transport_verified", "delivery_observed" -> 0L
        else -> TRANSPORT_STALLED_BACKOFF_MS
    }

    private fun recent(at: Long, now: Long, window: Long): Boolean =
        at > 0L && at <= now && now - at <= window

    private fun due(last: Long, now: Long, interval: Long): Boolean =
        last <= 0L || last > now || now - last >= interval
}
