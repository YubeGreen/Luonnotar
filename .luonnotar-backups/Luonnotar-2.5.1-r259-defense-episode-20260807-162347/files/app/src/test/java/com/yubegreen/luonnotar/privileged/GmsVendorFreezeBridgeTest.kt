package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsVendorFreezeBridgeTest {
    @Test
    fun parsesReadyHeartbeatRecoveryAndLockRecords() {
        val ready = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_READY__\ttimeout=1\tsticky=1\tstrategy=adopt_release"
        ) as GmsVendorFreezeBridgeRecord.Ready
        assertTrue(ready.timeout)
        assertTrue(ready.sticky)

        val heartbeat = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_HEARTBEAT__\tatCs=1234" +
                "\tmainPid=41\tmainState=frozen" +
                "\tpersistentPid=42\tpersistentState=thawed"
        ) as GmsVendorFreezeBridgeRecord.Heartbeat
        assertEquals(41, heartbeat.mainPid)
        assertEquals("frozen", heartbeat.mainState)
        assertEquals(42, heartbeat.persistentPid)

        val recovery = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_RECOVERY__\tseq=9" +
                "\ttarget=com.google.android.gms\tpid=41\tmode=adopt_release" +
                "\tplainRc=0\tfreezeRc=0\treleaseRc=0\tstickyRc=0\tverified=1" +
                "\tdurationCs=73\tconsecutive=2"
        ) as GmsVendorFreezeBridgeRecord.Recovery
        assertEquals("adopt_release", recovery.mode)
        assertTrue(recovery.verified)
        assertEquals(0, recovery.stickyExitCode)
        assertEquals(730L, recovery.durationCentiseconds * 10L)

        val lock = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_LOCK__\tseq=10" +
                "\ttarget=com.google.android.gms.persistent\tpid=42" +
                "\tfailures=3\tcooldownCs=1500"
        ) as GmsVendorFreezeBridgeRecord.VendorLock
        assertEquals(15_000L, lock.cooldownCentiseconds * 10L)
    }

    @Test
    fun scriptDirectlyWatchesCgroupAndUsesBoundedAdoptRelease() {
        val script = GmsVendorFreezeBridgeScript.build(
            parentPid = 4242,
            stickyUnfreeze = true,
            baseRoot = "/data/local/tmp"
        )

        assertTrue(script.contains("/cgroup.freeze"))
        assertTrue(script.contains("freezer.state"))
        assertTrue(script.contains("pidof \"${'$'}_target\""))
        assertTrue(script.contains("framework_release \"${'$'}_target\""))
        assertTrue(script.contains("cmd activity unfreeze \"${'$'}_subject\""))
        assertTrue(script.contains("cmd activity unfreeze --sticky \"${'$'}_subject\""))
        assertTrue(script.contains("cmd activity freeze \"${'$'}_subject\""))
        assertTrue(script.contains("_mode=\"adopt_release\""))
        assertTrue(script.contains("sleep 0.15"))
        assertTrue(script.contains("sleep 1"))
        assertTrue(script.contains("_failures\" -ge 3"))
        assertTrue(script.contains("cooldownCs=%s"))
        assertTrue(script.contains("while kill -0 \"${'$'}parent_pid\""))
        assertFalse(script.contains("echo 0 > \"${'$'}STATE_FILE\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeBaseRoot() {
        GmsVendorFreezeBridgeScript.build(4242, true, "/tmp/x;rm -rf /")
    }
}
