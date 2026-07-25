package com.yubegreen.luonnotar.util

data class GuardianTimelineSnapshot(
    val wallTime: String,
    val elapsedRealtime: Long,
    val screenInteractive: Boolean,
    val deviceIdleMode: Boolean,
    val powerSaveMode: Boolean,
    val wakeLockHeld: Boolean,
    val wifiLockHeld: Boolean,
    val networkHandle: Long,
    val vpnPresent: Boolean,
    val validated: Boolean,
    val underlay: String,
    val probeInFlight: Boolean,
    val lastProbeAgeMs: Long,
    val lastProbeRttMs: Long,
    val timerDriftMs: Long,
    val serviceGeneration: Long
) {
    fun toMap(): Map<String, Any> = linkedMapOf(
        "wallTime" to wallTime,
        "elapsedRealtime" to elapsedRealtime,
        "screenInteractive" to screenInteractive,
        "deviceIdleMode" to deviceIdleMode,
        "powerSaveMode" to powerSaveMode,
        "wakeLockHeld" to wakeLockHeld,
        "wifiLockHeld" to wifiLockHeld,
        "networkHandle" to networkHandle,
        "vpnPresent" to vpnPresent,
        "validated" to validated,
        "underlay" to underlay,
        "probeInFlight" to probeInFlight,
        "lastProbeAgeMs" to lastProbeAgeMs,
        "lastProbeRttMs" to lastProbeRttMs,
        "timerDriftMs" to timerDriftMs,
        "serviceGeneration" to serviceGeneration
    )

    companion object {
        val REQUIRED_FIELDS = setOf(
            "wallTime",
            "elapsedRealtime",
            "screenInteractive",
            "deviceIdleMode",
            "powerSaveMode",
            "wakeLockHeld",
            "wifiLockHeld",
            "networkHandle",
            "vpnPresent",
            "validated",
            "underlay",
            "probeInFlight",
            "lastProbeAgeMs",
            "lastProbeRttMs",
            "timerDriftMs",
            "serviceGeneration"
        )
    }
}
