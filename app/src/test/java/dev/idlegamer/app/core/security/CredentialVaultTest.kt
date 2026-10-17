package dev.idlegamer.app.core.security

import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.repository.CredentialStoreException
import dev.idlegamer.app.testing.OTHER_STEAM_ID
import dev.idlegamer.app.testing.SoftwareCipher
import dev.idlegamer.app.testing.TEST_STEAM_ID
import dev.idlegamer.app.testing.fakeJwt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class CredentialEnvelopeTest {
    private val cipher = SoftwareCipher()
    private val creds = SessionCredentials(TEST_STEAM_ID, "test_account", fakeJwt(TEST_STEAM_ID), 123L)

    @Test fun `round trip`() {
        val opened = CredentialEnvelope.open(CredentialEnvelope.seal(creds, cipher), cipher)
        assertEquals(creds.steamId, opened.steamId)
        assertEquals(creds.accountName, opened.accountName)
        assertEquals(creds.refreshToken, opened.refreshToken)
        assertEquals(creds.createdAtEpochMillis, opened.createdAtEpochMillis)
    }

    @Test fun `ciphertext does not contain the token or account name`() {
        val sealed = String(CredentialEnvelope.seal(creds, cipher), Charsets.ISO_8859_1)
        assertFalse(sealed.contains(creds.refreshToken.reveal()))
        assertFalse(sealed.contains("eyJ"))
        assertFalse(sealed.contains("test_account"))
    }

    @Test fun `every encryption uses a fresh IV`() {
        repeat(500) { CredentialEnvelope.seal(creds, cipher) }
        assertEquals(500, cipher.ivsUsed.toSet().size)
    }

    @Test fun `any flipped byte is rejected`() {
        val sealed = CredentialEnvelope.seal(creds, cipher)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertThrows("byte $i", CipherFailure.Corrupted::class.java) { CredentialEnvelope.open(tampered, cipher) }
        }
    }

    @Test fun `ciphertext cannot be moved to another account header`() {
        val sealed = CredentialEnvelope.seal(creds, cipher)
        // steamId lives at offset 6 (magic 4 + version 1 + purpose 1).
        val moved = sealed.copyOf().also { ByteBuffer.wrap(it).putLong(6, OTHER_STEAM_ID.raw) }
        assertThrows(CipherFailure.Corrupted::class.java) { CredentialEnvelope.open(moved, cipher) }
    }

    @Test fun `ciphertext from another key alias is rejected`() {
        val sealed = CredentialEnvelope.seal(creds, cipher)
        val sameKeyOtherAlias = object : CredentialCipher by cipher {
            override val keyAlias: String = "other.alias"
        }
        assertThrows(CipherFailure.Corrupted::class.java) { CredentialEnvelope.open(sealed, sameKeyOtherAlias) }
    }

    @Test fun `truncated and garbage input is rejected`() {
        val sealed = CredentialEnvelope.seal(creds, cipher)
        listOf(ByteArray(0), sealed.copyOf(10), sealed.copyOf(sealed.size - 1), "hello".toByteArray(), sealed + byteArrayOf(0))
            .forEach { assertThrows(CipherFailure.Corrupted::class.java) { CredentialEnvelope.open(it, cipher) } }
    }

    @Test fun `unknown format version is rejected`() {
        val sealed = CredentialEnvelope.seal(creds, cipher).also { it[4] = 2 }
        assertThrows(CipherFailure.Corrupted::class.java) { CredentialEnvelope.open(sealed, cipher) }
    }

    @Test fun `deleted key means key unavailable`() {
        val sealed = CredentialEnvelope.seal(creds, cipher)
        cipher.deleteKey()
        assertThrows(CipherFailure.KeyUnavailable::class.java) { CredentialEnvelope.open(sealed, cipher) }
    }

    @Test fun `regenerated key cannot open old vault`() {
        val sealed = CredentialEnvelope.seal(creds, cipher)
        cipher.regenerateKey()
        assertThrows(CipherFailure.Corrupted::class.java) { CredentialEnvelope.open(sealed, cipher) }
    }
}

class FileSecureCredentialStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val creds = SessionCredentials(TEST_STEAM_ID, "acc", fakeJwt(TEST_STEAM_ID), 1L)

    private fun store(cipher: SoftwareCipher = SoftwareCipher()) =
        FileSecureCredentialStore(tmp.root.resolve("vault/session.bin"), cipher, Dispatchers.Unconfined)

    @Test fun `empty store loads null`() = runTest { assertNull(store().load()) }

    @Test fun `save load clear`() = runTest {
        val s = store()
        s.save(creds)
        assertEquals(creds.refreshToken, s.load()?.refreshToken)
        s.clear()
        assertNull(s.load())
        assertFalse(tmp.root.resolve("vault/session.bin").exists())
    }

    @Test fun `clear destroys the key`() = runTest {
        val cipher = SoftwareCipher()
        val s = store(cipher)
        s.save(creds)
        s.clear()
        assertThrows(CipherFailure.KeyUnavailable::class.java) { cipher.encrypt(ByteArray(1), ByteArray(0)) }
    }

    @Test fun `file on disk holds no plaintext secret`() = runTest {
        store().save(creds)
        val raw = tmp.root.resolve("vault/session.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(creds.refreshToken.reveal()))
        assertFalse(tmp.root.resolve("vault/session.bin.tmp").exists())
    }

    @Test fun `corrupted file is reported as corrupted`() = runTest {
        val s = store()
        s.save(creds)
        tmp.root.resolve("vault/session.bin").writeBytes("garbage".toByteArray())
        val e = runCatching { s.load() }.exceptionOrNull() as CredentialStoreException
        assertEquals(CredentialStoreException.Kind.CORRUPTED, e.kind)
    }

    @Test fun `lost key is reported as key unavailable`() = runTest {
        val cipher = SoftwareCipher()
        val s = store(cipher)
        s.save(creds)
        cipher.deleteKey()
        val e = runCatching { s.load() }.exceptionOrNull() as CredentialStoreException
        assertEquals(CredentialStoreException.Kind.KEY_UNAVAILABLE, e.kind)
    }

    @Test fun `overwrite replaces the previous record`() = runTest {
        val s = store()
        s.save(creds)
        val newer = creds.copy(refreshToken = fakeJwt(TEST_STEAM_ID, expEpochSeconds = 5_000_000_000L))
        s.save(newer)
        assertTrue(s.load()?.refreshToken == newer.refreshToken)
    }
}
