package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSshGuardianPolicyTest {
    @Test fun recoveryBackoffIsBoundedAndEscalating() {
        assertEquals(5_000L, ShellSshGuardianPolicy.recoveryBackoffMs(1))
        assertEquals(15_000L, ShellSshGuardianPolicy.recoveryBackoffMs(2))
        assertEquals(30_000L, ShellSshGuardianPolicy.recoveryBackoffMs(3))
        assertEquals(60_000L, ShellSshGuardianPolicy.recoveryBackoffMs(4))
        assertEquals(300_000L, ShellSshGuardianPolicy.recoveryBackoffMs(5))
        assertEquals(300_000L, ShellSshGuardianPolicy.recoveryBackoffMs(99))
    }

    @Test fun acceptsOpenSshPublicKeysButRejectsCommands() {
        assertTrue(ShellSshGuardianPolicy.isAuthorizedKey("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFakeKeyForTestOnly user@mac"))
        assertTrue(ShellSshGuardianPolicy.isAuthorizedKey("ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQFake"))
        assertFalse(ShellSshGuardianPolicy.isAuthorizedKey("echo pwned"))
        assertFalse(ShellSshGuardianPolicy.isAuthorizedKey(""))
    }

    @Test fun fatalBootstrapDiagnosticsHaveIndependentPersistentPath() {
        assertEquals("last-failure.json", ShellSshPaths.LAST_FAILURE_NAME)
        assertFalse(ShellSshPaths.LAST_FAILURE_NAME == ShellSshPaths.DAEMON_STATE_NAME)
    }
}
