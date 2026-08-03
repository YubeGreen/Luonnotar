package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorWifiTransportPolicyTest {
    @Test
    fun vivoExtWifiIsTreatedAsWifi() {
        assertTrue(
            VendorWifiTransportPolicy.isWifi(
                capabilitiesSummary =
                    "[ Transports: VPN|EXTWIFI Capabilities: INTERNET ]",
                interfaceName = null
            )
        )
    }

    @Test
    fun wlanInterfaceIsASecondaryWifiFallback() {
        assertTrue(
            VendorWifiTransportPolicy.isWifi(
                capabilitiesSummary = "[ Transports: VPN ]",
                interfaceName = "wlan0"
            )
        )
    }

    @Test
    fun cellularDoesNotBecomeWifi() {
        assertFalse(
            VendorWifiTransportPolicy.isWifi(
                capabilitiesSummary = "[ Transports: CELLULAR ]",
                interfaceName = "rmnet_data0"
            )
        )
    }
}
