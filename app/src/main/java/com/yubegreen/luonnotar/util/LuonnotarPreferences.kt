package com.yubegreen.luonnotar.util

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object LuonnotarPreferences {
    @Volatile
    private var initializedKeeperProcessPid = 0

    const val PREFERENCES_FILE_NAME = "luonnotar_preferences"
    const val DATASTORE_FILE_NAME = "luonnotar_settings"

    const val KEY_ENABLED = "guardian_enabled"
    const val KEY_PAUSED = "guardian_paused"
    const val KEY_AGGRESSIVE_VIVO_MODE = "aggressive_vivo_mode"
    const val KEY_GUARDIAN_PROFILE = "guardian_runtime_profile"
    const val KEY_EXPERIMENT_PERMANENT_CPU_LOCK = "permanent_cpu_lock"
    const val KEY_EXPERIMENT_SCREEN_OFF_CPU_GUARD =
        "screen_off_cpu_guard"
    const val KEY_SCREEN_OFF_CPU_GUARD_INTEGRATED_V2 =
        "screen_off_cpu_guard_integrated_v2"
    const val KEY_EXPERIMENT_SCOPED_CPU_LOCK = "scoped_cpu_lock"
    const val KEY_EXPERIMENT_HIGH_PERF_WIFI_LOCK = "high_perf_wifi_lock"
    const val KEY_EXPERIMENT_SCREEN_EVENT_PROBE = "screen_event_probe"
    const val KEY_EXPERIMENT_PERIODIC_DNS = "periodic_dns"
    const val KEY_EXPERIMENT_PERIODIC_HTTPS = "periodic_https"
    const val KEY_EXPERIMENT_AUTOMATIC_MTALK = "automatic_mtalk"
    const val KEY_EXPERIMENT_PERSISTENT_NETWORK_LEASE =
        "persistent_network_lease"
    // Legacy 1.7.14/1.7.15 preference, retained only for migration.
    const val KEY_EXPERIMENT_PERSISTENT_MTALK_SOCKET =
        "persistent_mtalk_socket"
    const val KEY_EXPERIMENT_PERSISTENT_HEARTBEAT_SOCKET =
        "persistent_heartbeat_socket"
    const val KEY_EXPERIMENT_FREQUENT_NOTIFICATION_REFRESH =
        "frequent_notification_refresh"
    const val KEY_LAB_EXTREME_LEVEL = "lab_extreme_level"
    const val KEY_EXPERIMENT_SESSION_ACTIVE =
        "experiment_session_active"
    const val KEY_EXPERIMENT_SESSION_ID = "experiment_session_id"
    const val KEY_EXPERIMENT_SESSION_NAME = "experiment_session_name"
    const val KEY_EXPERIMENT_SESSION_SOURCE = "experiment_session_source"
    const val KEY_EXPERIMENT_SESSION_BOOT_ID = "experiment_session_boot_id"
    const val KEY_EXPERIMENT_SESSION_STARTED_WALL =
        "experiment_session_started_wall"
    const val KEY_EXPERIMENT_SESSION_STARTED_ELAPSED =
        "experiment_session_started_elapsed"
    const val KEY_EXPERIMENT_SESSION_MARK_COUNT =
        "experiment_session_mark_count"
    const val KEY_EXPERIMENT_SESSION_LAST_MARK =
        "experiment_session_last_mark"
    const val KEY_EXPERIMENT_SESSION_LAST_MARK_WALL =
        "experiment_session_last_mark_wall"
    const val KEY_EXPERIMENT_SESSION_LAST_MARK_ELAPSED =
        "experiment_session_last_mark_elapsed"
    const val KEY_EXPERIMENT_SESSION_LAST_EVENT =
        "experiment_session_last_event"
    const val KEY_EXPERIMENT_SESSION_STOPPED_WALL =
        "experiment_session_stopped_wall"
    const val KEY_EXPERIMENT_SESSION_STOPPED_ELAPSED =
        "experiment_session_stopped_elapsed"
    const val KEY_EXPERIMENT_SESSION_LAST_DURATION_MS =
        "experiment_session_last_duration_ms"
    const val KEY_MONITOR_GMS = "monitor_target_gms"
    const val KEY_MONITOR_WHATSAPP = "monitor_target_whatsapp"
    const val KEY_MONITOR_WHATSAPP_BUSINESS =
        "monitor_target_whatsapp_business"
    const val KEY_RUNTIME_BOOT_ID = "runtime_boot_id"
    const val KEY_STATE = "guardian_state"
    const val KEY_PID = "guardian_pid"
    const val KEY_KEEPER_PROCESS_PID = "keeper_process_pid"
    const val KEY_PROCESS_SEQUENCE = "process_sequence"
    const val KEY_SERVICE_GENERATION = "service_generation"
    const val KEY_HTTP_EVIDENCE_GENERATION = "http_evidence_generation"
    const val KEY_SUCCESS_EVIDENCE_GENERATION = "success_evidence_generation"
    const val KEY_ATTEMPT_EVIDENCE_GENERATION = "attempt_evidence_generation"
    const val KEY_SERVICE_STARTED_ELAPSED = "service_started_elapsed"
    const val KEY_SERVICE_DESTROYED_ELAPSED = "service_destroyed_elapsed"
    const val KEY_HEARTBEAT_ELAPSED = "heartbeat_elapsed"
    const val KEY_PERSISTENT_NETWORK_LEASE_STATE =
        "persistent_network_lease_state"
    const val KEY_PERSISTENT_NETWORK_LEASE_HANDLE =
        "persistent_network_lease_handle"
    const val KEY_PERSISTENT_NETWORK_LEASE_LAST_EVENT_ELAPSED =
        "persistent_network_lease_last_event_elapsed"
    const val KEY_PERSISTENT_MTALK_SOCKET_STATE =
        "persistent_mtalk_socket_state"
    const val KEY_PERSISTENT_MTALK_SOCKET_HANDLE =
        "persistent_mtalk_socket_handle"
    const val KEY_PERSISTENT_MTALK_SOCKET_LAST_EVENT_ELAPSED =
        "persistent_mtalk_socket_last_event_elapsed"
    const val KEY_PERSISTENT_MTALK_SOCKET_REASON =
        "persistent_mtalk_socket_reason"
    const val KEY_PERSISTENT_MTALK_SOCKET_CONNECT_COUNT =
        "persistent_mtalk_socket_connect_count"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_STATE =
        "persistent_heartbeat_socket_state"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_HANDLE =
        "persistent_heartbeat_socket_handle"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_EVENT_ELAPSED =
        "persistent_heartbeat_socket_last_event_elapsed"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_REASON =
        "persistent_heartbeat_socket_reason"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_CONNECT_COUNT =
        "persistent_heartbeat_socket_total_connect_count"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_CONNECT_COUNT =
        "persistent_heartbeat_socket_session_connect_count"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_CONNECTION_STARTED_ELAPSED =
        "persistent_heartbeat_socket_connection_started_elapsed"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_HEARTBEAT_ELAPSED =
        "persistent_heartbeat_socket_last_heartbeat_elapsed"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_TOTAL_HEARTBEAT_COUNT =
        "persistent_heartbeat_socket_total_heartbeat_count"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_HEARTBEAT_COUNT =
        "persistent_heartbeat_socket_session_heartbeat_count"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_CONSECUTIVE_FAILURES =
        "persistent_heartbeat_socket_consecutive_failures"
    const val KEY_PERSISTENT_HEARTBEAT_SOCKET_BACKOFF_MS =
        "persistent_heartbeat_socket_backoff_ms"
    const val KEY_VPN = "default_is_vpn"
    const val KEY_VALIDATED = "default_validated"
    const val KEY_BYPASSABLE = "vpn_bypassable"
    const val KEY_BYPASSABLE_KNOWN = "vpn_bypassable_known"
    const val KEY_VPN_PROVIDER_PACKAGE = "vpn_provider_package"
    const val KEY_VPN_INTERNET_ROUTED = "vpn_internet_routed"
    const val KEY_VPN_ROUTE_STATE = "vpn_route_state"
    const val KEY_VPN_IPV4_DEFAULT_ROUTE = "vpn_ipv4_default_route"
    const val KEY_VPN_IPV6_DEFAULT_ROUTE = "vpn_ipv6_default_route"
    const val KEY_VPN_SESSION_COMPLETE = "vpn_session_complete"
    const val KEY_VPN_BLOCKED = "vpn_blocked"
    const val KEY_VPN_BLOCKED_KNOWN = "vpn_blocked_known"
    const val KEY_VPN_NOT_SUSPENDED = "vpn_not_suspended"
    const val KEY_VPN_SESSION_FINGERPRINT = "vpn_session_fingerprint"
    const val KEY_VPN_SESSION_GENERATION = "vpn_session_generation"
    const val KEY_VPN_INTERFACE_NAME = "vpn_interface_name"
    const val KEY_VPN_LINK_ADDRESSES = "vpn_link_addresses"
    const val KEY_VPN_DNS_SERVERS = "vpn_dns_servers"
    const val KEY_VPN_MTU = "vpn_mtu"
    const val KEY_VPN_UNDERLYING_HANDLES = "vpn_underlying_handles"
    const val KEY_VPN_SESSION_HEALTH = "vpn_session_health"
    const val KEY_VPN_DNS_HEALTH = "vpn_dns_health"
    const val KEY_VPN_HTTPS_HEALTH = "vpn_https_health"
    const val KEY_FCM_HEALTH = "fcm_health"
    const val KEY_WHATSAPP_DELIVERY_HEALTH = "whatsapp_delivery_health"
    const val KEY_VPN_DNS_LAST_ATTEMPT_ELAPSED =
        "vpn_dns_last_attempt_elapsed"
    const val KEY_VPN_DNS_LAST_SUCCESS_ELAPSED =
        "vpn_dns_last_success_elapsed"
    const val KEY_VPN_DNS_LAST_RTT = "vpn_dns_last_rtt_ms"
    const val KEY_VPN_DNS_LAST_ERROR = "vpn_dns_last_error"
    const val KEY_VPN_DNS_FAILURES = "vpn_dns_failures"
    const val KEY_VPN_DNS_EVIDENCE_SESSION_FINGERPRINT =
        "vpn_dns_evidence_session_fingerprint"
    const val KEY_MTALK_LAST_ATTEMPT_ELAPSED =
        "mtalk_last_attempt_elapsed"
    const val KEY_MTALK_LAST_SESSION_FINGERPRINT =
        "mtalk_last_session_fingerprint"
    const val KEY_MTALK_IPV4_DNS = "mtalk_ipv4_dns"
    const val KEY_MTALK_IPV6_DNS = "mtalk_ipv6_dns"
    const val KEY_MTALK_IPV4_TCP_5228 = "mtalk_ipv4_tcp_5228"
    const val KEY_MTALK_IPV4_TCP_5229 = "mtalk_ipv4_tcp_5229"
    const val KEY_MTALK_IPV4_TCP_5230 = "mtalk_ipv4_tcp_5230"
    const val KEY_MTALK_IPV4_TCP_443 = "mtalk_ipv4_tcp_443"
    const val KEY_MTALK_IPV6_TCP_5228 = "mtalk_ipv6_tcp_5228"
    const val KEY_MTALK_IPV6_TCP_5229 = "mtalk_ipv6_tcp_5229"
    const val KEY_MTALK_IPV6_TCP_5230 = "mtalk_ipv6_tcp_5230"
    const val KEY_MTALK_IPV6_TCP_443 = "mtalk_ipv6_tcp_443"
    const val KEY_MTALK_RESULT_SUMMARY = "mtalk_result_summary"
    const val KEY_LAST_ATTEMPT_SESSION_FINGERPRINT =
        "last_attempt_session_fingerprint"
    const val KEY_LAST_SUCCESS_SESSION_FINGERPRINT =
        "last_success_session_fingerprint"
    const val KEY_TAILSCALE_PRESENT = "tailscale_present"
    const val KEY_TAILSCALE_NETWORK_HANDLE = "tailscale_network_handle"
    const val KEY_TAILSCALE_COMPLETE = "tailscale_evidence_complete"
    const val KEY_TAILSCALE_VALIDATED = "tailscale_validated"
    const val KEY_TAILSCALE_BLOCKED = "tailscale_blocked"
    const val KEY_TAILSCALE_BLOCKED_KNOWN = "tailscale_blocked_known"
    const val KEY_TAILSCALE_SUSPENDED = "tailscale_suspended"
    const val KEY_TAILSCALE_SELF_EXCLUDED = "tailscale_self_excluded"
    const val KEY_TAILSCALE_ROUTE_STATE = "tailscale_route_state"
    const val KEY_TAILSCALE_DNS_SERVERS = "tailscale_dns_servers"
    const val KEY_TAILSCALE_UNDERLYING_HANDLES =
        "tailscale_underlying_handles"
    const val KEY_TAILSCALE_DNS_LAST_ATTEMPT_ELAPSED =
        "tailscale_dns_last_attempt_elapsed"
    const val KEY_TAILSCALE_DNS_LAST_SUCCESS_ELAPSED =
        "tailscale_dns_last_success_elapsed"
    const val KEY_TAILSCALE_DNS_LAST_RTT = "tailscale_dns_last_rtt_ms"
    const val KEY_TAILSCALE_DNS_LAST_ERROR = "tailscale_dns_last_error"
    const val KEY_TAILSCALE_DNS_V4 = "tailscale_dns_v4"
    const val KEY_TAILSCALE_DNS_V6 = "tailscale_dns_v6"
    const val KEY_TAILSCALE_DNS_FAILURES = "tailscale_dns_failures"
    const val KEY_TAILSCALE_DNS_EVIDENCE_GENERATION =
        "tailscale_dns_evidence_generation"
    const val KEY_TAILSCALE_DNS_EVIDENCE_NETWORK_HANDLE =
        "tailscale_dns_evidence_network_handle"
    const val KEY_LOCKDOWN = "lockdown_enabled"
    const val KEY_LOCKDOWN_KNOWN = "lockdown_known"
    const val KEY_ALWAYS_ON = "always_on_enabled"
    const val KEY_ALWAYS_ON_KNOWN = "always_on_known"
    const val KEY_NETWORK_HANDLE = "network_handle"
    const val KEY_TRANSPORT = "underlying_transport"
    const val KEY_UNDERLAY_SOURCE = "underlay_source"
    const val KEY_LAST_EXPLICIT_UNDERLAY = "last_explicit_underlay"
    const val KEY_LAST_WIFI_SEEN_ELAPSED = "last_wifi_seen_elapsed"
    const val KEY_UNDERLAY_UNKNOWN_SINCE = "underlay_unknown_since_elapsed"
    const val KEY_UNDERLAY_HISTORY_BOOT_ID = "underlay_history_boot_id"
    const val KEY_WAKE_LOCK = "wake_lock_held"
    const val KEY_CONTINUOUS_WAKE_LOCK =
        "continuous_wake_lock_held"
    const val KEY_WIFI_LOCK = "wifi_lock_held"
    const val KEY_LAST_ATTEMPT_RTT = "last_attempt_rtt_ms"
    const val KEY_LAST_ATTEMPT_ELAPSED = "last_attempt_elapsed"
    const val KEY_LAST_ATTEMPT_NETWORK_HANDLE = "last_attempt_network_handle"
    const val KEY_LAST_SUCCESS_RTT = "last_success_rtt_ms"
    const val KEY_LAST_HTTP_CODE = "last_http_code"
    const val KEY_LAST_SUCCESS_ELAPSED = "last_success_elapsed"
    const val KEY_LAST_SUCCESS_NETWORK_HANDLE = "last_success_network_handle"
    const val KEY_CONSECUTIVE_FAILURES = "consecutive_failures"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_LAST_START_REASON = "last_start_reason"
    const val KEY_LAST_BOOT_BROADCAST = "last_boot_broadcast"
    const val KEY_BOOT_RECOVERY_CLAIM_BOOT_ID = "boot_recovery_claim_boot_id"
    const val KEY_BOOT_RECOVERY_CLAIM_ACTION = "boot_recovery_claim_action"
    const val KEY_BOOT_RECOVERY_CLAIM_ELAPSED = "boot_recovery_claim_elapsed"
    const val KEY_BOOT_RECOVERY_DISPATCH_ACCEPTED =
        "boot_recovery_dispatch_accepted"
    const val KEY_RECOVERY_FAILURE = "recovery_failure"
    const val KEY_RECOVERY_FAILURE_SERVICE = "recovery_failure_service"
    const val KEY_RECOVERY_FAILURE_SERVICE_ELAPSED = "recovery_failure_service_elapsed"
    const val KEY_RECOVERY_FAILURE_ALARM = "recovery_failure_alarm"
    const val KEY_RECOVERY_FAILURE_ALARM_ELAPSED = "recovery_failure_alarm_elapsed"
    const val KEY_RECOVERY_FAILURE_NOTIFICATION = "recovery_failure_notification"
    const val KEY_RECOVERY_FAILURE_NOTIFICATION_ELAPSED = "recovery_failure_notification_elapsed"
    const val KEY_RECOVERY_FAILURE_BOOT = "recovery_failure_boot"
    const val KEY_RECOVERY_FAILURE_BOOT_ELAPSED = "recovery_failure_boot_elapsed"
    const val KEY_FCM_TOKEN_REFRESH = "fcm_token_refresh"
    const val KEY_MAX_TIMER_DRIFT = "max_timer_drift_ms"
    const val KEY_LAST_TIMER_DRIFT = "last_timer_drift_ms"
    const val KEY_LAST_TICK_UPTIME = "last_tick_uptime"
    const val KEY_LAST_TICK_ELAPSED = "last_tick_elapsed"
    const val KEY_NOTIFICATION_PRIVACY_ACK = "notification_privacy_ack"
    const val KEY_NOTIFICATION_LISTENER_CONNECTED = "notification_listener_connected"
    const val KEY_NOTIFICATION_LISTENER_PID = "notification_listener_pid"
    const val KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED =
        "notification_listener_heartbeat_elapsed"
    const val KEY_NOTIFICATION_LISTENER_REBIND_LAST_REQUEST_ELAPSED =
        "notification_listener_rebind_last_request_elapsed"
    const val KEY_NOTIFICATION_LISTENER_REBIND_COUNT =
        "notification_listener_rebind_count"
    const val KEY_GMS_BINDER_ANCHOR_ENABLED = "gms_binder_anchor_enabled"
    const val KEY_GMS_BINDER_ANCHOR_STATE = "gms_binder_anchor_state"
    const val KEY_GMS_BINDER_ANCHOR_CONNECTED_SINCE_ELAPSED =
        "gms_binder_anchor_connected_since_elapsed"
    const val KEY_GMS_BINDER_ANCHOR_LAST_EVENT_ELAPSED =
        "gms_binder_anchor_last_event_elapsed"
    const val KEY_GMS_BINDER_ANCHOR_RECONNECT_ATTEMPT =
        "gms_binder_anchor_reconnect_attempt"
    const val KEY_GMS_BINDER_ANCHOR_SUSPENSION_CAUSE =
        "gms_binder_anchor_suspension_cause"
    const val KEY_GMS_BINDER_ANCHOR_FAILURE_CODE =
        "gms_binder_anchor_failure_code"
    const val KEY_GMS_BINDER_ANCHOR_GMS_VERSION =
        "gms_binder_anchor_gms_version"
    const val KEY_GMS_BINDER_ANCHOR_SESSION_GENERATION =
        "gms_binder_anchor_session_generation"
    const val KEY_GMS_BINDER_ANCHOR_PID = "gms_binder_anchor_pid"
    const val KEY_GMS_BINDER_ANCHOR_BOOT_ID = "gms_binder_anchor_boot_id"
    const val KEY_GMS_PREVENTIVE_PULSE_LAST_ATTEMPT_ELAPSED =
        "gms_preventive_pulse_last_attempt_elapsed"
    const val KEY_GMS_PREVENTIVE_PULSE_COUNT =
        "gms_preventive_pulse_count"
    const val KEY_GMS_PREVENTIVE_PULSE_LAST_REASON =
        "gms_preventive_pulse_last_reason"
    const val KEY_NOTIFICATION_COUNT = "notification_arrival_count"
    const val KEY_NOTIFICATION_UPDATE_COUNT = "notification_update_count"
    const val KEY_NOTIFICATION_RECENT_FINGERPRINTS = "notification_recent_fingerprints"
    const val KEY_LAST_NOTIFICATION_PACKAGE = "last_notification_package"
    const val KEY_LAST_NOTIFICATION_POST_WALL = "last_notification_post_wall"
    const val KEY_LAST_NOTIFICATION_SEEN_WALL = "last_notification_seen_wall"
    const val KEY_LAST_SERVICE_EXIT = "last_service_exit"
    const val KEY_ALARM_EXACT = "recovery_alarm_exact"
    const val KEY_ALARM_SCHEDULED_ELAPSED = "recovery_alarm_scheduled_elapsed"
    const val KEY_ADB_VERIFIED_WALL = "adb_vpn_verified_wall"
    const val KEY_ADB_VERIFIED_ELAPSED = "adb_vpn_verified_elapsed"
    const val KEY_ADB_VERIFIED_BOOT_ID = "adb_vpn_verified_boot_id"
    const val KEY_ADB_ACTIVE_VPN_PACKAGE = "adb_active_vpn_package"
    const val KEY_ADB_ALWAYS_ON = "adb_always_on"
    const val KEY_ADB_LOCKDOWN = "adb_lockdown"
    const val KEY_ADB_BYPASSABLE = "adb_bypassable"
    const val KEY_ADB_GMS_ROUTED = "adb_gms_routed"
    const val KEY_ADB_WHATSAPP_ROUTED = "adb_whatsapp_routed"
    const val KEY_ADB_WHATSAPP_BUSINESS_ROUTED = "adb_whatsapp_business_routed"
    const val KEY_ADB_INTERNET_ROUTED = "adb_internet_routed"
    const val KEY_ADB_EVIDENCE_HASH = "adb_evidence_hash"
    const val KEY_ADB_NETWORK_HANDLE = "adb_network_handle"
    const val KEY_ADB_SESSION_FINGERPRINT = "adb_session_fingerprint"
    const val KEY_LAST_ALERTED_STATE = "last_alerted_state"
    const val KEY_LAST_ALERT_ELAPSED = "last_alert_elapsed"
    const val KEY_HAS_EVER_OBSERVED_VPN = "has_ever_observed_vpn"
    const val KEY_LAST_NOTIFICATION_GROUP_HASH = "last_notification_group_hash"
    const val KEY_LAST_NOTIFICATION_IS_GROUP_SUMMARY = "last_notification_is_group_summary"
    const val KEY_PUSH_TEST_LAST_SEQUENCE = "push_test_last_sequence"
    const val KEY_PUSH_TEST_LAST_SENDER_EPOCH_MS =
        "push_test_last_sender_epoch_ms"
    const val KEY_PUSH_TEST_LAST_SENDER_LOCAL_TIME =
        "push_test_last_sender_local_time"
    const val KEY_PUSH_TEST_LAST_SENDER_ZONE =
        "push_test_last_sender_zone"
    const val KEY_PUSH_TEST_LAST_SENDER_PRECISION_MS =
        "push_test_last_sender_precision_ms"
    const val KEY_PUSH_TEST_LAST_SEEN_WALL = "push_test_last_seen_wall"
    const val KEY_PUSH_TEST_LAST_SEEN_ELAPSED =
        "push_test_last_seen_elapsed"
    const val KEY_PUSH_TEST_LAST_DELAY_MS = "push_test_last_delay_ms"
    const val KEY_PUSH_TEST_LAST_PACKAGE = "push_test_last_package"
    const val KEY_PUSH_TEST_SCAN_LAST_SEQUENCE =
        "push_test_scan_last_sequence"
    const val KEY_PUSH_TEST_SCAN_LAST_SENDER_EPOCH_MS =
        "push_test_scan_last_sender_epoch_ms"
    const val KEY_PUSH_TEST_SCAN_LAST_NOTIFICATION_POST_WALL =
        "push_test_scan_last_notification_post_wall"
    const val KEY_PUSH_TEST_SCAN_LAST_SEEN_WALL =
        "push_test_scan_last_seen_wall"
    const val KEY_PUSH_TEST_SCAN_LAST_SEEN_ELAPSED =
        "push_test_scan_last_seen_elapsed"
    const val KEY_PUSH_TEST_SCAN_LAST_PACKAGE =
        "push_test_scan_last_package"
    const val KEY_PROBE_STARTED_ELAPSED = "probe_started_elapsed"
    const val KEY_PROBE_DEADLINE_ELAPSED = "probe_deadline_elapsed"
    const val KEY_PROBE_IN_FLIGHT = "probe_in_flight"
    const val KEY_RECOVERY_CONFIRMATION_PENDING = "recovery_confirmation_pending"
    const val KEY_RECOVERY_REQUESTED_ELAPSED = "recovery_requested_elapsed"
    const val KEY_LAST_RECOVERY_SUCCESS_ELAPSED = "last_recovery_success_elapsed"
    const val KEY_ALARM_INSURANCE = "recovery_alarm_insurance"
    const val KEY_HARD_RESTART_ALARM_ELAPSED =
        "hard_restart_alarm_elapsed"
    const val KEY_HARD_RESTART_NONCE = "hard_restart_nonce"
    const val KEY_HARD_RESTART_EXPECTED_PID =
        "hard_restart_expected_pid"
    const val KEY_HARD_RESTART_EXPECTED_GENERATION =
        "hard_restart_expected_generation"
    const val KEY_HARD_RESTART_EXPECTED_PERMIT_OWNER =
        "hard_restart_expected_permit_owner"
    const val KEY_HARD_RESTART_FIRST_SCHEDULED_ELAPSED =
        "hard_restart_first_scheduled_elapsed"
    const val KEY_HARD_RESTART_ATTEMPT_COUNT =
        "hard_restart_attempt_count"
    const val KEY_TARGET_UID_HEALTH_SNAPSHOT =
        "target_uid_health_snapshot"
    const val KEY_TARGET_UID_HEALTH_CAPTURED_WALL =
        "target_uid_health_captured_wall"
    const val KEY_TARGET_UID_HEALTH_CAPTURE_STARTED =
        "target_uid_health_capture_started"
    const val KEY_TARGET_UID_HEALTH_CAPTURE_FINISHED =
        "target_uid_health_capture_finished"
    const val KEY_TARGET_UID_HEALTH_IMPORTED_AT =
        "target_uid_health_imported_at"
    const val KEY_TARGET_UID_HEALTH_IMPORT_STATE =
        "target_uid_health_import_state"

    fun deviceProtected(context: Context): SharedPreferences {
        val storage = context.createDeviceProtectedStorageContext()
        return storage.getSharedPreferences(PREFERENCES_FILE_NAME, Context.MODE_PRIVATE)
    }

    fun initializeKeeperBoot(context: Context): Boolean {
        val currentBootId = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        }.getOrDefault("")
        if (currentBootId.isBlank()) return false
        val prefs = deviceProtected(context)
        if (prefs.getString(KEY_RUNTIME_BOOT_ID, "") == currentBootId) return true
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val paused = prefs.getBoolean(KEY_PAUSED, false)
        val editor = prefs.edit()
            .putString(KEY_RUNTIME_BOOT_ID, currentBootId)
            .putInt(KEY_PID, 0)
            .putBoolean(KEY_WAKE_LOCK, false)
            .putBoolean(KEY_CONTINUOUS_WAKE_LOCK, false)
            .putBoolean(KEY_WIFI_LOCK, false)
            .putBoolean(KEY_NOTIFICATION_LISTENER_CONNECTED, false)
            .putInt(KEY_NOTIFICATION_LISTENER_PID, 0)
            .putLong(KEY_NOTIFICATION_LISTENER_HEARTBEAT_ELAPSED, 0L)
            .putLong(KEY_NOTIFICATION_LISTENER_REBIND_LAST_REQUEST_ELAPSED, 0L)
            .putString(KEY_PERSISTENT_NETWORK_LEASE_STATE, "STOPPED")
            .putLong(KEY_PERSISTENT_NETWORK_LEASE_HANDLE, -1L)
            .putString(KEY_PERSISTENT_MTALK_SOCKET_STATE, "STOPPED")
            .putLong(KEY_PERSISTENT_MTALK_SOCKET_HANDLE, -1L)
            .putString(KEY_PERSISTENT_HEARTBEAT_SOCKET_STATE, "STOPPED")
            .putLong(KEY_PERSISTENT_HEARTBEAT_SOCKET_HANDLE, -1L)
            .putLong(
                KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_EVENT_ELAPSED,
                0L
            )
            .putString(
                KEY_PERSISTENT_HEARTBEAT_SOCKET_REASON,
                "boot_reset"
            )
            .putLong(
                KEY_PERSISTENT_HEARTBEAT_SOCKET_CONNECTION_STARTED_ELAPSED,
                0L
            )
            .putLong(
                KEY_PERSISTENT_HEARTBEAT_SOCKET_LAST_HEARTBEAT_ELAPSED,
                0L
            )
            .putInt(
                KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_CONNECT_COUNT,
                0
            )
            .putInt(
                KEY_PERSISTENT_HEARTBEAT_SOCKET_SESSION_HEARTBEAT_COUNT,
                0
            )
            .putInt(
                KEY_PERSISTENT_HEARTBEAT_SOCKET_CONSECUTIVE_FAILURES,
                0
            )
            .putLong(KEY_PERSISTENT_HEARTBEAT_SOCKET_BACKOFF_MS, 0L)
            .putString(
                KEY_GMS_BINDER_ANCHOR_STATE,
                if (prefs.getBoolean(KEY_GMS_BINDER_ANCHOR_ENABLED, false)) {
                    "WAITING_FOR_GUARDIAN"
                } else {
                    "DISABLED"
                }
            )
            .putInt(KEY_GMS_BINDER_ANCHOR_PID, 0)
            .putLong(KEY_GMS_BINDER_ANCHOR_CONNECTED_SINCE_ELAPSED, 0L)
            .putLong(KEY_GMS_BINDER_ANCHOR_LAST_EVENT_ELAPSED, 0L)
            .putInt(KEY_GMS_BINDER_ANCHOR_RECONNECT_ATTEMPT, 0)
            .putInt(KEY_GMS_BINDER_ANCHOR_SUSPENSION_CAUSE, 0)
            .putInt(KEY_GMS_BINDER_ANCHOR_FAILURE_CODE, 0)
            .putInt(KEY_GMS_BINDER_ANCHOR_GMS_VERSION, 0)
            .putLong(KEY_GMS_BINDER_ANCHOR_SESSION_GENERATION, 0L)
            .putString(KEY_GMS_BINDER_ANCHOR_BOOT_ID, currentBootId)
            .remove(KEY_GMS_PREVENTIVE_PULSE_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_GMS_PREVENTIVE_PULSE_COUNT)
            .remove(KEY_GMS_PREVENTIVE_PULSE_LAST_REASON)
            .putString(
                KEY_STATE,
                when {
                    !enabled -> "DISABLED"
                    paused -> "PAUSED"
                    else -> "STARTING"
                }
            )
            .remove(KEY_SERVICE_STARTED_ELAPSED)
            .remove(KEY_SERVICE_DESTROYED_ELAPSED)
            .remove(KEY_HEARTBEAT_ELAPSED)
            .remove(KEY_LAST_TICK_ELAPSED)
            .remove(KEY_LAST_TICK_UPTIME)
            .remove(KEY_MAX_TIMER_DRIFT)
            .remove(KEY_LAST_TIMER_DRIFT)
            .remove(KEY_LAST_ATTEMPT_RTT)
            .remove(KEY_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_LAST_ATTEMPT_NETWORK_HANDLE)
            .remove(KEY_LAST_ATTEMPT_SESSION_FINGERPRINT)
            .remove(KEY_LAST_SUCCESS_RTT)
            .remove(KEY_LAST_SUCCESS_ELAPSED)
            .remove(KEY_LAST_SUCCESS_NETWORK_HANDLE)
            .remove(KEY_LAST_SUCCESS_SESSION_FINGERPRINT)
            .remove(KEY_LAST_HTTP_CODE)
            .remove(KEY_CONSECUTIVE_FAILURES)
            .remove(KEY_HTTP_EVIDENCE_GENERATION)
            .remove(KEY_SUCCESS_EVIDENCE_GENERATION)
            .remove(KEY_ATTEMPT_EVIDENCE_GENERATION)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_LAST_START_REASON)
            .remove(KEY_VPN)
            .remove(KEY_VALIDATED)
            .remove(KEY_BYPASSABLE)
            .remove(KEY_BYPASSABLE_KNOWN)
            .remove(KEY_VPN_PROVIDER_PACKAGE)
            .remove(KEY_VPN_INTERNET_ROUTED)
            .remove(KEY_VPN_ROUTE_STATE)
            .remove(KEY_VPN_IPV4_DEFAULT_ROUTE)
            .remove(KEY_VPN_IPV6_DEFAULT_ROUTE)
            .remove(KEY_VPN_SESSION_COMPLETE)
            .remove(KEY_VPN_BLOCKED)
            .remove(KEY_VPN_BLOCKED_KNOWN)
            .remove(KEY_VPN_NOT_SUSPENDED)
            .remove(KEY_VPN_SESSION_FINGERPRINT)
            .remove(KEY_VPN_SESSION_GENERATION)
            .remove(KEY_VPN_INTERFACE_NAME)
            .remove(KEY_VPN_LINK_ADDRESSES)
            .remove(KEY_VPN_DNS_SERVERS)
            .remove(KEY_VPN_MTU)
            .remove(KEY_VPN_UNDERLYING_HANDLES)
            .remove(KEY_VPN_SESSION_HEALTH)
            .remove(KEY_VPN_DNS_HEALTH)
            .remove(KEY_VPN_HTTPS_HEALTH)
            .remove(KEY_FCM_HEALTH)
            .remove(KEY_WHATSAPP_DELIVERY_HEALTH)
            .remove(KEY_VPN_DNS_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_VPN_DNS_LAST_SUCCESS_ELAPSED)
            .remove(KEY_VPN_DNS_LAST_RTT)
            .remove(KEY_VPN_DNS_LAST_ERROR)
            .remove(KEY_VPN_DNS_FAILURES)
            .remove(KEY_VPN_DNS_EVIDENCE_SESSION_FINGERPRINT)
            .remove(KEY_MTALK_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_MTALK_LAST_SESSION_FINGERPRINT)
            .remove(KEY_MTALK_IPV4_DNS)
            .remove(KEY_MTALK_IPV6_DNS)
            .remove(KEY_MTALK_IPV4_TCP_5228)
            .remove(KEY_MTALK_IPV4_TCP_5229)
            .remove(KEY_MTALK_IPV4_TCP_5230)
            .remove(KEY_MTALK_IPV4_TCP_443)
            .remove(KEY_MTALK_IPV6_TCP_5228)
            .remove(KEY_MTALK_IPV6_TCP_5229)
            .remove(KEY_MTALK_IPV6_TCP_5230)
            .remove(KEY_MTALK_IPV6_TCP_443)
            .remove(KEY_MTALK_RESULT_SUMMARY)
            .remove(KEY_TAILSCALE_PRESENT)
            .remove(KEY_TAILSCALE_NETWORK_HANDLE)
            .remove(KEY_TAILSCALE_COMPLETE)
            .remove(KEY_TAILSCALE_VALIDATED)
            .remove(KEY_TAILSCALE_BLOCKED)
            .remove(KEY_TAILSCALE_BLOCKED_KNOWN)
            .remove(KEY_TAILSCALE_SUSPENDED)
            .remove(KEY_TAILSCALE_SELF_EXCLUDED)
            .remove(KEY_TAILSCALE_ROUTE_STATE)
            .remove(KEY_TAILSCALE_DNS_SERVERS)
            .remove(KEY_TAILSCALE_UNDERLYING_HANDLES)
            .remove(KEY_TAILSCALE_DNS_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_TAILSCALE_DNS_LAST_SUCCESS_ELAPSED)
            .remove(KEY_TAILSCALE_DNS_LAST_RTT)
            .remove(KEY_TAILSCALE_DNS_LAST_ERROR)
            .remove(KEY_TAILSCALE_DNS_FAILURES)
            .remove(KEY_TAILSCALE_DNS_EVIDENCE_GENERATION)
            .remove(KEY_TAILSCALE_DNS_EVIDENCE_NETWORK_HANDLE)
            .remove(KEY_NETWORK_HANDLE)
            .remove(KEY_HAS_EVER_OBSERVED_VPN)
            .remove(KEY_TRANSPORT)
            .remove(KEY_UNDERLAY_SOURCE)
            .remove(KEY_LAST_EXPLICIT_UNDERLAY)
            .remove(KEY_LAST_WIFI_SEEN_ELAPSED)
            .remove(KEY_UNDERLAY_UNKNOWN_SINCE)
            .remove(KEY_UNDERLAY_HISTORY_BOOT_ID)
            .remove(KEY_PROBE_IN_FLIGHT)
            .remove(KEY_PROBE_STARTED_ELAPSED)
            .remove(KEY_PROBE_DEADLINE_ELAPSED)
            .remove(KEY_LOCKDOWN)
            .remove(KEY_LOCKDOWN_KNOWN)
            .remove(KEY_ALWAYS_ON)
            .remove(KEY_ALWAYS_ON_KNOWN)
            .remove(KEY_ALARM_EXACT)
            .remove(KEY_ALARM_SCHEDULED_ELAPSED)
            .remove(KEY_LAST_BOOT_BROADCAST)
            .remove(KEY_BOOT_RECOVERY_CLAIM_BOOT_ID)
            .remove(KEY_BOOT_RECOVERY_CLAIM_ACTION)
            .remove(KEY_BOOT_RECOVERY_CLAIM_ELAPSED)
            .remove(KEY_BOOT_RECOVERY_DISPATCH_ACCEPTED)
            .remove(KEY_ADB_VERIFIED_WALL)
            .remove(KEY_ADB_VERIFIED_ELAPSED)
            .remove(KEY_ADB_VERIFIED_BOOT_ID)
            .remove(KEY_ADB_ACTIVE_VPN_PACKAGE)
            .remove(KEY_ADB_ALWAYS_ON)
            .remove(KEY_ADB_LOCKDOWN)
            .remove(KEY_ADB_BYPASSABLE)
            .remove(KEY_ADB_GMS_ROUTED)
            .remove(KEY_ADB_WHATSAPP_ROUTED)
            .remove(KEY_ADB_WHATSAPP_BUSINESS_ROUTED)
            .remove(KEY_ADB_INTERNET_ROUTED)
            .remove(KEY_ADB_EVIDENCE_HASH)
            .remove(KEY_ADB_NETWORK_HANDLE)
            .remove(KEY_ADB_SESSION_FINGERPRINT)
            .remove(KEY_TARGET_UID_HEALTH_SNAPSHOT)
            .remove(KEY_TARGET_UID_HEALTH_CAPTURED_WALL)
        if (enabled && !paused) {
            editor
                .putString(KEY_LAST_SERVICE_EXIT, "boot_cycle_changed")
                .putString(
                    KEY_RECOVERY_FAILURE_SERVICE,
                    "检测到新开机周期；尚无本次开机的守护恢复证据"
                )
                .putLong(
                    KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                    android.os.SystemClock.elapsedRealtime()
                )
        } else {
            editor
                .remove(KEY_RECOVERY_FAILURE_SERVICE)
                .remove(KEY_RECOVERY_FAILURE_SERVICE_ELAPSED)
        }
        return editor.commit()
    }

    @Synchronized
    fun initializeKeeperProcess(context: Context): Boolean {
        val currentPid = android.os.Process.myPid()
        if (initializedKeeperProcessPid == currentPid) return true
        val prefs = deviceProtected(context)
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val paused = prefs.getBoolean(KEY_PAUSED, false)
        val editor = prefs.edit()
            .putInt(KEY_KEEPER_PROCESS_PID, currentPid)
            .putInt(KEY_PID, 0)
            .putBoolean(KEY_WAKE_LOCK, false)
            .putBoolean(KEY_CONTINUOUS_WAKE_LOCK, false)
            .putBoolean(KEY_WIFI_LOCK, false)
            .putString(
                KEY_STATE,
                when {
                    !enabled -> "DISABLED"
                    paused -> "PAUSED"
                    else -> "RECOVERING"
                }
            )
            .remove(KEY_SERVICE_STARTED_ELAPSED)
            .remove(KEY_SERVICE_DESTROYED_ELAPSED)
            .remove(KEY_HEARTBEAT_ELAPSED)
            .remove(KEY_LAST_TICK_ELAPSED)
            .remove(KEY_LAST_TICK_UPTIME)
            .remove(KEY_MAX_TIMER_DRIFT)
            .remove(KEY_LAST_TIMER_DRIFT)
            .remove(KEY_LAST_ATTEMPT_RTT)
            .remove(KEY_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_LAST_ATTEMPT_NETWORK_HANDLE)
            .remove(KEY_LAST_ATTEMPT_SESSION_FINGERPRINT)
            .remove(KEY_LAST_SUCCESS_RTT)
            .remove(KEY_LAST_SUCCESS_ELAPSED)
            .remove(KEY_LAST_SUCCESS_NETWORK_HANDLE)
            .remove(KEY_LAST_SUCCESS_SESSION_FINGERPRINT)
            .remove(KEY_LAST_HTTP_CODE)
            .remove(KEY_CONSECUTIVE_FAILURES)
            .remove(KEY_HTTP_EVIDENCE_GENERATION)
            .remove(KEY_SUCCESS_EVIDENCE_GENERATION)
            .remove(KEY_ATTEMPT_EVIDENCE_GENERATION)
            .remove(KEY_LAST_ERROR)
            .remove(KEY_VPN)
            .remove(KEY_VALIDATED)
            .remove(KEY_BYPASSABLE)
            .remove(KEY_BYPASSABLE_KNOWN)
            .remove(KEY_VPN_PROVIDER_PACKAGE)
            .remove(KEY_VPN_INTERNET_ROUTED)
            .remove(KEY_VPN_ROUTE_STATE)
            .remove(KEY_VPN_IPV4_DEFAULT_ROUTE)
            .remove(KEY_VPN_IPV6_DEFAULT_ROUTE)
            .remove(KEY_VPN_SESSION_COMPLETE)
            .remove(KEY_VPN_BLOCKED)
            .remove(KEY_VPN_BLOCKED_KNOWN)
            .remove(KEY_VPN_NOT_SUSPENDED)
            .remove(KEY_VPN_INTERFACE_NAME)
            .remove(KEY_VPN_LINK_ADDRESSES)
            .remove(KEY_VPN_DNS_SERVERS)
            .remove(KEY_VPN_MTU)
            .remove(KEY_VPN_UNDERLYING_HANDLES)
            .remove(KEY_VPN_SESSION_HEALTH)
            .remove(KEY_VPN_DNS_HEALTH)
            .remove(KEY_VPN_HTTPS_HEALTH)
            .remove(KEY_FCM_HEALTH)
            .remove(KEY_WHATSAPP_DELIVERY_HEALTH)
            .remove(KEY_VPN_DNS_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_VPN_DNS_LAST_SUCCESS_ELAPSED)
            .remove(KEY_VPN_DNS_LAST_RTT)
            .remove(KEY_VPN_DNS_LAST_ERROR)
            .remove(KEY_VPN_DNS_FAILURES)
            .remove(KEY_VPN_DNS_EVIDENCE_SESSION_FINGERPRINT)
            .remove(KEY_MTALK_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_MTALK_LAST_SESSION_FINGERPRINT)
            .remove(KEY_MTALK_IPV4_DNS)
            .remove(KEY_MTALK_IPV6_DNS)
            .remove(KEY_MTALK_IPV4_TCP_5228)
            .remove(KEY_MTALK_IPV4_TCP_5229)
            .remove(KEY_MTALK_IPV4_TCP_5230)
            .remove(KEY_MTALK_IPV4_TCP_443)
            .remove(KEY_MTALK_IPV6_TCP_5228)
            .remove(KEY_MTALK_IPV6_TCP_5229)
            .remove(KEY_MTALK_IPV6_TCP_5230)
            .remove(KEY_MTALK_IPV6_TCP_443)
            .remove(KEY_MTALK_RESULT_SUMMARY)
            .remove(KEY_TAILSCALE_PRESENT)
            .remove(KEY_TAILSCALE_NETWORK_HANDLE)
            .remove(KEY_TAILSCALE_COMPLETE)
            .remove(KEY_TAILSCALE_VALIDATED)
            .remove(KEY_TAILSCALE_BLOCKED)
            .remove(KEY_TAILSCALE_BLOCKED_KNOWN)
            .remove(KEY_TAILSCALE_SUSPENDED)
            .remove(KEY_TAILSCALE_SELF_EXCLUDED)
            .remove(KEY_TAILSCALE_ROUTE_STATE)
            .remove(KEY_TAILSCALE_DNS_SERVERS)
            .remove(KEY_TAILSCALE_UNDERLYING_HANDLES)
            .remove(KEY_TAILSCALE_DNS_LAST_ATTEMPT_ELAPSED)
            .remove(KEY_TAILSCALE_DNS_LAST_SUCCESS_ELAPSED)
            .remove(KEY_TAILSCALE_DNS_LAST_RTT)
            .remove(KEY_TAILSCALE_DNS_LAST_ERROR)
            .remove(KEY_TAILSCALE_DNS_FAILURES)
            .remove(KEY_TAILSCALE_DNS_EVIDENCE_GENERATION)
            .remove(KEY_TAILSCALE_DNS_EVIDENCE_NETWORK_HANDLE)
            .remove(KEY_NETWORK_HANDLE)
            .remove(KEY_TRANSPORT)
            .remove(KEY_UNDERLAY_SOURCE)
            .remove(KEY_PROBE_IN_FLIGHT)
            .remove(KEY_PROBE_STARTED_ELAPSED)
            .remove(KEY_PROBE_DEADLINE_ELAPSED)
            .remove(KEY_LOCKDOWN)
            .remove(KEY_LOCKDOWN_KNOWN)
            .remove(KEY_ALWAYS_ON)
            .remove(KEY_ALWAYS_ON_KNOWN)
        if (enabled && !paused) {
            val recoveryRequestedElapsed = android.os.SystemClock.elapsedRealtime()
            editor
                .putString(
                    KEY_RECOVERY_FAILURE_SERVICE,
                    "Keeper 进程已重建；等待新的服务心跳与网络证据"
                )
                .putLong(
                    KEY_RECOVERY_FAILURE_SERVICE_ELAPSED,
                    recoveryRequestedElapsed
                )
                .putBoolean(KEY_RECOVERY_CONFIRMATION_PENDING, true)
                .putLong(KEY_RECOVERY_REQUESTED_ELAPSED, recoveryRequestedElapsed)
        } else {
            editor
                .putBoolean(KEY_RECOVERY_CONFIRMATION_PENDING, false)
                .remove(KEY_RECOVERY_REQUESTED_ELAPSED)
        }
        val committed = editor.commit()
        if (committed) initializedKeeperProcessPid = currentPid
        return committed
    }
}
