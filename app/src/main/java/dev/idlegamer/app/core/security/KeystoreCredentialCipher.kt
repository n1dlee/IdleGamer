package dev.idlegamer.app.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key.
 *
 * * `setRandomizedEncryptionRequired(true)`: Keystore refuses caller-supplied IVs for encryption, so a
 *   nonce can never be reused by mistake; the IV is read back from the cipher.
 * * No user-authentication requirement: the background service must be able to restore the session
 *   without the user present. UI lock is a separate mechanism (SECURITY_DESIGN.md §5).
 * * StrongBox is preferred when the device has it.
 */
class KeystoreCredentialCipher(
    override val keyAlias: String = DEFAULT_ALIAS,
) : CredentialCipher {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) } }

    @Synchronized
    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedPayload = guard {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)
        EncryptedPayload(iv = cipher.iv, ciphertext = ciphertext)
    }

    @Synchronized
    override fun decrypt(payload: EncryptedPayload, aad: ByteArray): ByteArray = guard {
        val key = existingKey() ?: throw CipherFailure.KeyUnavailable()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, payload.iv))
        cipher.updateAAD(aad)
        cipher.doFinal(payload.ciphertext)
    }

    @Synchronized
    override fun deleteKey() {
        runCatching { if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias) }
    }

    private fun existingKey(): SecretKey? =
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun getOrCreateKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateKey(strongBox = true)
            } catch (_: StrongBoxUnavailableException) {
                // Fall through to TEE-backed key.
            }
        }
        return generateKey(strongBox = false)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: CipherFailure) {
        throw e
    } catch (e: AEADBadTagException) {
        throw CipherFailure.Corrupted(e)
    } catch (e: KeyPermanentlyInvalidatedException) {
        throw CipherFailure.KeyUnavailable(e)
    } catch (e: UnrecoverableKeyException) {
        throw CipherFailure.KeyUnavailable(e)
    } catch (e: InvalidKeyException) {
        throw CipherFailure.KeyUnavailable(e)
    } catch (e: KeyStoreException) {
        throw CipherFailure.KeyUnavailable(e)
    } catch (e: GeneralSecurityException) {
        throw CipherFailure.Corrupted(e)
    } catch (e: IllegalArgumentException) {
        throw CipherFailure.Corrupted(e)
    }

    companion object {
        const val DEFAULT_ALIAS = "idlegamer.vault.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
    }
}
