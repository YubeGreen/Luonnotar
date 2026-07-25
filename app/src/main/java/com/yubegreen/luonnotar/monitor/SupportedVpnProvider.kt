package com.yubegreen.luonnotar.monitor

enum class SupportedVpnProvider(
    val packageName: String,
    val displayName: String
) {
    PROTON("ch.protonvpn.android", "Proton VPN"),
    TAILSCALE("com.tailscale.ipn", "Tailscale");

    companion object {
        fun fromPackage(packageName: String?): SupportedVpnProvider? =
            entries.firstOrNull { it.packageName == packageName }

        fun isSupported(packageName: String?): Boolean =
            fromPackage(packageName) != null
    }
}
