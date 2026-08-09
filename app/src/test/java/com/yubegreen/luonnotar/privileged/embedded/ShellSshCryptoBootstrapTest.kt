package com.yubegreen.luonnotar.privileged.embedded

import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSshCryptoBootstrapTest {
    @Test
    fun bundledProviderResolvesAllSshNistAliasesBeforeMinaInitialization() {
        val provider = ShellSshCryptoBootstrap.installAndVerify()
        assertTrue(provider.startsWith("BC:"))
    }
}
