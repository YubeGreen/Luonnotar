package com.yubegreen.luonnotar.service

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeRequestGateTest {
    @Test
    fun networkHandleChangeInvalidatesOldProbeResult() {
        assertFalse(
            VpnProbeResultPolicy.accepts(
                capturedNetworkHandle = 100L,
                currentVpnNetworkHandle = 200L,
                activeNetworkHandle = 200L,
                vpnPresent = true
            )
        )
        assertTrue(
            VpnProbeResultPolicy.accepts(
                capturedNetworkHandle = 200L,
                currentVpnNetworkHandle = 200L,
                activeNetworkHandle = 200L,
                vpnPresent = true
            )
        )
    }

    @Test
    fun repeatedForcedEventsCollapseToOnePendingProbe() {
        val gate = ProbeRequestGate()
        val token = gate.begin(0L, force = true, startedElapsed = 10L)!!

        assertNull(gate.begin(0L, force = true, startedElapsed = 11L))
        assertNull(gate.begin(0L, force = true, startedElapsed = 12L))
        assertTrue(gate.snapshot().pendingForced)

        val finished = gate.finish(token)
        assertTrue(finished.accepted)
        assertTrue(finished.runPendingForced)
        assertFalse(gate.snapshot().inFlight)
    }

    @Test
    fun staleOwnerCannotResetOrFinishNewGenerationProbe() {
        val gate = ProbeRequestGate()
        val oldToken = gate.begin(0L, force = true, startedElapsed = 10L)!!

        gate.advanceGeneration(1L)
        val newToken = gate.begin(1L, force = true, startedElapsed = 20L)!!

        assertFalse(gate.reset(oldToken))
        assertFalse(gate.finish(oldToken).accepted)
        assertTrue(gate.snapshot().owner == newToken)
        assertTrue(gate.snapshot().startedElapsed == 20L)
    }

    @Test
    fun oldActualProbeMustReleaseBeforeNewGenerationStartsNetwork() {
        val gate = ProbeRequestGate()
        val oldToken = gate.begin(0L, force = true, startedElapsed = 10L)!!
        assertTrue(gate.beginActual(oldToken))

        gate.advanceGeneration(1L)
        val newToken = gate.begin(1L, force = true, startedElapsed = 20L)!!
        assertFalse(gate.beginActual(newToken))
        assertTrue(gate.snapshot().actualOwner == oldToken)
        assertTrue(gate.snapshot().pendingAfterActual)

        val oldActualFinish = gate.finishActual(oldToken)
        assertTrue(oldActualFinish.accepted)
        assertTrue(oldActualFinish.runPendingForced)
        assertTrue(gate.beginActual(newToken))
        assertTrue(gate.snapshot().actualOwner == newToken)
        assertFalse(gate.finishActual(oldToken).accepted)
    }

    @Test
    fun staleCallbacksCannotClearNewOwnerUnderConcurrentPressure() {
        val gate = ProbeRequestGate()
        val pool = Executors.newFixedThreadPool(8)
        var generation = 0L
        try {
            repeat(500) { iteration ->
                val oldToken = gate.begin(
                    generation,
                    force = true,
                    startedElapsed = iteration.toLong() + 1L
                )!!
                generation += 1L
                gate.advanceGeneration(generation)
                val newToken = gate.begin(
                    generation,
                    force = true,
                    startedElapsed = iteration.toLong() + 10_000L
                )!!
                val callbacks = List(16) { callback ->
                    pool.submit {
                        if (callback % 2 == 0) {
                            gate.reset(oldToken)
                        } else {
                            gate.finish(oldToken)
                        }
                    }
                }
                callbacks.forEach { it.get(5, TimeUnit.SECONDS) }
                val snapshot = gate.snapshot()
                assertTrue(snapshot.owner == newToken)
                assertTrue(snapshot.inFlight)
                assertTrue(
                    snapshot.startedElapsed ==
                        iteration.toLong() + 10_000L
                )
                assertTrue(gate.finish(newToken).accepted)
            }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun processPermitNeverAllowsTwoActualHttpsRequests() {
        val permit = ActualProbePermit()
        val concurrent = AtomicInteger(0)
        val maximum = AtomicInteger(0)
        val tokenSequence = AtomicLong(0L)
        val pool = Executors.newFixedThreadPool(12)
        try {
            val tasks = List(12) {
                pool.submit {
                    repeat(2_000) {
                        val token = ProbeOwnerToken(
                            tokenSequence.incrementAndGet(),
                            1L
                        )
                        if (
                            permit.tryAcquire(
                                token,
                                acquiredElapsed = token.value,
                                networkHandle = 700L
                            )
                        ) {
                            val active = concurrent.incrementAndGet()
                            maximum.accumulateAndGet(active) { current, value ->
                                maxOf(current, value)
                            }
                            Thread.yield()
                            concurrent.decrementAndGet()
                            assertTrue(permit.release(token))
                        }
                    }
                }
            }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue(maximum.get() == 1)
            assertFalse(permit.isHeld())
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun staleProcessPermitReleaseCannotClearNewOwner() {
        val permit = ActualProbePermit()
        val oldToken = ProbeOwnerToken(1L, 1L)
        val newToken = ProbeOwnerToken(2L, 2L)

        assertTrue(permit.tryAcquire(oldToken, 10L, 700L))
        assertTrue(permit.release(oldToken))
        assertTrue(permit.tryAcquire(newToken, 20L, 701L))
        assertFalse(permit.release(oldToken))
        assertTrue(permit.isHeld())
        assertTrue(permit.snapshot().owner === newToken)
        assertTrue(permit.snapshot().acquiredElapsed == 20L)
        assertTrue(permit.snapshot().networkHandle == 701L)
        assertTrue(permit.release(newToken))
        assertFalse(permit.isHeld())
    }

    @Test
    fun newServiceCanObserveAnOldProcessPermitLease() {
        val permit = ActualProbePermit()
        val oldServiceToken = ProbeOwnerToken(1L, 4L)

        assertTrue(
            permit.tryAcquire(
                oldServiceToken,
                acquiredElapsed = 1_000L,
                networkHandle = 900L,
                stage = "HTTPS"
            )
        )
        val snapshot = permit.snapshot()

        assertTrue(snapshot.owner === oldServiceToken)
        assertTrue(snapshot.acquiredElapsed == 1_000L)
        assertTrue(snapshot.networkHandle == 900L)
        assertTrue(snapshot.stage == "HTTPS")
        assertFalse(permit.release(ProbeOwnerToken(1L, 4L)))
        assertTrue(permit.isHeld())
        assertTrue(permit.release(oldServiceToken))
    }
}
