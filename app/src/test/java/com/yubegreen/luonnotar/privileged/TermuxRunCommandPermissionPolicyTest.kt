package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxRunCommandPermissionPolicyTest {
    @Test fun parsesGrantedInstallPermission() {
        assertTrue(
            TermuxRunCommandPermissionPolicy.permissionGranted(
                """
                install permissions:
                  com.termux.permission.RUN_COMMAND: granted=true
                """.trimIndent()
            )
        )
    }

    @Test fun parsesGrantedRuntimePermission() {
        assertTrue(
            TermuxRunCommandPermissionPolicy.permissionGranted(
                """
                runtime permissions:
                  com.termux.permission.RUN_COMMAND: granted=true, flags=[ USER_SET ]
                """.trimIndent()
            )
        )
    }

    @Test fun rejectsMissingOrDeniedPermission() {
        assertFalse(
            TermuxRunCommandPermissionPolicy.permissionGranted(
                "com.termux.permission.RUN_COMMAND: granted=false"
            )
        )
        assertFalse(TermuxRunCommandPermissionPolicy.permissionGranted(""))
    }
}
