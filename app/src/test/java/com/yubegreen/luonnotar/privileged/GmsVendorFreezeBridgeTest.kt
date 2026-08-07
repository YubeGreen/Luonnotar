package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GmsVendorFreezeBridgeTest {
    @Test
    fun defensePolicyUsesOneNonEscalatingReconnectRound() {
        val plan = GmsVendorDefensePolicy.reconnectPlan()
        assertEquals(1, plan.maxRounds)
        assertFalse(plan.allowEmergencyEscalation)
        assertEquals(12_000L, GmsVendorDefensePolicy.STABLE_REQUIRED_MILLISECONDS)
        assertEquals(12_000L, GmsVendorDefensePolicy.PULSE_REQUIRED_MILLISECONDS)
    }

    @Test
    fun parsesReadyHeartbeatDefenseRecoveryAndLockRecords() {
        val ready = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_READY__\ttimeout=1\tsticky=1" +
                "\tstrategy=atomic_group_defense_episode\tshellPid=77" +
                "\tparentStartTicks=1001\tshellStartTicks=1002" +
                "\theartbeatPath=/data/local/tmp/hb\townerPath=/data/local/tmp/owner"
        ) as GmsVendorFreezeBridgeRecord.Ready
        assertTrue(ready.timeout)
        assertTrue(ready.sticky)
        assertEquals(GmsVendorDefensePolicy.STRATEGY, ready.strategy)
        assertEquals(77, ready.shellPid)
        assertEquals("1001", ready.parentStartTimeTicks)
        assertEquals("1002", ready.shellStartTimeTicks)
        assertEquals("/data/local/tmp/hb", ready.heartbeatPath)

        val heartbeat = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_HEARTBEAT__\tatCs=1234" +
                "\tmainPid=41\tmainState=frozen" +
                "\tpersistentPid=42\tpersistentState=thawed" +
                "\twhatsappPid=43\twhatsappState=frozen" +
                "\tsignalPid=44\tsignalState=thawed"
        ) as GmsVendorFreezeBridgeRecord.Heartbeat
        assertEquals(41, heartbeat.mainPid)
        assertEquals("frozen", heartbeat.mainState)
        assertEquals(42, heartbeat.persistentPid)
        assertEquals(43, heartbeat.whatsappPid)
        assertEquals("frozen", heartbeat.whatsappState)
        assertEquals(44, heartbeat.signalPid)


        val defense = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_DEFENSE__\tseq=8\tphase=refrozen" +
                "\telapsedCs=525\tstableCs=0\trefreezes=3\tattempts=4" +
                "\tcommands=17\tmainPid=41\tpersistentPid=42\tdetail=vendor_returned"
        ) as GmsVendorFreezeBridgeRecord.Defense
        assertEquals(8L, defense.sequence)
        assertEquals("refrozen", defense.phase)
        assertEquals(5_250L, defense.elapsedCentiseconds * 10L)
        assertEquals(3, defense.refreezes)
        assertEquals(4, defense.attempts)
        assertEquals(17, defense.commandCount)
        assertEquals(41, defense.mainPid)
        assertEquals(42, defense.persistentPid)

        val recovery = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_RECOVERY__\tseq=9" +
                "\ttarget=com.google.android.gms\tpid=41\tpeerPid=42\tgroup=1" +
                "\tmode=adopt_release_group\tplainRc=0\tfreezeRc=0" +
                "\treleaseRc=0\tstickyRc=0\tverified=1\tadoptObserved=1" +
                "\tdurationCs=73\tconsecutive=2\tcommands=8\tdetail=ok"
        ) as GmsVendorFreezeBridgeRecord.Recovery
        assertEquals("adopt_release_group", recovery.mode)
        assertTrue(recovery.group)
        assertEquals(42, recovery.peerPid)
        assertTrue(recovery.adoptObserved)
        assertTrue(recovery.verified)
        assertEquals(0, recovery.stickyExitCode)
        assertEquals(730L, recovery.durationCentiseconds * 10L)
        assertEquals(8, recovery.commandCount)

        val lock = GmsVendorFreezeBridgeProtocol.parse(
            "__LUONNOTAR_VENDOR_BRIDGE_LOCK__\tseq=10" +
                "\ttarget=com.google.android.gms\tpid=42" +
                "\tfailures=3\tcooldownCs=1500"
        ) as GmsVendorFreezeBridgeRecord.VendorLock
        assertEquals(15_000L, lock.cooldownCentiseconds * 10L)
    }

    @Test
    fun scriptDirectlyWatchesCgroupAndUsesAtomicPidVerifiedAdoptRelease() {
        val script = GmsVendorFreezeBridgeScript.build(
            parentPid = 4242,
            stickyUnfreeze = true,
            monitorGms = true,
            monitorWhatsApp = true,
            monitorSignal = true,
            baseRoot = "/data/local/tmp"
        )

        assertTrue(script.contains("/cgroup.freeze"))
        assertTrue(script.contains("freezer.state"))
        assertTrue(script.contains("pid_matches_target"))
        assertTrue(script.contains("cmd activity unfreeze \"${'$'}_pid\""))
        assertTrue(script.contains("cmd activity unfreeze --sticky \"${'$'}_pid\""))
        assertTrue(script.contains("cmd activity freeze \"${'$'}_pid\""))
        assertTrue(script.contains("recover_gms_group"))
        assertTrue(script.contains("run_parallel_pair"))
        assertTrue(script.contains("run_group_phase release 1 1"))
        assertTrue(script.contains("main_state\" = \"thawed"))
        assertTrue(script.contains("persistent_state\" = \"thawed"))
        assertTrue(script.contains("adopt_release_group"))
        assertTrue(script.contains("adopt_unconfirmed_release_group"))
        assertTrue(script.contains("framework_release_retry_group"))
        assertTrue(script.contains("framework_lists_pid"))
        assertTrue(script.contains("ledgerBefore"))
        assertTrue(script.contains("freeze:skipped_framework_already_knows"))
        assertTrue(script.contains("whatsapp_target='com.whatsapp'"))
        assertTrue(script.contains("signal_target='org.thoughtcrime.securesms'"))
        assertTrue(script.contains("inspect_single whatsapp"))
        assertTrue(script.contains("inspect_single signal"))
        assertTrue(script.contains("heartbeat_loop"))
        assertTrue(script.contains("heartbeat_file=\"${'$'}base.heartbeat\""))
        assertTrue(script.contains("claim_command_owner"))
        assertTrue(script.contains("command_owner_record_matches_self"))
        assertTrue(script.contains("command_owner_is_self"))
        assertTrue(script.contains("parentStartTicks"))
        assertTrue(script.contains("shellStartTicks"))
        assertTrue(script.contains("pid_start_matches"))
        assertTrue(script.contains("require_command_owner"))
        assertTrue(script.contains("type=owner_lost"))
        assertTrue(script.contains("if command_owner_record_matches_self; then"))
        assertTrue(script.contains("owner_conflict"))
        assertTrue(script.contains("heartbeatPath=%s"))
        assertTrue(script.contains("_expected_owner_heartbeat=\"${'$'}base_root-${'$'}_owner_shell.heartbeat\""))
        assertTrue(script.contains("luonnotar-freezer-command-owner"))
        assertTrue(script.contains("command_failed"))
        assertTrue(script.contains("gms_group_incomplete"))
        assertTrue(script.contains("gms_incomplete_since_cs"))
        assertTrue(script.contains("gms_last_state=\"incomplete\""))
        assertTrue(script.contains("postStickyState"))
        assertTrue(script.contains("sticky:disabled"))
        assertTrue(script.contains("commands=%s"))
        assertTrue(script.contains("sleep 0.15"))
        assertTrue(script.contains("sleep 1"))
        assertTrue(script.contains("strategy=${GmsVendorDefensePolicy.STRATEGY}"))
        assertTrue(script.contains("start_gms_defense"))
        assertTrue(script.contains("tick_gms_defense"))
        assertTrue(script.contains("pulse_ready"))
        assertTrue(script.contains("defense_stable_group"))
        assertTrue(script.contains("gms_defense_stable_required_cs=1200"))
        assertTrue(script.contains("gms_defense_escalation_required_cs=12000"))
        assertFalse(script.contains("gms_failures="))
        assertTrue(script.contains("while pid_start_matches \"${'$'}parent_pid\""))
        assertFalse(script.contains("echo 0 > \"${'$'}STATE_FILE\""))
    }

    @Test
    fun scriptSupportsWhatsappAndSignalWithoutOwningGms() {
        val script = GmsVendorFreezeBridgeScript.build(
            parentPid = 4242,
            stickyUnfreeze = true,
            monitorGms = false,
            monitorWhatsApp = true,
            monitorSignal = true
        )

        assertTrue(script.contains("main_target=''"))
        assertTrue(script.contains("persistent_target=''"))
        assertTrue(script.contains("whatsapp_target='com.whatsapp'"))
        assertTrue(script.contains("signal_target='org.thoughtcrime.securesms'"))
        assertTrue(script.contains("CURRENT_STATE=\"disabled\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeBaseRoot() {
        GmsVendorFreezeBridgeScript.build(
            parentPid = 4242,
            stickyUnfreeze = true,
            baseRoot = "/tmp/x;rm -rf /"
        )
    }
}
