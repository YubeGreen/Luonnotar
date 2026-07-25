package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnderlayTransportPolicyTest {
    @Test
    fun wifiWinsRegardlessOfUnderlyingNetworkOrder() {
        assertEquals(
            "WIFI",
            UnderlayTransportPolicy.preferred(
                linkedSetOf("CELLULAR", "WIFI")
            )
        )
        assertEquals(
            "WIFI",
            UnderlayTransportPolicy.preferred(
                linkedSetOf("WIFI", "CELLULAR")
            )
        )
    }

    @Test
    fun deterministicFallbackPrefersEthernetThenCellular() {
        assertEquals(
            "ETHERNET",
            UnderlayTransportPolicy.preferred(
                setOf("CELLULAR", "ETHERNET")
            )
        )
        assertEquals(
            "CELLULAR",
            UnderlayTransportPolicy.preferred(setOf("CELLULAR"))
        )
        assertNull(UnderlayTransportPolicy.preferred(emptySet()))
    }
}
