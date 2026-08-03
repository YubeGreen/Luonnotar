package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedGuardianStatePolicyTest {
    @Test fun cachedShellUidWithoutCurrentRuntimeDoesNotRestoreConnected() {
        val state = EmbeddedGuardianStatePolicy.normalizePersisted(
            featureEnabled = true,
            setupState = EmbeddedSetupState.IDLE,
            connectionState = EmbeddedConnectionState.CONNECTED,
            reportedUid = 2_000,
            generation = 7L,
            runtimeOwnerIsCurrent = false
        )

        assertEquals(EmbeddedConnectionState.DISCONNECTED, state.connectionState)
        assertEquals(-1, state.reportedUid)
        assertFalse(state.liveConnected)
        assertTrue(EmbeddedGuardianStatePolicy.presentation(state).summary.contains("引擎未运行"))
    }

    @Test fun disconnectedFeatureCanBeDisabledWithoutRemoteEngine() {
        val enabled = runtime(
            setupState = EmbeddedSetupState.IDLE,
            connectionState = EmbeddedConnectionState.DISCONNECTED
        )
        val plan = EmbeddedGuardianStatePolicy.disablePlan(enabled, identityAvailable = false)
        val disabled = EmbeddedGuardianStatePolicy.disabledState(enabled)

        assertFalse(plan.attemptRemoteStop)
        assertFalse(disabled.featureEnabled)
        assertEquals(EmbeddedSetupState.IDLE, disabled.setupState)
        assertEquals(EmbeddedConnectionState.DISCONNECTED, disabled.connectionState)
        assertEquals(-1, disabled.reportedUid)
        assertEquals(enabled.generation + 1L, disabled.generation)
    }

    @Test fun waitingStartNeverPresentsAsRunningAndCanAlwaysClose() {
        val state = runtime(
            setupState = EmbeddedSetupState.STARTING,
            connectionState = EmbeddedConnectionState.CONNECTED,
            reportedUid = 2_000
        )
        val presentation = EmbeddedGuardianStatePolicy.presentation(state)

        assertTrue(presentation.summary.contains("正在配对或启动"))
        assertFalse(presentation.summary.contains("运行中"))
        assertFalse(presentation.privilegedOperationsEnabled)
        assertTrue(presentation.stopEnabled)
    }

    @Test fun deadConnectionClearsUidDisablesOperationsButKeepsCloseEnabled() {
        val state = EmbeddedGuardianStatePolicy.normalizePersisted(
            featureEnabled = true,
            setupState = EmbeddedSetupState.IDLE,
            connectionState = EmbeddedConnectionState.DEAD,
            reportedUid = 2_000,
            generation = 4L,
            runtimeOwnerIsCurrent = true
        )
        val presentation = EmbeddedGuardianStatePolicy.presentation(state)

        assertEquals(-1, state.reportedUid)
        assertTrue(presentation.summary.contains("连接已失效"))
        assertFalse(presentation.privilegedOperationsEnabled)
        assertTrue(presentation.stopEnabled)
    }

    @Test fun lateSetupCompletionAfterDisableCannotReviveFeature() {
        val enabled = runtime(generation = 20L)
        val disabled = EmbeddedGuardianStatePolicy.disabledState(enabled)

        assertFalse(
            EmbeddedGuardianStatePolicy.acceptsAsyncUpdate(
                featureEnabled = disabled.featureEnabled,
                expectedGeneration = enabled.generation,
                currentGeneration = disabled.generation
            )
        )
    }

    @Test fun activityOrApplicationProcessRestartDiscardsRuntimeEvidence() {
        val restored = EmbeddedGuardianStatePolicy.normalizePersisted(
            featureEnabled = true,
            setupState = EmbeddedSetupState.STARTING,
            connectionState = EmbeddedConnectionState.CONNECTED,
            reportedUid = 2_000,
            generation = 9L,
            runtimeOwnerIsCurrent = false
        )

        assertTrue(restored.featureEnabled)
        assertEquals(EmbeddedSetupState.IDLE, restored.setupState)
        assertEquals(EmbeddedConnectionState.DISCONNECTED, restored.connectionState)
        assertEquals(-1, restored.reportedUid)
    }

    @Test fun nonShellLiveHandshakeIsRejectedAndCloseRemainsAvailable() {
        assertFalse(
            EmbeddedGuardianStatePolicy.acceptsLiveHandshake(
                pingUid = 10_123,
                statusUid = 10_123,
                running = true
            )
        )
        val state = runtime(connectionState = EmbeddedConnectionState.DEAD)
        assertTrue(EmbeddedGuardianStatePolicy.presentation(state).stopEnabled)
    }

    @Test fun connectedDisablePlansRemoteStopAndAllLocalCleanup() {
        val connected = runtime(
            connectionState = EmbeddedConnectionState.CONNECTED,
            reportedUid = 2_000
        )
        val plan = EmbeddedGuardianStatePolicy.disablePlan(connected, identityAvailable = true)

        assertTrue(plan.attemptRemoteStop)
        assertTrue(plan.stopLocalSetupService)
        assertTrue(plan.cancelSetupNotification)
        assertTrue(plan.cancelRebootNotification)
    }

    private fun runtime(
        setupState: EmbeddedSetupState = EmbeddedSetupState.IDLE,
        connectionState: EmbeddedConnectionState = EmbeddedConnectionState.DISCONNECTED,
        reportedUid: Int = -1,
        generation: Long = 1L
    ) = EmbeddedGuardianRuntimeState(
        featureEnabled = true,
        setupState = setupState,
        connectionState = connectionState,
        reportedUid = reportedUid,
        generation = generation
    )
}
