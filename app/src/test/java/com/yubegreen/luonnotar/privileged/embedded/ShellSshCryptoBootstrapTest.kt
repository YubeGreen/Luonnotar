package com.yubegreen.luonnotar.privileged.embedded

import org.apache.sshd.common.cipher.ECCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.Security
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

class ShellSshCryptoBootstrapTest {
    @Test
    fun processLocalShimSurvivesMinaEccurveInitializationForAllSshNistAliases() {
        val provider = ShellSshCryptoBootstrap.installAndVerify()
        assertTrue(provider.startsWith("BC:"))
        assertEquals("BC", Security.getProviders().first {
            it.getService("AlgorithmParameters", "EC") != null
        }.name)
        assertEquals("BC", Security.getProviders().first {
            it.getService("KeyPairGenerator", "EC") != null
        }.name)

        mapOf(
            "nistp256" to 256,
            "nistp384" to 384,
            "nistp521" to 521
        ).forEach { (name, bits) ->
            val parameters = AlgorithmParameters.getInstance("EC")
            parameters.init(ECGenParameterSpec(name))
            val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
            assertEquals(bits, spec.curve.field.fieldSize)

            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(name))
            val publicKey = generator.generateKeyPair().public as ECPublicKey
            assertEquals(bits, publicKey.params.curve.field.fieldSize)

            val curve = ECCurves.fromCurveName(name)
            assertEquals(bits, curve.parameters.curve.field.fieldSize)
            assertEquals(bits, curve.keySize)
        }
    }
}
