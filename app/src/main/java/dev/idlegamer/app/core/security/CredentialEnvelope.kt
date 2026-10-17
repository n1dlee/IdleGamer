package dev.idlegamer.app.core.security

import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.model.SteamId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * On-disk vault format, version 1 (all integers big-endian):
 *
 * ```
 * magic    4 bytes  "IGV1"
 * version  1 byte   0x01
 * purpose  1 byte   0x01 = session credentials
 * steamId  8 bytes  SteamID64 of the account (plaintext; authenticated through AAD)
 * ivLen    1 byte
 * iv       ivLen bytes
 * ctLen    4 bytes
 * ct       ctLen bytes (AES-GCM ciphertext || 16-byte tag)
 * ```
 *
 * AAD = "dev.idlegamer.vault|v<version>|<purpose>|<keyAlias>|<steamId>". Binding the account id and
 * purpose means a ciphertext copied from another record, version or account fails tag verification.
 */
object CredentialEnvelope {
    private val MAGIC = byteArrayOf('I'.code.toByte(), 'G'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val FORMAT_VERSION: Int = 1
    const val PURPOSE_SESSION_CREDENTIALS: Int = 1
    private const val MAX_FIELD_BYTES = 16 * 1024

    fun aad(version: Int, purpose: Int, keyAlias: String, steamId: SteamId): ByteArray =
        "dev.idlegamer.vault|v$version|$purpose|$keyAlias|${steamId.asDecimalString()}".toByteArray(Charsets.UTF_8)

    fun seal(credentials: SessionCredentials, cipher: CredentialCipher): ByteArray {
        val aad = aad(FORMAT_VERSION, PURPOSE_SESSION_CREDENTIALS, cipher.keyAlias, credentials.steamId)
        val plaintext = encodePlaintext(credentials)
        val payload = try {
            cipher.encrypt(plaintext, aad)
        } finally {
            plaintext.fill(0)
        }
        require(payload.iv.size in 12..16) { "Unexpected IV length" }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC)
                out.writeByte(FORMAT_VERSION)
                out.writeByte(PURPOSE_SESSION_CREDENTIALS)
                out.writeLong(credentials.steamId.raw)
                out.writeByte(payload.iv.size)
                out.write(payload.iv)
                out.writeInt(payload.ciphertext.size)
                out.write(payload.ciphertext)
            }
            bytes.toByteArray()
        }
    }

    /** @throws CipherFailure.Corrupted for malformed envelopes or failed authentication. */
    fun open(bytes: ByteArray, cipher: CredentialCipher): SessionCredentials {
        val header = try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(4).also(input::readFully)
                if (!magic.contentEquals(MAGIC)) throw CipherFailure.Corrupted()
                val version = input.readUnsignedByte()
                if (version != FORMAT_VERSION) throw CipherFailure.Corrupted()
                val purpose = input.readUnsignedByte()
                if (purpose != PURPOSE_SESSION_CREDENTIALS) throw CipherFailure.Corrupted()
                val steamId = SteamId(input.readLong())
                val ivLen = input.readUnsignedByte()
                if (ivLen !in 12..16) throw CipherFailure.Corrupted()
                val iv = ByteArray(ivLen).also(input::readFully)
                val ctLen = input.readInt()
                if (ctLen !in 17..MAX_FIELD_BYTES) throw CipherFailure.Corrupted()
                val ct = ByteArray(ctLen).also(input::readFully)
                if (input.read() != -1) throw CipherFailure.Corrupted()
                Header(version, purpose, steamId, EncryptedPayload(iv, ct))
            }
        } catch (e: EOFException) {
            throw CipherFailure.Corrupted(e)
        } catch (e: IOException) {
            throw CipherFailure.Corrupted(e)
        }

        val aad = aad(header.version, header.purpose, cipher.keyAlias, header.steamId)
        val plaintext = cipher.decrypt(header.payload, aad)
        return try {
            decodePlaintext(plaintext, header.steamId)
        } finally {
            plaintext.fill(0)
        }
    }

    private class Header(val version: Int, val purpose: Int, val steamId: SteamId, val payload: EncryptedPayload)

    private fun encodePlaintext(c: SessionCredentials): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeLong(c.steamId.raw)
            out.writeUTF(c.accountName)
            out.writeUTF(c.refreshToken.reveal())
            out.writeLong(c.createdAtEpochMillis)
        }
        bytes.toByteArray()
    }

    private fun decodePlaintext(bytes: ByteArray, headerSteamId: SteamId): SessionCredentials = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val innerSteamId = SteamId(input.readLong())
            if (innerSteamId != headerSteamId) throw CipherFailure.Corrupted()
            SessionCredentials(
                steamId = innerSteamId,
                accountName = input.readUTF(),
                refreshToken = Secret(input.readUTF()),
                createdAtEpochMillis = input.readLong(),
            )
        }
    } catch (e: IOException) {
        throw CipherFailure.Corrupted(e)
    } catch (e: IllegalArgumentException) {
        throw CipherFailure.Corrupted(e)
    }
}
