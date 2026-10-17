package dev.idlegamer.app.core.security

/**
 * Authenticated encryption primitive behind the vault. The production implementation is
 * [KeystoreCredentialCipher]; tests use a software AES-GCM implementation with the same contract.
 *
 * Contract:
 *  * [encrypt] always uses a fresh random IV chosen by the cipher, never by the caller (no nonce reuse).
 *  * [decrypt] verifies the GCM tag over ciphertext **and** [aad]; any mismatch throws [CipherFailure.Corrupted].
 */
interface CredentialCipher {
    val keyAlias: String

    fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedPayload

    fun decrypt(payload: EncryptedPayload, aad: ByteArray): ByteArray

    /** Destroys the key. Everything encrypted with it becomes permanently unreadable. */
    fun deleteKey()
}

class EncryptedPayload(val iv: ByteArray, val ciphertext: ByteArray)

sealed class CipherFailure(message: String, cause: Throwable?) : Exception(message, cause) {
    class Corrupted(cause: Throwable? = null) : CipherFailure("Ciphertext failed authentication", cause)
    class KeyUnavailable(cause: Throwable? = null) : CipherFailure("Encryption key unavailable", cause)
}
