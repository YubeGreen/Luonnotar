package com.yubegreen.luonnotar.privileged

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianEngineConfigTest {
    @Test
    fun schemaFourConfigMigratesTermuxAndSignalTargets() {
        val raw = JSONObject()
            .put("schema", 4)
            .put("processTargets", JSONArray().put("com.whatsapp"))
            .put("packageTargets", JSONArray().put("com.whatsapp"))
            .toString()

        val config = GuardianEngineConfig.fromJson(raw)

        assertTrue(config.processTargets.contains("com.termux"))
        assertTrue(config.packageTargets.contains("com.termux"))
        assertTrue(config.packageTargets.contains("com.termux.boot"))
        assertTrue(config.processTargets.contains("org.thoughtcrime.securesms"))
        assertTrue(config.packageTargets.contains("org.thoughtcrime.securesms"))
    }

    @Test
    fun schemaFiveAddsSignalWithoutReaddingEarlierCustomTargets() {
        val raw = JSONObject()
            .put("schema", 5)
            .put("processTargets", JSONArray().put("com.whatsapp"))
            .put("packageTargets", JSONArray().put("com.whatsapp"))
            .toString()

        val config = GuardianEngineConfig.fromJson(raw)

        assertFalse(config.processTargets.contains("com.termux"))
        assertFalse(config.packageTargets.contains("com.termux"))
        assertFalse(config.packageTargets.contains("com.termux.boot"))
        assertTrue(config.processTargets.contains("org.thoughtcrime.securesms"))
        assertTrue(config.packageTargets.contains("org.thoughtcrime.securesms"))
    }

    @Test
    fun schemaSixKeepsExplicitCustomTargets() {
        val raw = JSONObject()
            .put("schema", 6)
            .put("processTargets", JSONArray().put("com.whatsapp"))
            .put("packageTargets", JSONArray().put("com.whatsapp"))
            .toString()

        val config = GuardianEngineConfig.fromJson(raw)

        assertFalse(config.processTargets.contains("org.thoughtcrime.securesms"))
        assertFalse(config.packageTargets.contains("org.thoughtcrime.securesms"))
    }

    @Test
    fun defaultsIncludeSignal() {
        assertTrue(
            GuardianEngineConfig.DEFAULT_PROCESS_TARGETS.contains(
                "org.thoughtcrime.securesms"
            )
        )
        assertTrue(
            GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS.contains(
                "org.thoughtcrime.securesms"
            )
        )
    }
    @Test
    fun schemaSixDisablesLegacyRootCgroupWrite() {
        val raw = JSONObject()
            .put("schema", 6)
            .put("rootCgroupThaw", true)
            .toString()

        assertFalse(GuardianEngineConfig.fromJson(raw).rootCgroupThaw)
    }

    @Test
    fun schemaSevenCanExplicitlyEnableRootCgroupWrite() {
        val raw = JSONObject()
            .put("schema", 7)
            .put("rootCgroupThaw", true)
            .toString()

        assertTrue(GuardianEngineConfig.fromJson(raw).rootCgroupThaw)
    }

}
