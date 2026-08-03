package com.yubegreen.luonnotar.privileged

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianEngineConfigTest {
    @Test
    fun schemaFourConfigMigratesTermuxTargets() {
        val raw = JSONObject()
            .put("schema", 4)
            .put("processTargets", JSONArray().put("com.whatsapp"))
            .put("packageTargets", JSONArray().put("com.whatsapp"))
            .toString()

        val config = GuardianEngineConfig.fromJson(raw)

        assertTrue(config.processTargets.contains("com.termux"))
        assertTrue(config.packageTargets.contains("com.termux"))
        assertTrue(config.packageTargets.contains("com.termux.boot"))
    }

    @Test
    fun schemaFiveKeepsExplicitCustomTargets() {
        val raw = JSONObject()
            .put("schema", 5)
            .put("processTargets", JSONArray().put("com.whatsapp"))
            .put("packageTargets", JSONArray().put("com.whatsapp"))
            .toString()

        val config = GuardianEngineConfig.fromJson(raw)

        assertFalse(config.processTargets.contains("com.termux"))
        assertFalse(config.packageTargets.contains("com.termux"))
        assertFalse(config.packageTargets.contains("com.termux.boot"))
    }
}
