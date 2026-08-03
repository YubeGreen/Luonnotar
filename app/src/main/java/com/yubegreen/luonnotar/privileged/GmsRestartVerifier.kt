package com.yubegreen.luonnotar.privileged

/** Verifies that the bounded GMS process rebuild replaced the observed process set. */
object GmsRestartVerifier {
    data class Observation(
        val oldPidStillAlive: Boolean,
        val newPids: List<Int>,
        val restarted: Boolean
    )

    fun observe(oldPids: Collection<Int>, currentPids: Collection<Int>): Observation {
        val old = oldPids.filter { it > 0 }.toSet()
        val current = currentPids.filter { it > 0 }.toSet()
        val oldAlive = old.any(current::contains)
        val newPids = current.filterNot(old::contains).sorted()
        return Observation(
            oldPidStillAlive = oldAlive,
            newPids = newPids,
            restarted = !oldAlive && newPids.isNotEmpty()
        )
    }
}
