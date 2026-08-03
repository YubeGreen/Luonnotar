package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyReportTest {
    @Test fun reportRoundTripPreservesVendorAndVerificationCounts() {
        val report = BackgroundPolicyReport(
            source = "test",
            createdElapsed = 123L,
            device = BackgroundPolicyDeviceIdentity(
                family = BackgroundPolicyVendorFamily.XIAOMI,
                manufacturer = "Xiaomi",
                brand = "Redmi",
                model = "22011211C",
                product = "matisse",
                romName = "HyperOS",
                romVersion = "3.0",
                sdkInt = 36
            ),
            targets = listOf(
                target("com.yubegreen.luonnotar", true),
                target("com.whatsapp", false),
                BackgroundPolicyTargetResult(
                    packageName = "com.whatsapp.w4b",
                    installed = false,
                    fullyVerified = false,
                    commandsAttempted = 0,
                    commandsSucceeded = 0,
                    capabilities = emptyList()
                )
            ),
            requiresOemUserAction = true,
            oemGuidance = "manual",
            commandsAttempted = 20,
            commandsSucceeded = 18
        )

        val restored = BackgroundPolicyReport.fromJson(report.toJson())

        assertEquals(BackgroundPolicyVendorFamily.XIAOMI, restored.device.family)
        assertEquals(2, restored.installedTargets)
        assertEquals(1, restored.verifiedTargets)
        assertEquals(2, restored.failedCommands)
        assertTrue(restored.conciseSummary().contains("1/2"))
    }

    @Test fun invalidOrUnknownSchemaReturnsEmptyReport() {
        assertEquals(0L, BackgroundPolicyReport.fromJson("not-json").createdElapsed)
        assertEquals(
            0L,
            BackgroundPolicyReport.fromJson("{\"schema\":99}").createdElapsed
        )
    }

    private fun target(packageName: String, verified: Boolean) =
        BackgroundPolicyTargetResult(
            packageName = packageName,
            installed = true,
            fullyVerified = verified,
            commandsAttempted = 10,
            commandsSucceeded = if (verified) 10 else 8,
            capabilities = emptyList()
        )
}
