package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedGuardianHandoffCommandTest {
    private val identity = EmbeddedGuardianStore.EndpointIdentity(
        port = 41234,
        token = "ab".repeat(32)
    )

    @Test
    fun waitsForExactOldInstanceThenReusesEndpoint() {
        val command = EmbeddedGuardianHandoffCommand.build(
            apkPath = "/data/app/example/base.apk",
            mainClass = "com.yubegreen.luonnotar.privileged.embedded.EmbeddedGuardianServerMain",
            identity = identity,
            oldPid = 1234,
            oldStartTicks = 987654L,
            expectedRevision = 260,
            reason = "unit_test"
        )

        assertTrue(command.contains("old_pid=1234"))
        assertTrue(command.contains("old_start=987654"))
        assertTrue(command.contains("/proc/\$old_pid/stat"))
        assertTrue(command.contains("cur_start=\${20:-0}"))
        assertTrue(command.contains("--port 41234"))
        assertTrue(command.contains("--token '${identity.token}'"))
        assertTrue(command.contains("hot_handoff:unit_test:expected_r260"))
        assertFalse(command.contains("kill -9"))
        assertFalse(command.contains("pkill"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsRelativeApkPath() {
        EmbeddedGuardianHandoffCommand.build(
            apkPath = "base.apk",
            mainClass = "com.example.Main",
            identity = identity,
            oldPid = 1234,
            oldStartTicks = 1L,
            expectedRevision = 260,
            reason = "bad_path"
        )
    }
}
