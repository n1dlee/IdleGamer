package dev.idlegamer.app.core.security

import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.repository.CredentialStoreException
import dev.idlegamer.app.domain.repository.SecureCredentialStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Vault file in the app's no-backup directory (`Context.noBackupFilesDir`), sealed with
 * [CredentialEnvelope]. Writes are atomic (temp file + fsync + atomic rename) so a crash mid-write never
 * leaves a half-written vault.
 */
class FileSecureCredentialStore(
    private val vaultFile: File,
    private val cipher: CredentialCipher,
    private val io: CoroutineDispatcher,
) : SecureCredentialStore {

    private val mutex = Mutex()

    override suspend fun save(credentials: SessionCredentials) = mutex.withLock {
        withContext(io) {
            val sealed = try {
                CredentialEnvelope.seal(credentials, cipher)
            } catch (e: CipherFailure.KeyUnavailable) {
                throw CredentialStoreException(CredentialStoreException.Kind.KEY_UNAVAILABLE, e)
            } catch (e: CipherFailure) {
                throw CredentialStoreException(CredentialStoreException.Kind.CORRUPTED, e)
            }
            try {
                vaultFile.parentFile?.mkdirs()
                val tmp = File(vaultFile.parentFile, vaultFile.name + ".tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(sealed)
                    out.fd.sync()
                }
                Files.move(
                    tmp.toPath(),
                    vaultFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: IOException) {
                throw CredentialStoreException(CredentialStoreException.Kind.IO, e)
            } finally {
                sealed.fill(0)
            }
            Unit
        }
    }

    override suspend fun load(): SessionCredentials? = mutex.withLock {
        withContext(io) {
            if (!vaultFile.exists()) return@withContext null
            val bytes = try {
                vaultFile.readBytes()
            } catch (e: IOException) {
                throw CredentialStoreException(CredentialStoreException.Kind.IO, e)
            }
            try {
                CredentialEnvelope.open(bytes, cipher)
            } catch (e: CipherFailure.KeyUnavailable) {
                throw CredentialStoreException(CredentialStoreException.Kind.KEY_UNAVAILABLE, e)
            } catch (e: CipherFailure) {
                throw CredentialStoreException(CredentialStoreException.Kind.CORRUPTED, e)
            }
        }
    }

    /** Deletes the vault file and the Keystore key. Safe to call repeatedly. */
    override suspend fun clear() = mutex.withLock {
        withContext(io) {
            File(vaultFile.parentFile, vaultFile.name + ".tmp").delete()
            if (vaultFile.exists() && !vaultFile.delete()) {
                // Overwrite so a failed delete cannot leave a decryptable vault behind.
                runCatching { vaultFile.writeBytes(ByteArray(0)) }
            }
            cipher.deleteKey()
        }
    }
}
