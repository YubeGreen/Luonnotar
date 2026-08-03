package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedGuardianStarterCommandTest {
    private val identity = EmbeddedGuardianStore.EndpointIdentity(
        port = 41234,
        token = "ab".repeat(32)
    )

    @Test
    fun commandUsesPidofInsteadOfPkillPatternMatching() {
        val command = EmbeddedGuardianStarterCommand.build(
            "/data/app/example/base.apk",
            "com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianServerMain",
            identity
        )
        assertTrue(command.contains("pidof luonnotar_privileged_engine"))
        assertFalse(command.contains("pkill -f"))
        assertTrue(command.contains("--port 41234"))
        assertTrue(command.contains("--token '${identity.token}'"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidToken() {
        EmbeddedGuardianStarterCommand.build(
            "/data/app/example/base.apk",
            "com.example.Main",
            identity.copy(token = "bad")
        )
    }
}
