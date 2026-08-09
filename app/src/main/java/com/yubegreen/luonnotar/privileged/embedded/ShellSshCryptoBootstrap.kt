package com.yubegreen.luonnotar.privileged.embedded

import java.security.AlgorithmParameters
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/**
 * Installs a process-local EC AlgorithmParameters compatibility shim before
 * Apache MINA SSHD initializes its static ECCurve catalogue.
 *
 * MINA 2.19 asks JCA for SSH curve aliases such as nistp384. The Android shell
 * runtime observed on the target device does not resolve those aliases. The
 * shim translates only nistp256/nistp384/nistp521 to their SEC equivalents and
 * delegates the actual EC work to Luonnotar's bundled Bouncy Castle provider.
 * Other algorithms/providers are left untouched.
 */
internal object ShellSshCryptoBootstrap {
    private const val PROVIDER_NAME = ShellSshNistEcAlgorithmParametersProvider.NAME
    private val requiredCurves = listOf(
        "nistp256" to 256,
        "nistp384" to 384,
        "nistp521" to 521
    )

    fun installAndVerify(): String {
        Security.removeProvider(PROVIDER_NAME)
        val provider = ShellSshNistEcAlgorithmParametersProvider()
        check(Security.insertProviderAt(provider, 1) == 1) {
            "unable to install Luonnotar SSH EC alias provider as primary security provider"
        }

        val active = Security.getProvider(PROVIDER_NAME)
            ?: error("Luonnotar SSH EC alias provider missing after installation")
        check(active === provider) {
            "unexpected SSH EC provider instance: ${active.javaClass.name}"
        }

        // Verify through the provider-free JCA lookup. This mirrors MINA's
        // initialization path and proves our priority-1 shim is actually used.
        requiredCurves.forEach { (name, expectedBits) ->
            verifyCurve(name, expectedBits)
        }

        val firstEcProvider = Security.getProviders().firstOrNull { candidate ->
            candidate.getService("AlgorithmParameters", "EC") != null
        }
        check(firstEcProvider?.name == PROVIDER_NAME) {
            "Luonnotar SSH EC shim is not the primary EC AlgorithmParameters provider: " +
                firstEcProvider?.name.orEmpty()
        }

        return "$PROVIDER_NAME:${active.javaClass.name}"
    }

    private fun verifyCurve(name: String, expectedBits: Int) {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec(name))
        val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
        check(spec.curve.field.fieldSize == expectedBits) {
            "invalid EC parameters for $name: expected=$expectedBits actual=${spec.curve.field.fieldSize}"
        }
    }
}
