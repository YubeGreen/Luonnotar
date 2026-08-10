package com.yubegreen.luonnotar.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.yubegreen.luonnotar.notification.ArrivalNotificationListenerFaultBridge
import com.yubegreen.luonnotar.notification.NotificationListenerFaultInjectionPolicy
import com.yubegreen.luonnotar.util.LogManager

/**
 * DUMP-protected listener fault-injection control plane.
 *
 * All fault state is process-local and time-bounded. It never changes the
 * user's NotificationListener authorization. Sticky mode exists only so the
 * shell guardian's ordinary -> strong escalation can be deterministically
 * exercised on a healthy device.
 */
class AdbNotificationListenerFaultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        val app = context.applicationContext
        val authorized =
            NotificationManagerCompat.getEnabledListenerPackages(app)
                .contains(app.packageName)

        when (action) {
            ACTION_TEST_UNBIND -> {
                val requested =
                    authorized && ArrivalNotificationListenerFaultBridge.requestDiagnosticUnbind()
                LogManager.event(
                    app,
                    "notification_listener_fault_unbind_requested",
                    mapOf("authorized" to authorized, "requested" to requested)
                )
                finish(
                    requested,
                    "authorized=$authorized;requestUnbindRequested=$requested"
                )
            }

            ACTION_TEST_STICKY_UNBIND -> {
                val requestedDurationMs =
                    intent?.getLongExtra(EXTRA_DURATION_MS, 0L) ?: 0L
                val durationMs =
                    NotificationListenerFaultInjectionPolicy.boundedDurationMs(
                        requestedDurationMs
                    )
                val requested =
                    authorized &&
                        ArrivalNotificationListenerFaultBridge
                            .requestDiagnosticStickyUnbind(durationMs)
                LogManager.event(
                    app,
                    "notification_listener_fault_sticky_started",
                    mapOf(
                        "authorized" to authorized,
                        "requested" to requested,
                        "durationMs" to durationMs
                    )
                )
                finish(
                    requested,
                    "authorized=$authorized;stickyActive=" +
                        ArrivalNotificationListenerFaultBridge.stickyFaultActive() +
                        ";durationMs=$durationMs;remainingMs=" +
                        ArrivalNotificationListenerFaultBridge.stickyFaultRemainingMs()
                )
            }

            ACTION_TEST_RELEASE -> {
                val released =
                    ArrivalNotificationListenerFaultBridge.releaseStickyFault()
                LogManager.event(
                    app,
                    "notification_listener_fault_sticky_released",
                    mapOf("released" to released)
                )
                finish(
                    true,
                    "stickyReleased=$released;stickyActive=false;remainingMs=0"
                )
            }

            ACTION_TEST_STATUS -> {
                val active =
                    ArrivalNotificationListenerFaultBridge.stickyFaultActive()
                finish(
                    true,
                    "authorized=$authorized;stickyActive=$active;remainingMs=" +
                        ArrivalNotificationListenerFaultBridge.stickyFaultRemainingMs()
                )
            }

            else -> finish(false, "reason=unsupported_action")
        }
    }

    private fun finish(ok: Boolean, detail: String) {
        setResultCode(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        setResultData("ok=$ok;$detail")
    }

    companion object {
        const val ACTION_TEST_UNBIND =
            "com.yubegreen.luonnotar.action.ADB_NOTIFICATION_TEST_UNBIND"
        const val ACTION_TEST_STICKY_UNBIND =
            "com.yubegreen.luonnotar.action.ADB_NOTIFICATION_TEST_STICKY_UNBIND"
        const val ACTION_TEST_RELEASE =
            "com.yubegreen.luonnotar.action.ADB_NOTIFICATION_TEST_RELEASE"
        const val ACTION_TEST_STATUS =
            "com.yubegreen.luonnotar.action.ADB_NOTIFICATION_TEST_STATUS"
        const val EXTRA_DURATION_MS = "durationMs"
    }
}
