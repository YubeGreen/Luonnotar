package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSelfUpdatePolicyTest {
    @Test fun acceptsOnlyNewerSamePackageSameSigner() {
        val decision = EmbeddedSelfUpdatePolicy.validate(
            packageName = "com.yubegreen.luonnotar",
            candidateVersionCode = 85,
            installedVersionCode = 84,
            candidateSignerDigests = setOf("abc"),
            installedSignerDigests = setOf("abc"),
            apkSize = 10_000
        )
        assertTrue(decision.allowed)
    }

    @Test fun rejectsOtherPackage() {
        val decision = EmbeddedSelfUpdatePolicy.validate(
            "com.example.other", 85, 84, setOf("abc"), setOf("abc"), 10_000
        )
        assertEquals("REJECT_PACKAGE_MISMATCH", decision.code)
    }

    @Test fun rejectsSignerMismatchAndDowngrade() {
        assertEquals(
            "REJECT_SIGNATURE_MISMATCH",
            EmbeddedSelfUpdatePolicy.validate(
                "com.yubegreen.luonnotar", 85, 84, setOf("x"), setOf("y"), 10_000
            ).code
        )
        assertEquals(
            "REJECT_VERSION_DOWNGRADE",
            EmbeddedSelfUpdatePolicy.validate(
                "com.yubegreen.luonnotar", 84, 84, setOf("x"), setOf("x"), 10_000
            ).code
        )
    }

    @Test fun stagingPathIsNarrow() {
        assertTrue(
            EmbeddedSelfUpdatePolicy.pathLooksAllowed(
                "/data/local/tmp/luonnotar-self-update/Luonnotar-2.5.2.apk"
            )
        )
        assertFalse(EmbeddedSelfUpdatePolicy.pathLooksAllowed("/sdcard/Download/x.apk"))
        assertFalse(EmbeddedSelfUpdatePolicy.pathLooksAllowed("/data/local/tmp/luonnotar-self-update/x.txt"))
    }
}
