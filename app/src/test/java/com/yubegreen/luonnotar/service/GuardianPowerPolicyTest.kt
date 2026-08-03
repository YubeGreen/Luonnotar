package com.yubegreen.luonnotar.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianPowerPolicyTest {
    private val base = GuardianPowerInput(
        guardianActive = true,
        currentService = true,
        profile = GuardianRuntimeProfile.STANDARD,
        screenInteractive = false,
        screenOffCpuGuard = true,
        labPermanentCpuLock = false
    )

    @Test
    fun `screen off guard works in standard profile`() {
        assertTrue(
            GuardianPowerPolicy.decide(base).holdCpuLock
        )
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(screenInteractive = true)
            ).holdCpuLock
        )
    }

    @Test
    fun `disabled guard does not hold continuous lock`() {
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(screenOffCpuGuard = false)
            ).holdCpuLock
        )
    }

    @Test
    fun `iqoo default guard follows screen state`() {
        val iqoo = base.copy(
            profile = GuardianRuntimeProfile.IQOO_COOPERATIVE
        )

        assertTrue(
            GuardianPowerPolicy.decide(iqoo).holdCpuLock
        )
        assertFalse(
            GuardianPowerPolicy.decide(
                iqoo.copy(screenInteractive = true)
            ).holdCpuLock
        )
    }

    @Test
    fun `adb passive always remains clean baseline`() {
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(
                    profile = GuardianRuntimeProfile.ADB_PASSIVE
                )
            ).holdCpuLock
        )
    }

    @Test
    fun `inactive or stale service never holds cpu lock`() {
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(guardianActive = false)
            ).holdCpuLock
        )
        assertFalse(
            GuardianPowerPolicy.decide(
                base.copy(currentService = false)
            ).holdCpuLock
        )
    }

    @Test
    fun `lab permanent lock has priority`() {
        val lab = base.copy(
            profile = GuardianRuntimeProfile.LAB_EXTREME,
            screenInteractive = true,
            screenOffCpuGuard = false,
            labPermanentCpuLock = true
        )

        assertTrue(
            GuardianPowerPolicy.decide(lab).holdCpuLock
        )
        assertFalse(
            GuardianPowerPolicy.decide(
                lab.copy(labPermanentCpuLock = false)
            ).holdCpuLock
        )
    }
}