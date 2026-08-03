package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianProcessParserTest {
    @Test
    fun parsesPidNameArgsLayout() {
        val parsed = GuardianProcessParser.parse(
            """
            PID NAME ARGS
            4786 com.google.android.gms com.google.android.gms
            4291 com.google.android.gms.persistent com.google.android.gms.persistent
            """.trimIndent()
        )

        assertEquals(listOf(4786, 4291), parsed.map { it.pid })
        assertEquals("com.google.android.gms.persistent", parsed[1].name)
    }

    @Test
    fun prefersFullArgsWhenVendorNameIsTruncated() {
        val parsed = GuardianProcessParser.parse(
            """
            PID NAME ARGS
            4291 com.google.andr com.google.android.gms.persistent
            """.trimIndent()
        )

        assertEquals(1, parsed.size)
        assertEquals("com.google.android.gms.persistent", parsed.single().name)
    }

    @Test
    fun parsesVendorPsLayout() {
        val parsed = GuardianProcessParser.parse(
            "u0_a123 9912 1050 123456 45678 0 0 S com.whatsapp"
        )

        assertEquals(9912, parsed.single().pid)
        assertEquals("com.whatsapp", parsed.single().name)
    }

    @Test
    fun rejectsShellFragmentsAndMatchesSubprocesses() {
        val parsed = GuardianProcessParser.parse(
            """
            1 harmless rm -rf /
            9912 com.whatsapp:account_switching com.whatsapp:account_switching
            """.trimIndent()
        )
        val matched = GuardianProcessParser.matching(parsed, listOf("com.whatsapp", "bad;name"))

        assertEquals(1, matched.size)
        assertTrue(matched.single().name.startsWith("com.whatsapp:"))
    }
}
