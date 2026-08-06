package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsFreezerFastLaneProtocolTest {
    @Test
    fun parsesReadyAndFirstThawRecords() {
        val ready = GmsFreezerFastLaneProtocol.parse(
            "__LUONNOTAR_FAST_LANE_READY__\tbackend=cmd_activity\tsticky=1\ttimeout=1"
        ) as GmsFreezerFastLaneRecord.Ready
        assertEquals("cmd_activity", ready.backend)
        assertTrue(ready.sticky)
        assertTrue(ready.timeout)


        val signal = GmsFreezerFastLaneProtocol.parse(
            "__LUONNOTAR_FAST_LANE_SIGNAL__\tseq=8\tatCs=990" +
                "\ttarget=com.google.android.gms"
        ) as GmsFreezerFastLaneRecord.Signal
        assertEquals(8L, signal.sequence)
        assertEquals("com.google.android.gms", signal.target)

        val first = GmsFreezerFastLaneProtocol.parse(
            "__LUONNOTAR_FAST_LANE_FIRST__\tseq=9\ttarget=com.google.android.gms.persistent" +
                "\tbackend=am\trc=0\tskipped=0\tdoneCs=1050\tdurationCs=3"
        ) as GmsFreezerFastLaneRecord.FirstThaw
        assertEquals(9L, first.sequence)
        assertEquals("com.google.android.gms.persistent", first.target)
        assertEquals(0, first.exitCode)
        assertFalse(first.skipped)
        assertEquals(30L, first.durationCentiseconds * 10L)
    }

    @Test
    fun parsesProbeAndShieldCounters() {
        val probe = GmsFreezerFastLaneProtocol.parse(
            "__LUONNOTAR_FAST_LANE_PROBE__\tseq=3\tstate=thawed\tcommands=2" +
                "\taccepted=2\tfrozenPolls=1\tverified=1\tblind=0"
        ) as GmsFreezerFastLaneRecord.ProbeResult
        assertEquals("thawed", probe.state)
        assertEquals(2, probe.commandCount)
        assertEquals(1, probe.verifiedThawCount)

        val shield = GmsFreezerFastLaneProtocol.parse(
            "__LUONNOTAR_FAST_LANE_SHIELD__\tseq=4\tepisode=1\tstate=frozen" +
                "\tcommands=48\taccepted=48\tfrozenPolls=49\tverified=2\tblind=0" +
                "\tdurationCs=4501\texhausted=1"
        ) as GmsFreezerFastLaneRecord.ShieldResult
        assertEquals(48, shield.commandCount)
        assertEquals(45_010L, shield.durationCentiseconds * 10L)
        assertTrue(shield.exhausted)
    }

    @Test
    fun preservesRawLogVerbatim() {
        val raw = "I/am_app_frozen: [123,com.google.android.gms]"
        val record = GmsFreezerFastLaneProtocol.parse(
            "__LUONNOTAR_FAST_LANE_LOG__\t$raw"
        ) as GmsFreezerFastLaneRecord.RawLog
        assertEquals(raw, record.line)
    }
}
