package com.yubegreen.luonnotar.monitor

data class TargetRoutingSnapshot(
    val monitored: Boolean,
    val active: Boolean,
    val routed: Boolean
)

object TargetRoutingPolicy {
    fun isVerified(vararg targets: TargetRoutingSnapshot): Boolean =
        targets.all { !it.monitored || !it.active || it.routed }
}
