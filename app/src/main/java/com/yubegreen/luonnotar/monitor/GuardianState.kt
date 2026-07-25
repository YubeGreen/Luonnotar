package com.yubegreen.luonnotar.monitor

enum class GuardianState {
    DISABLED,
    STARTING,
    RECOVERING,
    PAUSED,
    VPN_UNVALIDATED,
    NO_SUCCESS_EVIDENCE,
    WAITING_FOR_VPN,
    VPN_PATH_HEALTHY,
    TARGET_ROUTING_UNVERIFIED,
    VPN_BYPASSABLE,
    LOCKDOWN_UNVERIFIED,
    KEEPALIVE_DEGRADED,
    VPN_LOST,
    OEM_RESTRICTED,
    SERVICE_RECOVERED,
    FATAL
}

object GuardianStateReducer {
    fun reduce(
        enabled: Boolean,
        paused: Boolean,
        vpn: Boolean,
        validated: Boolean,
        bypassable: Boolean?,
        lastHttpCode: Int,
        failures: Int,
        hasAnySuccess: Boolean,
        hasRecentSuccess: Boolean,
        targetRoutingVerified: Boolean,
        hasEverObservedVpn: Boolean
    ): GuardianState = when {
        !enabled -> GuardianState.DISABLED
        paused -> GuardianState.PAUSED
        !vpn && !hasEverObservedVpn -> GuardianState.WAITING_FOR_VPN
        !vpn -> GuardianState.VPN_LOST
        !validated -> GuardianState.VPN_UNVALIDATED
        bypassable == true -> GuardianState.VPN_BYPASSABLE
        !hasAnySuccess -> GuardianState.NO_SUCCESS_EVIDENCE
        lastHttpCode != 204 || failures != 0 || !hasRecentSuccess ->
            GuardianState.KEEPALIVE_DEGRADED
        bypassable == null -> GuardianState.LOCKDOWN_UNVERIFIED
        !targetRoutingVerified -> GuardianState.TARGET_ROUTING_UNVERIFIED
        else -> GuardianState.VPN_PATH_HEALTHY
    }
}

object VpnOnlyRoutingPolicy {
    fun maySendHttps(defaultNetworkIsVpn: Boolean, paused: Boolean): Boolean =
        defaultNetworkIsVpn && !paused
}
