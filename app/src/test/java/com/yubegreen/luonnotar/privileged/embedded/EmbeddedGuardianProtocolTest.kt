package com.yubegreen.luonnotar.privileged.embedded

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class EmbeddedGuardianProtocolTest {
    @Test fun requestDoesNotExposePayloadAsStructure() {
        val raw = EmbeddedGuardianProtocol.request("a".repeat(64), "configure", "{\"x\":1}")
        val json = JSONObject(raw)
        assertEquals("configure", json.getString("operation"))
        assertEquals("{\"x\":1}", json.getString("payload"))
    }

    @Test fun shellQuoteHandlesApostrophe() {
        assertEquals("'a'\\''b'", EmbeddedGuardianProtocol.shellQuote("a'b"))
    }

    @Test fun limitedReaderStopsAtNewline() {
        assertEquals("hello", EmbeddedGuardianProtocol.readLimitedLine(BufferedReader(StringReader("hello\nrest"))))
    }

    @Test fun responsesHaveExplicitStatus() {
        assertTrue(JSONObject(EmbeddedGuardianProtocol.success("ok")).getBoolean("ok"))
        assertFalse(JSONObject(EmbeddedGuardianProtocol.failure("bad")).getBoolean("ok"))
    }

    @Test fun engineRevisionMatches251ReleaseR259() {
        assertEquals(259, EmbeddedGuardianProtocol.ENGINE_REVISION)
    }
}
