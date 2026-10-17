package dev.idlegamer.app.core

import dev.idlegamer.app.core.steamapp.SteamAppHandoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamAppHandoffTest {
    @Test fun `accepts only the Steam challenge url shape`() {
        assertTrue(SteamAppHandoff.isValidChallengeUrl("https://s.team/q/1/2372462679780599330"))
        listOf(
            "http://s.team/q/1/2372462679780599330",
            "https://s.team.evil.com/q/1/2",
            "https://evil.com/s.team/q/1/2",
            "https://s.team/q/1/2?redirect=https://evil.com",
            "https://s.team/q/1/2/extra",
            "https://s.team/q/1/abc",
            "javascript:alert(1)",
            "intent://s.team/q/1/2#Intent;package=evil;end",
            "",
        ).forEach { assertFalse(it, SteamAppHandoff.isValidChallengeUrl(it)) }
    }

    @Test fun `trusted fingerprints are well-formed sha256 values`() {
        assertEquals(2, SteamAppHandoff.TRUSTED_CERT_SHA256.size)
        SteamAppHandoff.TRUSTED_CERT_SHA256.forEach { assertEquals(32, SteamAppHandoff.hexToBytes(it).size) }
    }

    @Test fun `hex helpers round trip`() {
        val bytes = SteamAppHandoff.hexToBytes("00FF10ab")
        assertEquals(listOf(0x00, 0xFF, 0x10, 0xAB).map { it.toByte() }, bytes.toList())
        assertEquals(
            "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            SteamAppHandoff.sha256Hex("abc".toByteArray()),
        )
    }
}
