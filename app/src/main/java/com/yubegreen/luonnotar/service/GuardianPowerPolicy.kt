package com.yubegreen.luonnotar.service

data class GuardianPowerInput(
    val guardianActive: Boolean,
    val currentService: Boolean,
    val profile: GuardianRuntimeProfile,
    val screenInteractive: Boolean,
    val screenOffCpuGuard: Boolean,
    val labPermanentCpuLock: Boolean
)

data class GuardianPowerDecision(
    val holdCpuLock: Boolean,
    val scope: String
)

object GuardianPowerPolicy {
    const val IQOO_TICK_SECONDS = 30L

    fun decide(
        input: GuardianPowerInput
    ): GuardianPowerDecision {
        if (!input.guardianActive) {
            return GuardianPowerDecision(
                holdCpuLock = false,
                scope = "guardian_inactive"
            )
        }

        if (!input.currentService) {
            return GuardianPowerDecision(
                holdCpuLock = false,
                scope = "stale_service"
            )
        }

        if (input.profile == GuardianRuntimeProfile.ADB_PASSIVE) {
            return GuardianPowerDecision(
                holdCpuLock = false,
                scope = "adb_passive_no_continuous_lock"
            )
        }

        if (
            input.profile == GuardianRuntimeProfile.LAB_EXTREME &&
            input.labPermanentCpuLock
        ) {
            return GuardianPowerDecision(
                holdCpuLock = true,
                scope = "lab_permanent"
            )
        }

        if (input.screenOffCpuGuard) {
            return GuardianPowerDecision(
                holdCpuLock = !input.screenInteractive,
                scope = if (input.screenInteractive) {
                    "screen_off_cpu_guard_screen_on"
                } else {
                    "screen_off_cpu_guard_active"
                }
            )
        }

        return GuardianPowerDecision(
            holdCpuLock = false,
            scope = "screen_off_cpu_guard_disabled"
        )
    }
}