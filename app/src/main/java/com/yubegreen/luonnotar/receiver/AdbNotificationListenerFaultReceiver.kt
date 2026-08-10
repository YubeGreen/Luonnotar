package com.yubegreen.luonnotar.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.yubegreen.luonnotar.notification.ArrivalNotificationListenerFaultBridge
import com.yubegreen.luonnotar.util.LogManager

/**
 * DUMP-protected r300 fault injection.
 *
 * requestUnbind() disconnects the runtime NotificationListenerService while
 * preserving the user's notification-access grant. This reproduces the exact
 * authorized=true/runtime=false state needed to test shell self-healing.
 */
class AdbNotificationListenerFaultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action != ACTION_TEST_UNBIND) {
            setResultCode(Activity.RESULT_CANCELED)
            setResultData("ok=false;reason=unsupported_action")
            return
        }

        val app = context.applicationContext
        val authorized =
            NotificationManagerCompat.getEnabledListenerPackages(app)
                .contains(app.packageName)
        val requested =
            authorized && ArrivalNotificationListenerFaultBridge.requestDiagnosticUnbind()

        LogManager.event(
            app,
            "notification_listener_fault_unbind_requested",
            mapOf(
                "authorized" to authorized,
                "requested" to requested
            )
        )

        setResultCode(if (requested) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        setResultData(
            "ok=$requested;authorized=$authorized;requestUnbindRequested=$requested"
        )
    }

    companion object {
        const val ACTION_TEST_UNBIND =
            "com.yubegreen.luonnotar.action.ADB_NOTIFICATION_TEST_UNBIND"
    }
}
