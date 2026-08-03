package com.yubegreen.luonnotar.privileged.embedded

import com.yubegreen.luonnotar.privileged.BackgroundPolicyVendorFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OemBackgroundSettingsPlanTest {
    @Test fun xiaomiPlanPrioritizesVendorAutostartBeforeSystemFallbacks() {
        val candidates = OemBackgroundSettingsPlan.candidates(
            BackgroundPolicyVendorFamily.XIAOMI
        )

        assertEquals("xiaomi_autostart_action", candidates.first().label)
        assertTrue(candidates.any { it.label == "xiaomi_battery_component" })
        assertEquals("application_details", candidates.last().label)
    }

    @Test fun vivoOppoAndHuaweiPlansContainPrivateManagerCandidates() {
        assertTrue(
            OemBackgroundSettingsPlan.candidates(BackgroundPolicyVendorFamily.VIVO)
                .any { it.label.startsWith("vivo_") || it.label.startsWith("iqoo_") }
        )
        assertTrue(
            OemBackgroundSettingsPlan.candidates(BackgroundPolicyVendorFamily.OPPO)
                .any { it.label.startsWith("oplus_") || it.label.startsWith("coloros_") }
        )
        assertTrue(
            OemBackgroundSettingsPlan.candidates(BackgroundPolicyVendorFamily.HUAWEI)
                .any { it.label.startsWith("huawei_") || it.label.startsWith("honor_") }
        )
    }

    @Test fun everyPlanEndsWithPublicSystemFallbacks() {
        BackgroundPolicyVendorFamily.entries.forEach { family ->
            val labels = OemBackgroundSettingsPlan.candidates(family).map { it.label }
            assertTrue(labels.takeLast(2).contains("system_battery_optimization"))
            assertEquals("application_details", labels.last())
        }
    }
}
