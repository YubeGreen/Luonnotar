package com.yubegreen.luonnotar.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetRoutingPolicyTest {
    @Test fun unmonitoredBusinessDoesNotBlock() = assertTrue(
        TargetRoutingPolicy.isVerified(TargetRoutingSnapshot(false, true, false))
    )
    @Test fun disabledBusinessDoesNotBlock() = assertTrue(
        TargetRoutingPolicy.isVerified(TargetRoutingSnapshot(true, false, false))
    )
    @Test fun activeUnroutedTargetFails() = assertFalse(
        TargetRoutingPolicy.isVerified(TargetRoutingSnapshot(true, true, false))
    )
    @Test fun activeRoutedTargetPasses() = assertTrue(
        TargetRoutingPolicy.isVerified(TargetRoutingSnapshot(true, true, true))
    )
}
