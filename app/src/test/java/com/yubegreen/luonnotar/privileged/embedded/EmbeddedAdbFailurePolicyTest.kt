package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedAdbFailurePolicyTest {
    @Test fun recognisesStrongAuthenticationEvidence() {
        assertTrue(EmbeddedAdbFailurePolicy.isAuthorizationFailure("ADB authentication failed"))
        assertTrue(EmbeddedAdbFailurePolicy.isAuthorizationFailure("TLS alert: certificate rejected"))
    }

    @Test fun transientConnectivityFailureDoesNotErasePairing() {
        assertFalse(EmbeddedAdbFailurePolicy.isAuthorizationFailure("Connection refused"))
        assertFalse(EmbeddedAdbFailurePolicy.isAuthorizationFailure("Connection reset by peer"))
        assertFalse(EmbeddedAdbFailurePolicy.isAuthorizationFailure("Timed out"))
    }

    @Test fun recognisesStaleOrUnreachableAdbEndpoints() {
        assertTrue(EmbeddedAdbFailurePolicy.isEndpointUnavailable("connect failed: ECONNREFUSED"))
        assertTrue(EmbeddedAdbFailurePolicy.isEndpointUnavailable("No route to host"))
        assertTrue(EmbeddedAdbFailurePolicy.isEndpointUnavailable("Connection reset by peer"))
        assertFalse(EmbeddedAdbFailurePolicy.isEndpointUnavailable("starter exit=1: ClassNotFound"))
    }
}
