package dev.idlegamer.app

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.idlegamer.app.core.security.CipherFailure
import dev.idlegamer.app.core.security.FileSecureCredentialStore
import dev.idlegamer.app.core.security.KeystoreCredentialCipher
import dev.idlegamer.app.core.security.Secret
import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.model.SteamId
import dev.idlegamer.app.domain.repository.CredentialStoreException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Runs against the real Android Keystore on a device/emulator. */
@RunWith(AndroidJUnit4::class)
class KeystoreVaultInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val alias = "idlegamer.test.${System.nanoTime()}"
    private val cipher = KeystoreCredentialCipher(alias)
    private val dir = File(context.noBackupFilesDir, "test-vault-${System.nanoTime()}")
    private val creds = SessionCredentials(SteamId(76561197960287930L), "acct", Secret("header.payload.signature"), 1L)

    @After fun tearDown() {
        cipher.deleteKey()
        dir.deleteRecursively()
    }

    @Test fun roundTripAndUniqueIvs() {
        val ivs = (1..50).map { cipher.encrypt("x".toByteArray(), "aad".toByteArray()).iv.toList() }.toSet()
        assertEquals(50, ivs.size)
        val payload = cipher.encrypt("secret".toByteArray(), "aad".toByteArray())
        assertEquals("secret", String(cipher.decrypt(payload, "aad".toByteArray())))
    }

    @Test fun wrongAadIsRejected() {
        val payload = cipher.encrypt("secret".toByteArray(), "aad-1".toByteArray())
        assertThrows(CipherFailure.Corrupted::class.java) { cipher.decrypt(payload, "aad-2".toByteArray()) }
    }

    @Test fun keystoreRefusesCallerChosenIv() {
        cipher.encrypt(ByteArray(1), ByteArray(0)) // creates the key
        val key = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        assertThrows(InvalidAlgorithmParameterException::class.java) {
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12)))
        }
    }

    @Test fun keyMaterialIsNotExportable() {
        cipher.encrypt(ByteArray(1), ByteArray(0))
        val key: SecretKey = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        assertNull(key.encoded)
    }

    @Test fun vaultEndToEndAndKeyLoss() = runBlocking {
        val file = File(dir, "session.bin")
        val store = FileSecureCredentialStore(file, cipher, Dispatchers.IO)
        store.save(creds)
        assertEquals(creds.refreshToken, store.load()?.refreshToken)
        assertFalse(String(file.readBytes(), Charsets.ISO_8859_1).contains("header.payload.signature"))

        // Simulates key invalidation / device transfer: vault file exists, key does not.
        cipher.deleteKey()
        val e = runCatching { store.load() }.exceptionOrNull() as CredentialStoreException
        assertEquals(CredentialStoreException.Kind.KEY_UNAVAILABLE, e.kind)
        store.clear()
        assertNull(store.load())
    }
}

@RunWith(AndroidJUnit4::class)
class PlatformConfigurationInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realBouncyCastleIsRegisteredForJavaSteam() {
        assertTrue(Security.getProvider("BC") is BouncyCastleProvider)
        // JavaSteam's CryptoHelper needs these from the "BC" provider (TCP channel encryption, SHA-1).
        assertNotNull(Cipher.getInstance("AES/CBC/PKCS7Padding", "BC"))
        assertNotNull(Cipher.getInstance("AES/ECB/NoPadding", "BC"))
        assertNotNull(java.security.MessageDigest.getInstance("SHA-1", "BC"))
        // Loading JavaSteam's crypto helper must not throw from its static initialiser.
        val provider = Class.forName("in.dragonbra.javasteam.util.crypto.CryptoHelper").getField("SEC_PROV").get(null)
        assertEquals("BC", provider)
    }

    @Test fun defaultAesGcmProviderIsUnchanged() {
        // The vault relies on default provider selection reaching AndroidKeyStore.
        assertFalse(Cipher.getInstance("AES/GCM/NoPadding").provider is BouncyCastleProvider)
    }

    @Test fun backupIsDisabled() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun onlyTheLauncherActivityIsExported() {
        val pkg = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
        )
        val exported = buildList {
            pkg.activities?.filter { it.exported }?.forEach { add(it.name) }
            pkg.services?.filter { it.exported }?.forEach { add(it.name) }
            pkg.receivers?.filter { it.exported && it.permission == null }?.forEach { add(it.name) }
            pkg.providers?.filter { it.exported }?.forEach { add(it.name) }
        }
        // androidx.profileinstaller registers a receiver guarded by the DUMP permission; it is filtered above.
        assertEquals(listOf("dev.idlegamer.app.MainActivity"), exported)
    }

    @Test fun serviceIsNotExportedAndUsesSpecialUse() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
        val service = pkg.services!!.single { it.name.endsWith("SteamSessionService") }
        assertFalse(service.exported)
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.foregroundServiceType)
    }

    @Test fun noDangerousPermissionsBeyondNotifications() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = pkg.requestedPermissions.orEmpty().toSet()
        val allowed = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
            // Added by AndroidX biometric / core libraries.
            "android.permission.USE_BIOMETRIC",
            "android.permission.USE_FINGERPRINT",
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        assertEquals(emptySet<String>(), requested - allowed)
    }
}
