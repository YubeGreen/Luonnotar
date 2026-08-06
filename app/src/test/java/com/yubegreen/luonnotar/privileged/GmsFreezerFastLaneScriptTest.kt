package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertTrue
import org.junit.Test

class GmsFreezerFastLaneScriptTest {
    @Test
    fun shellOwnsLogcatAndThawsBeforeForwardingTheFreezeLine() {
        val script = GmsFreezerFastLaneScript.build(
            parentPid = 4242,
            stickyUnfreeze = true,
            baseRoot = "/data/local/tmp"
        )

        assertTrue(script.contains("cmd activity unfreeze"))
        assertTrue(script.contains("am unfreeze"))
        assertTrue(script.contains("_now + 4500"))
        assertTrue(script.contains("_now + 12000"))
        assertTrue(script.contains("_commands\" -lt 48"))
        assertTrue(script.contains("_immediate_count\" -lt 24"))

        val persistentCase = script.indexOf("*am_app_frozen*com.google.android.gms.persistent*")
        val mainCase = script.indexOf("*am_app_frozen*com.google.android.gms*", persistentCase + 1)
        val firstThaw = script.indexOf("unfreeze_process \"${'$'}_signal_target\"", mainCase)
        val rawForward = script.indexOf("__LUONNOTAR_FAST_LANE_LOG__", mainCase)
        assertTrue(persistentCase >= 0)
        assertTrue(mainCase > persistentCase)
        assertTrue(firstThaw in (mainCase + 1) until rawForward)
        assertTrue(script.contains("for _target in com.google.android.gms com.google.android.gms.persistent"))
        assertTrue(script.contains("cmd activity unfreeze ${'$'}sticky_arg \"${'$'}_target\""))
        assertTrue(!script.contains("cmd activity unfreeze ${'$'}sticky_arg \"${'$'}_target\" --user 0"))
        assertTrue(script.contains("[ \"${'$'}_reported_state\" != \"${'$'}_state_name\" ]"))
        assertTrue(script.contains("last_main_immediate_cs=0"))
        assertTrue(script.contains("last_persistent_immediate_cs=0"))
        assertTrue(script.contains("UNFREEZE_COMMAND_COUNT"))
        assertTrue(script.contains("_budget=${'$'}((48 - _commands))"))
        assertTrue(script.contains("_unobservable=1"))
        assertTrue(script.contains("_previous_soft"))
        assertTrue(script.contains("[ \"${'$'}_previous_soft\" -lt \"${'$'}_now\" ]"))
        assertTrue(script.contains("FROZEN|FREEZING"))
        assertTrue(script.contains("return 124"))
        assertTrue(!script.contains("mkfifo"))
        assertTrue(!script.contains("logcat.fifo"))
        assertTrue(script.contains("transport=pipe"))
        assertTrue(script.contains("2>&1 | consume_logcat &"))
        assertTrue(script.contains("wait \"${'$'}pipeline_pid\""))
        assertTrue(script.contains("logcat_pid_file"))
        assertTrue(
            script.windowed("__LUONNOTAR_FAST_LANE_FIRST__".length)
                .count { it == "__LUONNOTAR_FAST_LANE_FIRST__" } == 1
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeBaseRoot() {
        GmsFreezerFastLaneScript.build(4242, false, "/tmp/x;rm -rf /")
    }
}
