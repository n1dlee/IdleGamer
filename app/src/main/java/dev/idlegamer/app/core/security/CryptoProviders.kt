package dev.idlegamer.app.core.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * JavaSteam's `CryptoHelper` resolves algorithms with the provider name "BC" and expects the full
 * BouncyCastle provider. Android ships a stripped, deprecated provider under the same name, so
 * JavaSteam's own `insertProviderAt` silently fails and its calls would hit the stripped one.
 *
 * Replacing it *before* any JavaSteam class is loaded fixes that. The provider is appended at the end
 * of the list so default algorithm selection (Conscrypt / AndroidKeyStore for the vault) is unchanged.
 */
object CryptoProviders {
    @Volatile
    private var installed = false

    @Synchronized
    fun installBouncyCastle() {
        if (installed) return
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing !is BouncyCastleProvider) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
        installed = true
    }
}
