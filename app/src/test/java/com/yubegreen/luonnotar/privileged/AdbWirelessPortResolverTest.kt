package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbWirelessPortResolverTest {
    @Test
    fun parsesSdk36WifiSupportParcel() {
        assertTrue(
            AdbWirelessPortResolver.parseBooleanParcel(
                "Result: Parcel( 00000000 00000001 '........')"
            ) == true
        )
        assertFalse(
            AdbWirelessPortResolver.parseBooleanParcel(
                "Result: Parcel( 00000000 00000000 '........')"
            ) == true
        )
        assertNull(
            AdbWirelessPortResolver.parseBooleanParcel(
                "Result: Parcel( ffffffff 00000001 '........')"
            )
        )
    }

    @Test
    fun parsesLiveWirelessAdbPortParcel() {
        assertEquals(
            42949,
            AdbWirelessPortResolver.parsePortParcel(
                "Result: Parcel( 00000000 0000a7c5 '........')"
            )
        )
        assertEquals(
            33609,
            AdbWirelessPortResolver.parsePortParcel(
                "Result: Parcel( 00000000 00008349 '....I...')"
            )
        )
    }

    @Test
    fun rejectsInvalidAndFixed5555Ports() {
        assertNull(AdbWirelessPortResolver.parsePortParcel("Result: Parcel( 00000000 000015b3 )"))
        assertNull(AdbWirelessPortResolver.parsePortParcel("Result: Parcel( 00000000 00000000 )"))
        assertNull(AdbWirelessPortResolver.parsePortParcel("garbage"))
    }
}
