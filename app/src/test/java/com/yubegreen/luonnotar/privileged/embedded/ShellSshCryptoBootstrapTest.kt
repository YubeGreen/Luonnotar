package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.AlgorithmParameters
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

class ShellSshCryptoBootstrapTest {
    @Test
    fun processLocalShimResolvesAllMinaSshNistAliasesBeforeMinaInitialization() {
        val provider = ShellSshCryptoBootstrap.installAndVerify()
        assertTrue(provider.startsWith("LuonnotarSSH-EC:"))
        assertEquals("LuonnotarSSH-EC", Security.getProviders().first {
            it.getService("AlgorithmParameters", "EC") != null
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
        }
    }
}
