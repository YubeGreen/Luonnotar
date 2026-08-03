package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals

class TargetUidHealthSnapshotTest {
    @Test
    fun onlyKnownTargetPackagesAreAccepted() {
        assertTrue(
            TargetUidHealthSnapshot.isAllowedTargetPackage(
                "com.google.android.gms"
            )
        )
        assertTrue(
            TargetUidHealthSnapshot.isAllowedTargetPackage("com.whatsapp")
        )
        assertTrue(
            TargetUidHealthSnapshot.isAllowedTargetPackage("com.termux")
        )
        assertTrue(
            TargetUidHealthSnapshot.isAllowedTargetPackage("com.termux.boot")
        )
        assertFalse(
            TargetUidHealthSnapshot.isAllowedTargetPackage(
                "com.example.untrusted"
            )
        )
    }

    @Test
    fun oversizedImportIsRejected() {
        assertTrue(
            TargetUidHealthSnapshot.parseArray(" ".repeat(48_001)).isEmpty()
        )
    }

    @Test
    fun missingDiagnosticFieldsAreUnknownNotHealthy() {
        assertEquals(
            DiagnosticTruth.UNKNOWN,
            TargetUidHealthSnapshot.truthValue(false, null)
        )
        assertEquals(
            DiagnosticTruth.TRUE,
            TargetUidHealthSnapshot.truthValue(true, true)
        )
        assertEquals(
            DiagnosticTruth.FALSE,
            TargetUidHealthSnapshot.truthValue(true, "FALSE")
        )
    }
}
