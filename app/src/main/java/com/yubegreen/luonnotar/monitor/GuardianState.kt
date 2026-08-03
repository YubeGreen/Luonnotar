package com.yubegreen.luonnotar.monitor

enum class GuardianState {
    DISABLED,
    STARTING,
    RECOVERING,
    PAUSED,
    VPN_UNVALIDATED,
    VPN_BLOCKED,
    VPN_SUSPENDED,
    VPN_SESSION_INCOMPLETE,
    VPN_DNS_STALLED,
    VPN_HTTPS_STALLED,
    NO_SUCCESS_EVIDENCE,
    WAITING_FOR_VPN,
    VPN_PATH_HEALTHY,
    TARGET_ROUTING_UNVERIFIED,
    TAILSCALE_BLOCKED,
    TAILSCALE_SUSPENDED,
    TAILSCALE_SELF_EXCLUDED,
    TAILSCALE_ROUTE_INCOMPLETE,
    TAILSCALE_DNS_STALLED,
    TAILSCALE_EXIT_PATH_STALLED,
    TAILSCALE_PATH_HEALTHY_GMS_UNKNOWN,
    FCM_CANARY_DELAYED,
    VPN_BYPASSABLE,
    LOCKDOWN_UNVERIFIED,
    KEEPALIVE_DEGRADED,
    VPN_LOST,
    OEM_RESTRICTED,
    SERVICE_RECOVERED,
    FATAL
}

object TailscaleGuardianStatePolicy {
    fun overlay(
        base: GuardianState,
        tailscaleActive: Boolean,
        tailscalePresent: Boolean,
        complete: Boolean,
        blocked: Boolean,
        suspended: Boolean,
        routeState: VpnRouteState,
        dnsEvidenceCurrent: Boolean,
        dnsFailures: Int
    ): GuardianState = when {
        base == GuardianState.DISABLED || base == GuardianState.PAUSED -> base
        tailscalePresent && !tailscaleActive ->
            GuardianState.TAILSCALE_SELF_EXCLUDED
        !tailscaleActive -> base
        blocked || base == GuardianState.VPN_BLOCKED ->
            GuardianState.TAILSCALE_BLOCKED
        suspended || base == GuardianState.VPN_SUSPENDED ->
            GuardianState.TAILSCALE_SUSPENDED
        !complete || routeState != VpnRouteState.ROUTED ->
            GuardianState.TAILSCALE_ROUTE_INCOMPLETE
        base == GuardianState.VPN_SESSION_INCOMPLETE ->
            GuardianState.TAILSCALE_ROUTE_INCOMPLETE
        (dnsEvidenceCurrent && dnsFailures >= 2) ||
            base == GuardianState.VPN_DNS_STALLED ->
            GuardianState.TAILSCALE_DNS_STALLED
        base == GuardianState.KEEPALIVE_DEGRADED ||
            base == GuardianState.VPN_HTTPS_STALLED ->
            GuardianState.TAILSCALE_EXIT_PATH_STALLED
        base == GuardianState.TARGET_ROUTING_UNVERIFIED -> base
        base == GuardianState.VPN_PATH_HEALTHY ->
            GuardianState.TAILSCALE_PATH_HEALTHY_GMS_UNKNOWN
        else -> base
    }
}

object VpnGuardianStatePolicy {
    fun overlay(
        base: GuardianState,
        vpnPresent: Boolean,
        complete: Boolean,
        blocked: Boolean,
        notSuspended: Boolean,
        routeState: VpnRouteState,
        dnsEvidenceCurrent: Boolean,
        dnsFailures: Int
    ): GuardianState = when {
        base == GuardianState.DISABLED || base == GuardianState.PAUSED -> base
        !vpnPresent -> base
        blocked -> GuardianState.VPN_BLOCKED
        !notSuspended -> GuardianState.VPN_SUSPENDED
        !complete || routeState != VpnRouteState.ROUTED ->
            GuardianState.VPN_SESSION_INCOMPLETE
        dnsEvidenceCurrent && dnsFailures >= 2 ->
            GuardianState.VPN_DNS_STALLED
        base == GuardianState.KEEPALIVE_DEGRADED ->
            GuardianState.VPN_HTTPS_STALLED
        else -> base
    }
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
