package com.yubegreen.luonnotar.privileged

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class GuardianDiagnosticStoreTest {
    @Test
    fun writesAtomicStatusAndRotatingJsonlEvents() {
        val root = Files.createTempDirectory("luonnotar-diagnostics").toFile()
        val status = root.resolve("status.json")
        val events = root.resolve("events.log")
        val store = GuardianDiagnosticStore(status, events, maxEventsBytes = 80L)

        assertTrue(store.writeStatus("{\"running\":true}"))
        assertEquals(true, JSONObject(status.readText()).getBoolean("running"))
        assertFalse(root.resolve("status.json.tmp").exists())

        repeat(8) { index ->
            assertTrue(store.appendEvent(JSONObject().put("index", index).put("detail", "x".repeat(30))))
        }
        assertTrue(events.exists())
        assertTrue(root.resolve("events.log.1").exists())
        events.readLines().forEach { JSONObject(it) }
    }
}
