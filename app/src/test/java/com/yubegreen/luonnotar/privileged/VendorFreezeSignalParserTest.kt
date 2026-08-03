package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorFreezeSignalParserTest {
    private val processes = GuardianEngineConfig.DEFAULT_PROCESS_TARGETS
    private val packages = GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS

    @Test fun parsesOriginOsAospFreezeEvent() {
        val signal = parse(
            "I am_app_frozen: [0,10171,com.google.android.gms,from fast_freezer]"
        )

        assertEquals(VendorFreezeSignalKind.AOSP_APP_FROZEN, signal?.kind)
        assertEquals("com.google.android.gms", signal?.packageName)
        assertFalse(signal?.deliveryCritical ?: true)
    }

    @Test fun mapsGmsPersistentProcessBackToGmsPackage() {
        val signal = parse(
            "I am_app_frozen: [0,10171,com.google.android.gms.persistent,from fast_freezer]"
        )

        assertEquals("com.google.android.gms", signal?.packageName)
        assertEquals("com.google.android.gms.persistent", signal?.processName)
    }

    @Test fun parsesHyperOsGreezerDenialForWhatsapp() {
        val signal = parse(
            "W BroadcastQueue: Greezer Denial: sending Intent { act=android.intent.action.BATTERY_CHANGED }, " +
                "due to receiver ProcessRecord{6d73ccf 9987:com.whatsapp/u0a367} (uid 10367) need cached broadcast"
        )

        assertEquals(VendorFreezeSignalKind.XIAOMI_GREEZER_DENIAL, signal?.kind)
        assertEquals("com.whatsapp", signal?.packageName)
        assertFalse(signal?.deliveryCritical ?: true)
    }

    @Test fun marksC2dmGreezerDenialAsDeliveryCritical() {
        val signal = parse(
            "W BroadcastQueue: Greezer Denial: sending Intent { " +
                "act=com.google.android.c2dm.intent.RECEIVE pkg=com.whatsapp " +
                "cmp=com.whatsapp/com.google.firebase.iid.FirebaseInstanceIdReceiver }, " +
                "due to receiver ProcessRecord{6d73ccf 9987:com.whatsapp/u0a367}"
        )

        assertEquals("com.whatsapp", signal?.packageName)
        assertTrue(signal?.deliveryCritical == true)
    }

    @Test fun parsesUidFrozenWakeLockWorkSource() {
        val signal = parse(
            "I PowerManagerServiceImpl: Partial wakeLock:'GOOGLE_C2DM' " +
                "ws=WorkSource{10367 com.whatsapp}, disabled: true, reason: UidFrozen"
        )

        assertEquals(VendorFreezeSignalKind.UID_FROZEN_WAKELOCK, signal?.kind)
        assertEquals("com.whatsapp", signal?.packageName)
        assertTrue(signal?.deliveryCritical == true)
    }

    @Test fun parsesCancelledGcmCallbackTarget() {
        val signal = parse(
            "W GCM: broadcast intent callback: result=CANCELLED forIntent { " +
                "act=com.google.android.c2dm.intent.RECEIVE pkg=com.whatsapp }"
        )

        assertEquals(VendorFreezeSignalKind.GCM_DELIVERY_CANCELLED, signal?.kind)
        assertEquals("com.whatsapp", signal?.packageName)
        assertTrue(signal?.deliveryCritical == true)
    }

    @Test fun parsesVendorAutostartDenialOnlyForGuardedPackages() {
        val signal = parse(
            "W BroadcastQueueInjector: Unable to launch app com.tailscale.ipn/10371 for broadcast " +
                "Intent { act=x }: process is not permitted to auto start"
        )
        val ignored = parse(
            "W BroadcastQueueInjector: Unable to launch app com.example.other/10999 for broadcast " +
                "Intent { act=x }: process is not permitted to auto start"
        )

        assertEquals(VendorFreezeSignalKind.AUTOSTART_LAUNCH_DENIED, signal?.kind)
        assertEquals("com.tailscale.ipn", signal?.packageName)
        assertNull(ignored)
    }

    @Test fun policyUsesLongerHoldForDeliveryFailure() {
        val ordinary = parse(
            "W BroadcastQueue: Greezer Denial: sending Intent { act=x }, " +
                "due to receiver ProcessRecord{6d73ccf 9987:com.whatsapp/u0a367}"
        )!!
        val critical = parse(
            "W GCM: broadcast intent callback: result=CANCELLED forIntent { " +
                "act=com.google.android.c2dm.intent.RECEIVE pkg=com.whatsapp }"
        )!!

        assertEquals(
            VendorFreezeRecoveryPolicy.GENERAL_HOLD_MS,
            VendorFreezeRecoveryPolicy.holdDurationMs(ordinary)
        )
        assertEquals(
            VendorFreezeRecoveryPolicy.DELIVERY_CRITICAL_HOLD_MS,
            VendorFreezeRecoveryPolicy.holdDurationMs(critical)
        )
    }

    private fun parse(line: String) = VendorFreezeSignalParser.parse(line, processes, packages)
}
