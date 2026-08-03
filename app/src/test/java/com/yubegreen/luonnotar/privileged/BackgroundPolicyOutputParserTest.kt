package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPolicyOutputParserTest {
    @Test fun parsesStandbyBucketByNameOrNumericValue() {
        assertTrue(BackgroundPolicyOutputParser.standbyBucketActive("active"))
        assertTrue(BackgroundPolicyOutputParser.standbyBucketActive("10"))
        assertFalse(BackgroundPolicyOutputParser.standbyBucketActive("40"))
    }

    @Test fun parsesInactiveAndHibernationFalse() {
        assertTrue(BackgroundPolicyOutputParser.inactiveFalse("Idle=false"))
        assertTrue(BackgroundPolicyOutputParser.hibernationDisabled("false"))
        assertFalse(BackgroundPolicyOutputParser.inactiveFalse("Idle=true"))
    }

    @Test fun parsesAppOpsAllowWithoutAcceptingIgnore() {
        assertTrue(
            BackgroundPolicyOutputParser.appOpAllowed(
                "RUN_ANY_IN_BACKGROUND: allow; time=+2m"
            )
        )
        assertFalse(
            BackgroundPolicyOutputParser.appOpAllowed(
                "RUN_ANY_IN_BACKGROUND: ignore"
            )
        )
    }

    @Test fun matchesDeviceIdlePackageExactly() {
        val output = """
            system-excidle,com.android.providers.downloads,10001
            user,com.whatsapp,10234
            com.yubegreen.luonnotar
        """.trimIndent()

        assertTrue(BackgroundPolicyOutputParser.deviceIdleContains(output, "com.whatsapp"))
        assertTrue(
            BackgroundPolicyOutputParser.deviceIdleContains(
                output,
                "com.yubegreen.luonnotar"
            )
        )
        assertFalse(BackgroundPolicyOutputParser.deviceIdleContains(output, "com.what"))
    }

    @Test fun matchesNetPolicyUidAsWholeNumber() {
        val output = "Restrict background whitelisted UIDs: 10123 10237 10456"

        assertTrue(BackgroundPolicyOutputParser.netPolicyContainsUid(output, 10237))
        assertFalse(BackgroundPolicyOutputParser.netPolicyContainsUid(output, 237))
    }
}
