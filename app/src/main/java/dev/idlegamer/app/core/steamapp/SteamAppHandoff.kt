package dev.idlegamer.app.core.steamapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.security.MessageDigest

/**
 * Same-device approval: hands the QR challenge URL to the **official** Steam Mobile app instead of asking the
 * user to scan their own screen (impossible on one phone).
 *
 * Basis: `https://s.team/.well-known/assetlinks.json` (fetched 2026-10-17) declares Android App Links for
 * [STEAM_PACKAGE] with the certificate fingerprints in [TRUSTED_CERT_SHA256]. Whether the app shows the
 * approval prompt for a `/q/` link is verified at runtime only as far as Android can tell (an activity resolves
 * for the explicit intent); the actual prompt must be confirmed on a real phone (TEST_PLAN.md A14).
 *
 * The challenge URL is sensitive (whoever approves it signs this device in), so it is only ever sent:
 *  * as an **explicit** intent (`setPackage`) — no chooser, no other app can receive it;
 *  * to a package whose signing certificate matches Valve's published fingerprints;
 *  * if it has exactly the `https://s.team/q/<version>/<clientId>` shape.
 */
class SteamAppHandoff(private val context: Context) {

    enum class Availability { AVAILABLE, NOT_INSTALLED, UNTRUSTED_SIGNATURE, CANNOT_HANDLE_LINK }

    fun availability(sampleChallengeUrl: String = "https://s.team/q/1/1"): Availability {
        val pm = context.packageManager
        val installed = try {
            pm.getPackageInfo(STEAM_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return Availability.NOT_INSTALLED
        if (!isSignedByValve(pm)) return Availability.UNTRUSTED_SIGNATURE
        val resolves = pm.resolveActivity(buildIntent(sampleChallengeUrl), 0) != null
        return if (resolves) Availability.AVAILABLE else Availability.CANNOT_HANDLE_LINK
    }

    /** @return true if the Steam app was opened. */
    fun open(challengeUrl: String): Boolean {
        if (!isValidChallengeUrl(challengeUrl)) return false
        if (availability(challengeUrl) != Availability.AVAILABLE) return false
        return try {
            context.startActivity(buildIntent(challengeUrl).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun buildIntent(url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(STEAM_PACKAGE)

    private fun isSignedByValve(pm: PackageManager): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Checks the package's proven signing lineage (handles key rotation).
            TRUSTED_CERT_SHA256.any { pm.hasSigningCertificate(STEAM_PACKAGE, hexToBytes(it), PackageManager.CERT_INPUT_SHA256) }
        } else {
            @Suppress("DEPRECATION")
            val signatures = pm.getPackageInfo(STEAM_PACKAGE, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            signatures.isNotEmpty() && signatures.all { sha256Hex(it.toByteArray()) in TRUSTED_CERT_SHA256 }
        }
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val STEAM_PACKAGE = "com.valvesoftware.android.steam.community"

        /** From https://s.team/.well-known/assetlinks.json, target `com.valvesoftware.android.steam.community`. */
        val TRUSTED_CERT_SHA256: Set<String> = setOf(
            "5DFF6B05761447A5BDF919EA88FC6FDF20D301E30B2315415C4D368EC0FBDA45",
            "FAC61745DC0903786FB9EDE62A962B399F7348F0BB6F899B8332667591033B9C",
        )

        private val CHALLENGE_URL = Regex("""^https://s\.team/q/\d{1,4}/\d{1,20}$""")

        fun isValidChallengeUrl(url: String): Boolean = CHALLENGE_URL.matches(url)

        fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0 && hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' })
            return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }
    }
}
