package com.yubegreen.luonnotar.service

import java.util.concurrent.atomic.AtomicReference

data class ActualProbeLease(
    val owner: ProbeOwnerToken,
    val acquiredElapsed: Long,
    val networkHandle: Long,
    val stage: String
)

data class ActualProbePermitSnapshot(
    val owner: ProbeOwnerToken?,
    val acquiredElapsed: Long,
    val networkHandle: Long,
    val stage: String = ""
) {
    val isHeld: Boolean
        get() = owner != null
}

class ActualProbePermit {
    private val lease = AtomicReference<ActualProbeLease?>(null)

    fun tryAcquire(
        token: ProbeOwnerToken,
        acquiredElapsed: Long,
        networkHandle: Long,
        stage: String = ""
    ): Boolean =
        lease.compareAndSet(
            null,
            ActualProbeLease(token, acquiredElapsed, networkHandle, stage)
        )

    fun updateStage(token: ProbeOwnerToken, stage: String): Boolean {
        while (true) {
            val current = lease.get() ?: return false
            if (current.owner !== token) return false
            if (
                lease.compareAndSet(
                    current,
                    current.copy(stage = stage)
                )
            ) return true
        }
    }

    fun release(token: ProbeOwnerToken): Boolean {
        while (true) {
            val current = lease.get() ?: return false
            if (current.owner !== token) return false
            if (lease.compareAndSet(current, null)) return true
        }
    }

    fun snapshot(): ActualProbePermitSnapshot {
        val current = lease.get()
        return ActualProbePermitSnapshot(
            owner = current?.owner,
            acquiredElapsed = current?.acquiredElapsed ?: 0L,
            networkHandle = current?.networkHandle ?: -1L,
            stage = current?.stage.orEmpty()
        )
    }

    fun isHeld(): Boolean = lease.get() != null
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
