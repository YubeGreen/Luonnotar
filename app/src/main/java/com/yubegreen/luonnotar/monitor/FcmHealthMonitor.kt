package com.yubegreen.luonnotar.monitor

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

data class FcmHealthEvidence(
    val googlePlayServicesInstalled: Boolean,
    val available: Boolean,
    val statusCode: Int,
    val tokenOwnedByLuonnotar: Boolean,
    val lastKnownTokenRefreshWallTime: Long?,
    val explanation: String
)

class FcmHealthMonitor(private val context: Context) {
    fun inspect(): FcmHealthEvidence {
        val gmsInstalled = isInstalled("com.google.android.gms")
        val availability = GoogleApiAvailability.getInstance()
        val statusCode = availability.isGooglePlayServicesAvailable(context)
        val available = statusCode == ConnectionResult.SUCCESS
        return FcmHealthEvidence(
            googlePlayServicesInstalled = gmsInstalled,
            available = available,
            statusCode = statusCode,
            tokenOwnedByLuonnotar = false,
            lastKnownTokenRefreshWallTime = null,
            explanation = when {
                available ->
                    "Google Play 服务可用 · 普通 APK 无权读取 WhatsApp/GMS 私有 token 或 socket"
                !gmsInstalled ->
                    "未安装 Google Play 服务 · 无可用 Google FCM 环境"
                availability.isUserResolvableError(statusCode) ->
                    "Google Play 服务不可用（${availability.getErrorString(statusCode)}）· 可由用户修复"
                else ->
                    "Google Play 服务不可用（${availability.getErrorString(statusCode)}）"
            }
        )
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
