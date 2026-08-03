package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyVendorDetectorTest {
    @Test fun detectsHyperOsFromProperties() {
        val device = BackgroundPolicyVendorDetector.detect(
            mapOf(
                "ro.product.manufacturer" to "Xiaomi",
                "ro.product.brand" to "Redmi",
                "ro.product.model" to "22011211C",
                "ro.mi.os.version.name" to "OS3.0",
                "ro.build.version.sdk" to "36"
            )
        )

        assertEquals(BackgroundPolicyVendorFamily.XIAOMI, device.family)
        assertEquals("HyperOS", device.romName)
        assertTrue(device.displayName().contains("Redmi"))
        assertTrue(BackgroundPolicyVendorDetector.requiresPrivateLayerConfirmation(device.family))
    }

    @Test fun detectsIqooAsVivoFamily() {
        val device = BackgroundPolicyVendorDetector.detect(
            mapOf(
                "ro.product.manufacturer" to "vivo",
                "ro.product.brand" to "iQOO",
                "ro.product.model" to "V2352A",
                "ro.vivo.os.version" to "16"
            )
        )

        assertEquals(BackgroundPolicyVendorFamily.VIVO, device.family)
        assertTrue(device.romName.contains("iQOO"))
    }

    @Test fun detectsOppoFamilyAcrossOnePlusAndRealme() {
        val onePlus = BackgroundPolicyVendorDetector.detect(
            mapOf(
                "ro.product.manufacturer" to "OnePlus",
                "ro.build.version.oplusrom" to "16"
            )
        )
        val realme = BackgroundPolicyVendorDetector.detect(
            mapOf("ro.product.brand" to "realme")
        )

        assertEquals(BackgroundPolicyVendorFamily.OPPO, onePlus.family)
        assertEquals("OxygenOS", onePlus.romName)
        assertEquals(BackgroundPolicyVendorFamily.OPPO, realme.family)
        assertEquals("realme UI", realme.romName)
    }

    @Test fun detectsHuaweiHonorAndSamsung() {
        val honor = BackgroundPolicyVendorDetector.detect(
            mapOf(
                "ro.product.brand" to "HONOR",
                "ro.build.version.magic" to "10"
            )
        )
        val samsung = BackgroundPolicyVendorDetector.detect(
            mapOf(
                "ro.product.manufacturer" to "samsung",
                "ro.build.version.oneui" to "80000"
            )
        )

        assertEquals(BackgroundPolicyVendorFamily.HUAWEI, honor.family)
        assertEquals("MagicOS", honor.romName)
        assertEquals(BackgroundPolicyVendorFamily.SAMSUNG, samsung.family)
        assertEquals("One UI", samsung.romName)
    }

    @Test fun defaultTargetsCoverLuonnotarPushAndSupportedVpns() {
        assertTrue(GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS.contains("com.yubegreen.luonnotar"))
        assertTrue(GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS.contains("com.google.android.gms"))
        assertTrue(GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS.contains("com.whatsapp"))
        assertTrue(GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS.contains("com.tailscale.ipn"))
        assertTrue(GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS.contains("com.termux"))
        assertTrue(GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS.contains("com.termux.boot"))
        assertTrue(GuardianEngineConfig.DEFAULT_PROCESS_TARGETS.contains("com.termux"))
        assertTrue(GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS.contains("ch.protonvpn.android"))
    }

    @Test fun vendorAppOpProbeKeepsSymbolicNamesSafe() {
        BackgroundPolicyVendorFamily.entries.forEach { family ->
            BackgroundPolicyVendorDetector.symbolicOemAppOps(family).forEach { operation ->
                assertTrue(operation.matches(Regex("^[A-Z_]+$")))
                assertFalse(operation == "BACKGROUND_START_ACTIVITY")
                assertFalse(operation.all(Char::isDigit))
            }
        }
    }

    @Test fun xiaomiNumericAppOpsCoverBootDeliveryAndAutostartOnly() {
        val operations = BackgroundPolicyVendorDetector.numericOemAppOps(
            BackgroundPolicyVendorFamily.XIAOMI
        )

        assertEquals(listOf(10007, 10008), operations.map { it.code })
        assertEquals(listOf("boot_completed", "auto_start"), operations.map { it.label })
        BackgroundPolicyVendorFamily.entries
            .filterNot { it == BackgroundPolicyVendorFamily.XIAOMI }
            .forEach { family ->
                assertTrue(BackgroundPolicyVendorDetector.numericOemAppOps(family).isEmpty())
            }
    }

    @Test fun googleAospDoesNotRequirePrivateLayerConfirmation() {
        val device = BackgroundPolicyVendorDetector.detect(
            mapOf(
                "ro.product.manufacturer" to "Google",
                "ro.product.brand" to "google"
            )
        )

        assertEquals(BackgroundPolicyVendorFamily.AOSP, device.family)
        assertFalse(BackgroundPolicyVendorDetector.requiresPrivateLayerConfirmation(device.family))
    }
}
