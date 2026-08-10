package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsThawedTransportBootstrapPolicyTest {
    @Test
    fun badAuthStartsNonDestructiveAuthSettle() {
        val decision = GmsThawedTransportBootstrapPolicy.decideStart(
            isVivo = true,
            vendorEmergencyEnabled = true,
            nowElapsed = 1_000_000L,
            transportObservable = true,
            transportHealthy = false,
            consecutiveMissing = 5,
            missingSinceElapsed = 900_000L,
            mainRunning = true,
            persistentRunning = true,
            physicallyFrozen = false,
            recoveryCampaignActive = false,
            bootstrapActive = false,
            backoffUntilElapsed = 0L,
            lastBadAuthenticationElapsed = 990_000L,
            lastMcsConnectAttemptElapsed = 0L,
            lastNetworkTransitionElapsed = 0L,
            recentFastFreezerEvidence = false
        )
        assertTrue(decision.start)
        assertTrue(decision.authSuspected)
        assertTrue(decision.allowSoftReset)
        assertEquals("thawed_mcs_missing_after_bad_auth", decision.reason)
    }

    @Test
    fun frozenGmsIsOwnedByFreezerCampaign() {
        val decision = GmsThawedTransportBootstrapPolicy.decideStart(
            isVivo = true,
            vendorEmergencyEnabled = true,
            nowElapsed = 1_000_000L,
            transportObservable = true,
            transportHealthy = false,
            consecutiveMissing = 50,
            missingSinceElapsed = 500_000L,
            mainRunning = true,
            persistentRunning = true,
            physicallyFrozen = true,
            recoveryCampaignActive = false,
            bootstrapActive = false,
            backoffUntilElapsed = 0L,
            lastBadAuthenticationElapsed = 990_000L,
            lastMcsConnectAttemptElapsed = 990_000L,
            lastNetworkTransitionElapsed = 990_000L,
            recentFastFreezerEvidence = true
        )
        assertFalse(decision.start)
        assertEquals("gms_physically_frozen", decision.reason)
    }

    @Test
    fun badAuthTickNeverRequestsSoftReset() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 400_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastReconnectElapsed = 350_000L,
            lastLeaseRefreshElapsed = 350_000L,
            lastBadAuthenticationElapsed = 399_000L,
            baselineBadAuthenticationCount = 10,
            currentBadAuthenticationCount = 11,
            authSuspected = true,
            allowSoftReset = true,
            softResetCount = 0,
            postSoftResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertEquals(GmsThawedTransportBootstrapPolicy.Phase.AUTH_SETTLE, decision.phase)
        assertFalse(decision.softReset)
    }

    @Test
    fun stalledReconnectMaySoftResetExactlyOnceAfterQuietWindow() {
        val first = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 250_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastReconnectElapsed = 240_000L,
            lastLeaseRefreshElapsed = 240_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            softResetCount = 0,
            postSoftResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertTrue(first.softReset)
        assertFalse(first.sendReconnect)
        assertFalse(first.refreshLease)

        val second = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 300_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastReconnectElapsed = 290_000L,
            lastLeaseRefreshElapsed = 290_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            softResetCount = 1,
            postSoftResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertFalse(second.softReset)
    }

    @Test
    fun controlledDeliveryIsStrongerThanPointInTimeSocketAbsence() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 200_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastReconnectElapsed = 0L,
            lastLeaseRefreshElapsed = 0L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = false,
            softResetCount = 0,
            postSoftResetUntilElapsed = 0L,
            deliveryObservedElapsed = 150_000L
        )
        assertEquals("delivery_observed", decision.finishResult)
    }
    @Test
    fun networkTransitionGetsOneLateSoftResetButNotImmediateDestruction() {
        val start = GmsThawedTransportBootstrapPolicy.decideStart(
            isVivo = true,
            vendorEmergencyEnabled = true,
            nowElapsed = 1_000_000L,
            transportObservable = true,
            transportHealthy = false,
            consecutiveMissing = 2,
            missingSinceElapsed = 970_000L,
            mainRunning = true,
            persistentRunning = true,
            physicallyFrozen = false,
            recoveryCampaignActive = false,
            bootstrapActive = false,
            backoffUntilElapsed = 0L,
            lastBadAuthenticationElapsed = 0L,
            lastMcsConnectAttemptElapsed = 0L,
            lastNetworkTransitionElapsed = 990_000L,
            recentFastFreezerEvidence = false
        )
        assertTrue(start.start)
        assertTrue(start.allowSoftReset)
        assertEquals("thawed_mcs_missing_after_network_transition", start.reason)

        val early = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 1_050_000L,
            startedElapsed = 1_000_000L,
            deadlineElapsed = 1_360_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastReconnectElapsed = 1_040_000L,
            lastLeaseRefreshElapsed = 1_040_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = start.allowSoftReset,
            softResetCount = 0,
            postSoftResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertFalse(early.softReset)

        val late = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 1_130_000L,
            startedElapsed = 1_000_000L,
            deadlineElapsed = 1_360_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastReconnectElapsed = 1_120_000L,
            lastLeaseRefreshElapsed = 1_120_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = start.allowSoftReset,
            softResetCount = 0,
            postSoftResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertTrue(late.softReset)
        assertFalse(late.sendReconnect)
        assertFalse(late.refreshLease)
    }


    @Test
    fun sustainedMissingWithStaleMcsAttemptStillGetsBoundedEscalation() {
        val decision = GmsThawedTransportBootstrapPolicy.decideStart(
            isVivo = true,
            vendorEmergencyEnabled = true,
            nowElapsed = 1_000_000L,
            transportObservable = true,
            transportHealthy = false,
            consecutiveMissing = 50,
            missingSinceElapsed = 700_000L,
            mainRunning = true,
            persistentRunning = true,
            physicallyFrozen = false,
            recoveryCampaignActive = false,
            bootstrapActive = false,
            backoffUntilElapsed = 0L,
            lastBadAuthenticationElapsed = 0L,
            lastMcsConnectAttemptElapsed = 100_000L,
            lastNetworkTransitionElapsed = 0L,
            recentFastFreezerEvidence = false
        )
        assertTrue(decision.start)
        assertTrue(decision.allowSoftReset)
        assertTrue(decision.allowHardReset)
        assertEquals("thawed_mcs_sustained_missing", decision.reason)
    }

    @Test
    fun transientPhysicalRefreezeDoesNotEndBootstrap() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 200_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = true,
            physicalFrozenSinceElapsed = 195_000L,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastReconnectElapsed = 190_000L,
            lastLeaseRefreshElapsed = 190_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            softResetCount = 0,
            postSoftResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertEquals(GmsThawedTransportBootstrapPolicy.Phase.FREEZER_SETTLE, decision.phase)
        assertEquals(null, decision.finishResult)
        assertFalse(decision.softReset)
        assertFalse(decision.hardReset)
    }

    @Test
    fun continuousPhysicalRefreezeEventuallyHandsBackToFreezerCampaign() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 220_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = true,
            physicalFrozenSinceElapsed = 200_000L,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastReconnectElapsed = 0L,
            lastLeaseRefreshElapsed = 0L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            softResetCount = 0,
            postSoftResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertEquals("refrozen", decision.finishResult)
        assertEquals("persistent_physical_refreeze", decision.reason)
    }

    @Test
    fun longNoHealthyWindowRequestsOneHardResetGate() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 310_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastHealthyObservedElapsed = 0L,
            lastReconnectElapsed = 300_000L,
            lastLeaseRefreshElapsed = 300_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            allowHardReset = true,
            softResetCount = 1,
            hardResetCount = 0,
            lastHardResetGateCheckElapsed = 0L,
            postSoftResetUntilElapsed = 0L,
            postHardResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertTrue(decision.hardReset)
        assertFalse(decision.sendReconnect)
        assertFalse(decision.refreshLease)
    }

    @Test
    fun recentHealthyEdgePreventsHardResetEscalation() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 310_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            lastHealthyObservedElapsed = 280_000L,
            lastReconnectElapsed = 300_000L,
            lastLeaseRefreshElapsed = 300_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            allowHardReset = true,
            softResetCount = 1,
            hardResetCount = 0,
            lastHardResetGateCheckElapsed = 0L,
            postSoftResetUntilElapsed = 0L,
            postHardResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertFalse(decision.hardReset)
    }

    @Test
    fun finalSocketProbeCanVetoStaleStableResult() {
        val finish = GmsThawedTransportBootstrapPolicy.decideFinish(
            requestedResult = "stable_transport_verified",
            frozen = false,
            finalTransportObservable = true,
            finalTransportHealthy = false,
            deliveryObserved = false
        )
        assertFalse(finish.success)
        assertEquals("final_transport_unhealthy", finish.result)
    }

    @Test
    fun controlledDeliveryRemainsStrongerThanFinalSocketRace() {
        val finish = GmsThawedTransportBootstrapPolicy.decideFinish(
            requestedResult = "stable_transport_verified",
            frozen = true,
            finalTransportObservable = true,
            finalTransportHealthy = false,
            deliveryObserved = true
        )
        assertTrue(finish.success)
        assertEquals("delivery_observed", finish.result)
    }

    @Test
    fun eightSecondSocketRecoveryDoesNotCloseIncident() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 210_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 500_000L,
            transportObservable = true,
            transportHealthy = true,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 200_000L,
            incidentProbationStartedElapsed = 205_000L,
            lastReconnectElapsed = 0L,
            lastLeaseRefreshElapsed = 0L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            allowHardReset = true,
            softResetCount = 1,
            hardResetCount = 1,
            postSoftResetUntilElapsed = 0L,
            postHardResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertEquals(GmsThawedTransportBootstrapPolicy.Phase.RECOVERY_PROBATION, decision.phase)
        assertEquals(null, decision.finishResult)
    }

    @Test
    fun probationClosesIncidentOnlyAfterTwoMinutes() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 330_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 300_000L,
            transportObservable = true,
            transportHealthy = true,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 300_000L,
            incidentProbationStartedElapsed = 200_000L,
            lastReconnectElapsed = 0L,
            lastLeaseRefreshElapsed = 0L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            allowHardReset = true,
            softResetCount = 1,
            hardResetCount = 1,
            postSoftResetUntilElapsed = 0L,
            postHardResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertEquals("incident_recovered", decision.finishResult)
    }

    @Test
    fun shortCollapseInsideProbationMayCrossBootstrapDeadline() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 365_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 360_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            incidentProbationStartedElapsed = 300_000L,
            incidentCurrentOutageSinceElapsed = 350_000L,
            lastReconnectElapsed = 350_000L,
            lastLeaseRefreshElapsed = 350_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            allowHardReset = true,
            softResetCount = 1,
            hardResetCount = 1,
            postSoftResetUntilElapsed = 0L,
            postHardResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertEquals(null, decision.finishResult)
        assertEquals(GmsThawedTransportBootstrapPolicy.Phase.RECONNECT, decision.phase)
    }

    @Test
    fun longCollapseAfterProbationDoesNotReceiveDeadlineProtection() {
        val decision = GmsThawedTransportBootstrapPolicy.decideTick(
            nowElapsed = 390_000L,
            startedElapsed = 100_000L,
            deadlineElapsed = 360_000L,
            transportObservable = true,
            transportHealthy = false,
            physicallyFrozen = false,
            mainRunning = true,
            persistentRunning = true,
            healthySinceElapsed = 0L,
            incidentProbationStartedElapsed = 300_000L,
            incidentCurrentOutageSinceElapsed = 350_000L,
            lastReconnectElapsed = 350_000L,
            lastLeaseRefreshElapsed = 350_000L,
            lastBadAuthenticationElapsed = 0L,
            baselineBadAuthenticationCount = 0,
            currentBadAuthenticationCount = 0,
            authSuspected = false,
            allowSoftReset = true,
            allowHardReset = true,
            softResetCount = 1,
            hardResetCount = 1,
            postSoftResetUntilElapsed = 0L,
            postHardResetUntilElapsed = 0L,
            deliveryObservedElapsed = 0L
        )
        assertEquals("transport_stalled", decision.finishResult)
    }

}
