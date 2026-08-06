package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityManagerUnfreezeCommandTest {
    @Test
    fun buildsDirectStickyCommand() {
        assertEquals(
            listOf(
                "cmd", "activity", "unfreeze", "--sticky",
                "com.google.android.gms"
            ),
            ActivityManagerUnfreezeCommand.build(
                processName = "com.google.android.gms",
                sticky = true,
                backend = ActivityManagerUnfreezeCommand.Backend.CMD_ACTIVITY
            )
        )
    }

    @Test
    fun buildsAmFallbackCommand() {
        assertEquals(
            listOf("am", "unfreeze", "com.whatsapp", "--user", "0"),
            ActivityManagerUnfreezeCommand.build(
                processName = "com.whatsapp",
                sticky = false,
                backend = ActivityManagerUnfreezeCommand.Backend.AM
            )
        )
    }


    @Test
    fun buildsDirectCommandWithAmFallback() {
        assertEquals(
            "cmd activity unfreeze --sticky com.google.android.gms || " +
                "am unfreeze --sticky com.google.android.gms --user 0",
            ActivityManagerUnfreezeCommand.shellWithAmFallback(
                processName = "com.google.android.gms",
                sticky = true,
                backend = ActivityManagerUnfreezeCommand.Backend.CMD_ACTIVITY
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShellMetacharacters() {
        ActivityManagerUnfreezeCommand.build(
            processName = "com.google.android.gms;id",
            sticky = false,
            backend = ActivityManagerUnfreezeCommand.Backend.AM
        )
    }
}
