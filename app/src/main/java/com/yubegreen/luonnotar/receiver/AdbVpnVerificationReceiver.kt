package com.yubegreen.luonnotar.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.yubegreen.luonnotar.monitor.SupportedVpnProvider
import com.yubegreen.luonnotar.util.LogManager
import com.yubegreen.luonnotar.util.LuonnotarPreferences
import java.io.File
import java.security.MessageDigest

class AdbVpnVerificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_VERIFY) return
        val activePackage = intent.getStringExtra(EXTRA_ACTIVE_PACKAGE).orEmpty()
        if (!SupportedVpnProvider.isSupported(activePackage)) return
        val alwaysOn = intent.getBooleanExtra(EXTRA_ALWAYS_ON, false)
        val lockdown = intent.getBooleanExtra(EXTRA_LOCKDOWN, false)
        val bypassable = intent.getBooleanExtra(EXTRA_BYPASSABLE, true)
        val gmsRouted = intent.getBooleanExtra(EXTRA_GMS_ROUTED, false)
        val whatsappRouted = intent.getBooleanExtra(EXTRA_WHATSAPP_ROUTED, false)
        val whatsappBusinessRouted =
            intent.getBooleanExtra(EXTRA_WHATSAPP_BUSINESS_ROUTED, false)
        val internetRouted = intent.getBooleanExtra(
            EXTRA_INTERNET_ROUTED,
            false
        )
        val networkHandle = intent.getLongExtra(EXTRA_NETWORK_HANDLE, -1L)
        if (networkHandle < 0) return
        val evidenceHash = importFingerprint(
            activePackage,
            alwaysOn,
            lockdown,
            bypassable,
            gmsRouted,
            whatsappRouted,
            whatsappBusinessRouted,
            internetRouted,
            networkHandle
        )
        val prefs = LuonnotarPreferences.deviceProtected(context)
        if (
            !prefs.getBoolean(LuonnotarPreferences.KEY_VPN, false) ||
            prefs.getLong(LuonnotarPreferences.KEY_NETWORK_HANDLE, -1L) != networkHandle
        ) return
        val committed = prefs.edit()
            .putLong(LuonnotarPreferences.KEY_ADB_VERIFIED_WALL, System.currentTimeMillis())
            .putLong(LuonnotarPreferences.KEY_ADB_VERIFIED_ELAPSED, SystemClock.elapsedRealtime())
            .putString(LuonnotarPreferences.KEY_ADB_VERIFIED_BOOT_ID, readBootId())
            .putString(LuonnotarPreferences.KEY_ADB_ACTIVE_VPN_PACKAGE, activePackage)
            .putBoolean(LuonnotarPreferences.KEY_ADB_ALWAYS_ON, alwaysOn)
            .putBoolean(LuonnotarPreferences.KEY_ADB_LOCKDOWN, lockdown)
            .putBoolean(LuonnotarPreferences.KEY_ADB_BYPASSABLE, bypassable)
            .putBoolean(LuonnotarPreferences.KEY_ADB_GMS_ROUTED, gmsRouted)
            .putBoolean(LuonnotarPreferences.KEY_ADB_WHATSAPP_ROUTED, whatsappRouted)
            .putBoolean(
                LuonnotarPreferences.KEY_ADB_WHATSAPP_BUSINESS_ROUTED,
                whatsappBusinessRouted
            )
            .putBoolean(LuonnotarPreferences.KEY_ADB_INTERNET_ROUTED, internetRouted)
            .putString(LuonnotarPreferences.KEY_ADB_EVIDENCE_HASH, evidenceHash)
            .putLong(LuonnotarPreferences.KEY_ADB_NETWORK_HANDLE, networkHandle)
            .commit()
        LogManager.event(
            context,
            if (committed) "adb_vpn_evidence_imported" else "adb_vpn_import_commit_failed",
            mapOf(
                "activePackage" to activePackage,
                "alwaysOn" to alwaysOn,
                "lockdown" to lockdown,
                "bypassable" to bypassable,
                "gmsRouted" to gmsRouted,
                "whatsappRouted" to whatsappRouted,
                "whatsappBusinessRouted" to whatsappBusinessRouted,
                "internetRouted" to internetRouted,
                "networkHandle" to networkHandle,
                "evidenceHash" to evidenceHash
            )
        )
    }

    private fun readBootId(): String =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrDefault("unavailable")

    private fun importFingerprint(vararg values: Any): String =
        MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("|").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val ACTION_VERIFY = "com.yubegreen.luonnotar.action.ADB_VERIFY_VPN"
        const val EXTRA_ACTIVE_PACKAGE = "active_package"
        const val EXTRA_ALWAYS_ON = "always_on"
        const val EXTRA_LOCKDOWN = "lockdown"
        const val EXTRA_BYPASSABLE = "bypassable"
        const val EXTRA_GMS_ROUTED = "gms_routed"
        const val EXTRA_WHATSAPP_ROUTED = "whatsapp_routed"
        const val EXTRA_WHATSAPP_BUSINESS_ROUTED = "whatsapp_business_routed"
        const val EXTRA_INTERNET_ROUTED = "internet_routed"
        const val EXTRA_EVIDENCE_HASH = "evidence_hash"
        const val EXTRA_NETWORK_HANDLE = "network_handle"
    }
}
