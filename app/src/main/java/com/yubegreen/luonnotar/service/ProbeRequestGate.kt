package com.yubegreen.luonnotar.service

import java.util.concurrent.atomic.AtomicReference

class ActualProbePermit {
    private val owner = AtomicReference<ProbeOwnerToken?>(null)

    fun tryAcquire(token: ProbeOwnerToken): Boolean =
        owner.compareAndSet(null, token)

    fun release(token: ProbeOwnerToken): Boolean =
        owner.compareAndSet(token, null)

    fun isHeld(): Boolean = owner.get() != null
}

class ProbeOwnerToken internal constructor(
    val value: Long,
    val generation: Long
)

data class ProbeGateSnapshot(
    val generation: Long,
    val owner: ProbeOwnerToken?,
    val startedElapsed: Long,
    val pendingForced: Boolean,
    val actualOwner: ProbeOwnerToken?,
    val actualStartedElapsed: Long,
    val pendingAfterActual: Boolean
) {
    val inFlight: Boolean
        get() = owner != null

    val actualInFlight: Boolean
        get() = actualOwner != null

    val anyInFlight: Boolean
        get() = inFlight || actualInFlight

    val effectiveStartedElapsed: Long
        get() = actualStartedElapsed.takeIf { actualInFlight } ?: startedElapsed
}

data class ProbeFinishResult(
    val accepted: Boolean,
    val runPendingForced: Boolean
)

class ProbeRequestGate(initialGeneration: Long = 0L) {
    private var generation = initialGeneration
    private var nextToken = 0L
    private var owner: ProbeOwnerToken? = null
    private var startedElapsed = 0L
    private var pendingForced = false
    private var actualOwner: ProbeOwnerToken? = null
    private var actualStartedElapsed = 0L
    private var pendingAfterActual = false

    @Synchronized
    fun begin(
        generation: Long,
        force: Boolean,
        startedElapsed: Long
    ): ProbeOwnerToken? {
        if (generation != this.generation) return null
        if (owner != null) {
            if (force) pendingForced = true
            return null
        }
        val token = ProbeOwnerToken(++nextToken, generation)
        owner = token
        this.startedElapsed = startedElapsed
        if (force) pendingForced = false
        return token
    }

    @Synchronized
    fun finish(token: ProbeOwnerToken): ProbeFinishResult {
        if (owner != token || token.generation != generation) {
            return ProbeFinishResult(false, false)
        }
        owner = null
        startedElapsed = 0L
        val runPending = pendingForced
        pendingForced = false
        return ProbeFinishResult(true, runPending)
    }

    @Synchronized
    fun reset(token: ProbeOwnerToken): Boolean {
        if (owner != token || token.generation != generation) return false
        owner = null
        startedElapsed = 0L
        pendingForced = false
        return true
    }

    @Synchronized
    fun advanceGeneration(generation: Long) {
        if (generation < this.generation) return
        this.generation = generation
        owner = null
        startedElapsed = 0L
        pendingForced = false
    }

    @Synchronized
    fun owns(token: ProbeOwnerToken): Boolean =
        owner == token && token.generation == generation

    @Synchronized
    fun beginActual(token: ProbeOwnerToken): Boolean {
        if (owner != token || token.generation != generation) return false
        if (actualOwner != null) {
            pendingAfterActual = true
            return false
        }
        actualOwner = token
        actualStartedElapsed = startedElapsed
        return true
    }

    @Synchronized
    fun finishActual(token: ProbeOwnerToken): ProbeFinishResult {
        if (actualOwner != token) return ProbeFinishResult(false, false)
        actualOwner = null
        actualStartedElapsed = 0L
        val runFollowUp = pendingAfterActual
        pendingAfterActual = false
        return ProbeFinishResult(true, runFollowUp)
    }

    @Synchronized
    fun snapshot(): ProbeGateSnapshot = ProbeGateSnapshot(
        generation = generation,
        owner = owner,
        startedElapsed = startedElapsed,
        pendingForced = pendingForced,
        actualOwner = actualOwner,
        actualStartedElapsed = actualStartedElapsed,
        pendingAfterActual = pendingAfterActual
    )
}

object VpnProbeResultPolicy {
    fun accepts(
        capturedNetworkHandle: Long,
        currentVpnNetworkHandle: Long,
        activeNetworkHandle: Long,
        vpnPresent: Boolean
    ): Boolean =
        capturedNetworkHandle >= 0L &&
            vpnPresent &&
            currentVpnNetworkHandle == capturedNetworkHandle &&
            activeNetworkHandle == capturedNetworkHandle
}
