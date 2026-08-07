package com.yubegreen.luonnotar.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRuntimeCallerPolicyTest {
    private val appUid = 10444

    @Test fun allowsOnlyAppRootSystemOrShell() {
        assertTrue(AdbRuntimeCallerPolicy.isAllowed(appUid, appUid, 0, 1000, 2000))
        assertTrue(AdbRuntimeCallerPolicy.isAllowed(0, appUid, 0, 1000, 2000))
        assertTrue(AdbRuntimeCallerPolicy.isAllowed(1000, appUid, 0, 1000, 2000))
        assertTrue(AdbRuntimeCallerPolicy.isAllowed(2000, appUid, 0, 1000, 2000))
        assertFalse(AdbRuntimeCallerPolicy.isAllowed(10171, appUid, 0, 1000, 2000))
        assertFalse(AdbRuntimeCallerPolicy.isAllowed(10999, appUid, 0, 1000, 2000))
    }
}
