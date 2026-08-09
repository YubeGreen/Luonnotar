package com.yubegreen.luonnotar.privileged.embedded

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.AlgorithmParameters
import java.security.Provider
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/**
 * Installs a process-local crypto provider before Apache MINA SSHD initializes
 * its static ECCurve catalogue.
 *
 * Android's platform EC implementation on the target shell runtime does not
 * resolve MINA's SSH curve aliases (for example nistp384). MINA 2.19.0 then
 * aborts in ECCurves.<clinit>. A bundled Bouncy Castle provider is installed
 * at priority 1 in this isolated SSH process and the exact aliases used by SSH
 * are verified before any SshServer class is initialized.
 */
internal object ShellSshCryptoBootstrap {
    private const val PROVIDER_NAME = "BC"
    private val requiredCurves = listOf("nistp256", "nistp384", "nistp521")

    fun installAndVerify(): String {
        val provider = BouncyCastleProvider()

        // Android may expose a platform provider named BC. Replace it only in
        // this stand-alone app_process so Apache MINA deterministically sees
        // the bundled implementation selected for Luonnotar SSH.
        Security.removeProvider(PROVIDER_NAME)
        check(Security.insertProviderAt(provider, 1) == 1) {
            "unable to install Bouncy Castle as primary security provider"
        }

        val active = Security.getProvider(PROVIDER_NAME)
            ?: error("Bouncy Castle provider missing after installation")
        check(active === provider) {
            "unexpected BC provider instance: ${active.javaClass.name}"
        }

        requiredCurves.forEach { verifyCurve(active, it) }

        val firstEcProvider = Security.getProviders().firstOrNull { candidate ->
            candidate.getService("AlgorithmParameters", "EC") != null
        }
        check(firstEcProvider?.name == PROVIDER_NAME) {
            "BC is not the primary EC AlgorithmParameters provider: ${firstEcProvider?.name.orEmpty()}"
        }

        return "$PROVIDER_NAME:${active.javaClass.name}"
    }

    private fun verifyCurve(provider: Provider, name: String) {
        val parameters = AlgorithmParameters.getInstance("EC", provider)
        parameters.init(ECGenParameterSpec(name))
        val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
        check(spec.curve.field.fieldSize > 0) { "invalid EC parameters for $name" }
    }
}
