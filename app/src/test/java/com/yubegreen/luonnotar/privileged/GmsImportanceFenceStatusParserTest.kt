package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsImportanceFenceStatusParserTest {
    @Test
    fun `parses ordered broadcast result data`() {
        val parsed = GmsImportanceFenceStatusParser.parseCommandOutput(
            "Broadcast completed: result=-1, data=\"ok=true;active=true;generation=4;" +
                "anyConnected=true;bothConnected=false;mainState=CONNECTED;" +
                "mainAction=location;mainComponent=com.google.android.gms/.Main;" +
                "persistentState=EXHAUSTED;persistentAction=common;" +
                "persistentComponent=\""
        )
        requireNotNull(parsed)
        assertTrue(parsed.active)
        assertTrue(parsed.anyConnected)
        assertFalse(parsed.bothConnected)
        assertEquals(4L, parsed.generation)
        assertEquals("CONNECTED", parsed.mainState)
        assertEquals("EXHAUSTED", parsed.persistentState)
    }

    @Test
    fun `rejects failed or malformed status`() {
        assertNull(
            GmsImportanceFenceStatusParser.parseCommandOutput(
                "Broadcast completed: result=0, data=\"ok=false;active=false\""
            )
        )
        assertNull(GmsImportanceFenceStatusParser.parseCommandOutput("nothing useful"))
    }
}
