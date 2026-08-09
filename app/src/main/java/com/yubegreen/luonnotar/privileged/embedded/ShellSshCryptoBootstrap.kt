package com.yubegreen.luonnotar.privileged.embedded

import org.apache.sshd.common.cipher.ECCurves
import java.security.AlgorithmParameters
import java.security.KeyPairGenerator
import java.security.Security
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/**
 * Installs a process-local EC compatibility shim before Apache MINA SSHD
 * initializes its static ECCurve catalogue.
 *
 * The Android shell runtime needs SSH NIST aliases translated on every JCA
 * surface MINA may use. We therefore verify AlgorithmParameters.EC,
 * KeyPairGenerator.EC, and finally force-load MINA's own ECCurves catalogue.
 * A bootstrap that cannot survive the exact MINA static initializer is rejected
 * before SshServer.setUpDefaultServer() is allowed to run.
 */
internal object ShellSshCryptoBootstrap {
    private const val PROVIDER_NAME = ShellSshNistEcAlgorithmParametersProvider.NAME
    private val requiredCurves = listOf(
        "nistp256" to 256,
        "nistp384" to 384,
        "nistp521" to 521
    )

    fun installAndVerify(): String {
        // Android ships its own provider named BC. Apache MINA can resolve BC
        // explicitly, so a differently named provider is insufficient even if
        // AlgorithmParameters.getInstance("EC") probes pass. Replace BC only
        // inside this isolated SSH daemon JVM with Luonnotar's bundled BC clone
        // plus the SSH NIST alias translation layer.
        Security.removeProvider(PROVIDER_NAME)
        val provider = ShellSshNistEcAlgorithmParametersProvider()
        check(Security.insertProviderAt(provider, 1) == 1) {
            "unable to install Luonnotar patched BC provider as primary security provider"
        }

        val active = Security.getProvider(PROVIDER_NAME)
            ?: error("Luonnotar patched BC provider missing after installation")
        check(active === provider) {
            "unexpected patched BC provider instance: ${active.javaClass.name}"
        }

        requiredCurves.forEach { (name, expectedBits) ->
            verifyAlgorithmParametersCurve(name, expectedBits)
            verifyKeyPairGeneratorCurve(name, expectedBits)
        }

        val firstParametersProvider = Security.getProviders().firstOrNull { candidate ->
            candidate.getService("AlgorithmParameters", "EC") != null
        }
        check(firstParametersProvider?.name == PROVIDER_NAME) {
            "Luonnotar SSH EC shim is not the primary EC AlgorithmParameters provider: " +
                firstParametersProvider?.name.orEmpty()
        }

        val firstGeneratorProvider = Security.getProviders().firstOrNull { candidate ->
            candidate.getService("KeyPairGenerator", "EC") != null
        }
        check(firstGeneratorProvider?.name == PROVIDER_NAME) {
            "Luonnotar SSH EC shim is not the primary EC KeyPairGenerator provider: " +
                firstGeneratorProvider?.name.orEmpty()
        }

        // This is the regression guard the old bootstrap lacked. Loading
        // ECCurves here exercises MINA's real static catalogue construction,
        // which is exactly where the OriginOS shell process failed for nistp384.
        verifyMinaCurveCatalogue()

        return "$PROVIDER_NAME:${active.javaClass.name}"
    }

    private fun verifyAlgorithmParametersCurve(name: String, expectedBits: Int) {
        val parameters = AlgorithmParameters.getInstance("EC")
        parameters.init(ECGenParameterSpec(name))
        val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
        check(spec.curve.field.fieldSize == expectedBits) {
            "invalid EC AlgorithmParameters for $name: expected=$expectedBits actual=${spec.curve.field.fieldSize}"
        }
    }

    private fun verifyKeyPairGeneratorCurve(name: String, expectedBits: Int) {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(name))
        val publicKey = generator.generateKeyPair().public as? ECPublicKey
            ?: error("EC KeyPairGenerator returned non-EC public key for $name")
        check(publicKey.params.curve.field.fieldSize == expectedBits) {
            "invalid EC KeyPairGenerator parameters for $name: expected=$expectedBits " +
                "actual=${publicKey.params.curve.field.fieldSize}"
        }
    }

    private fun verifyMinaCurveCatalogue() {
        requiredCurves.forEach { (name, expectedBits) ->
            val curve = ECCurves.fromCurveName(name)
                ?: error("Apache MINA ECCurves missing $name")
            val actualBits = curve.parameters.curve.field.fieldSize
            check(actualBits == expectedBits) {
                "Apache MINA ECCurves invalid $name: expected=$expectedBits actual=$actualBits"
            }
            check(curve.keySize == expectedBits) {
                "Apache MINA ECCurves key size invalid $name: expected=$expectedBits actual=${curve.keySize}"
            }
        }
    }
}
