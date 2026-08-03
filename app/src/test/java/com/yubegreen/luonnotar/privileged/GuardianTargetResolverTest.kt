package com.yubegreen.luonnotar.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianTargetResolverTest {
    private val packages = GuardianEngineConfig.DEFAULT_PACKAGE_TARGETS

    @Test fun mapsSecondaryProcessesToOwningPackage() {
        assertEquals(
            "com.google.android.gms",
            GuardianTargetResolver.ownerPackage("com.google.android.gms.persistent", packages)
        )
        assertEquals(
            "com.whatsapp",
            GuardianTargetResolver.ownerPackage("com.whatsapp:account_switching", packages)
        )
    }

    @Test fun onlyMainProcessCanUseActivityManagerUnfreeze() {
        assertTrue(
            GuardianTargetResolver.canUseActivityManagerUnfreeze(
                "com.google.android.gms",
                "com.google.android.gms"
            )
        )
        assertFalse(
            GuardianTargetResolver.canUseActivityManagerUnfreeze(
                "com.google.android.gms.persistent",
                "com.google.android.gms"
            )
        )
        assertFalse(
            GuardianTargetResolver.canUseActivityManagerUnfreeze(
                "com.whatsapp:account_switching",
                "com.whatsapp"
            )
        )
    }
}
