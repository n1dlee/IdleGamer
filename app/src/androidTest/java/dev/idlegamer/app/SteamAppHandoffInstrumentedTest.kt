package dev.idlegamer.app

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.idlegamer.app.core.steamapp.SteamAppHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Same-device approval must never hand the QR challenge to anything but Valve's signed Steam app.
 *
 * Run twice: once on a clean emulator (no Steam app) and once with the fixture installed:
 *     ./gradlew :testing:impostor-steam-app:installDebug
 * The fixture uses Steam's package name and handles s.team/q links, but is signed with the local debug key.
 */
@RunWith(AndroidJUnit4::class)
class SteamAppHandoffInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val handoff = SteamAppHandoff(context)
    private val url = "https://s.team/q/1/1234567890"

    private fun steamPackageInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SteamAppHandoff.STEAM_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    @Test fun handoffIsRefusedUnlessValveSignedSteamAppIsPresent() {
        val expected = if (steamPackageInstalled()) {
            // On a test device the only way to have this package is the debug-signed impostor fixture.
            SteamAppHandoff.Availability.UNTRUSTED_SIGNATURE
        } else {
            SteamAppHandoff.Availability.NOT_INSTALLED
        }
        assertEquals(expected, handoff.availability())
        assertFalse(handoff.open(url))
    }

    @Test fun malformedChallengeIsNeverSent() {
        assertFalse(handoff.open("https://evil.example/q/1/1"))
        assertFalse(handoff.open("https://s.team/q/1/1?next=https://evil.example"))
    }
}
