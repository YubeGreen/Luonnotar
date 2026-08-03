package com.yubegreen.luonnotar.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentSessionPolicyTest {
    @Test
    fun sessionNameNormalizesWhitespaceAndUnsafeSeparators() {
        assertEquals(
            "normal_use_whatsapp_background",
            ExperimentSessionPolicy.normalizeSessionName(
                "  normal use;whatsapp=background  "
            )
        )
    }

    @Test
    fun unicodeLettersArePreserved() {
        assertEquals(
            "正常使用_WhatsApp后台",
            ExperimentSessionPolicy.normalizeSessionName(
                "正常使用 WhatsApp后台"
            )
        )
    }

    @Test
    fun emptyOrOnlyUnsafeInputIsRejected() {
        assertNull(ExperimentSessionPolicy.normalizeSessionName(" ;=\n "))
    }

    @Test
    fun labelsAreBounded() {
        val value = ExperimentSessionPolicy.normalizeMarkLabel("a".repeat(500))
        assertEquals(ExperimentSessionPolicy.MAX_MARK_LABEL_LENGTH, value?.length)
    }

    @Test
    fun generatedSessionIdIsWireSafe() {
        val value = ExperimentSessionPolicy.newSessionId(123456L, 7890L, 42)
        assertTrue(value.matches(Regex("[a-z0-9-]+")))
    }
}
