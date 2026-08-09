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

}
