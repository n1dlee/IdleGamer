package dev.idlegamer.app.core

import dev.idlegamer.app.core.logging.Redactor
import dev.idlegamer.app.core.security.JwtClaims
import dev.idlegamer.app.core.security.Secret
import dev.idlegamer.app.data.steam.SteamErrorMapper
import dev.idlegamer.app.domain.model.Game
import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.model.SteamId
import dev.idlegamer.app.testing.TEST_STEAM_ID
import dev.idlegamer.app.testing.fakeJwt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RedactorTest {
    @Test fun `jwt is removed`() {
        val jwt = fakeJwt(TEST_STEAM_ID).reveal()
        val out = Redactor.redact("logon with $jwt done")
        assertFalse(out.contains(jwt))
        assertFalse(out.contains("eyJ"))
        assertTrue(out.contains("[REDACTED_JWT]"))
    }

    @Test fun `qr challenge url is removed`() {
        assertEquals("show [REDACTED_QR_URL] now", Redactor.redact("show https://s.team/q/1/1234567890 now"))
    }

    @Test fun `sensitive key value pairs are masked`() {
        listOf("refresh_token=abc", "accessToken: \"xyz\"", "password=hunter2", "guard_data=1234", "steamLoginSecure=777", "Authorization: Bearer abc.def")
            .forEach { input ->
                val out = Redactor.redact(input)
                assertTrue("$input -> $out", out.contains("[REDACTED]"))
                assertFalse("$input -> $out", out.contains("hunter2") || out.endsWith("abc") || out.contains("xyz") || out.contains("777"))
            }
    }

    @Test fun `steam id is shortened`() {
        assertEquals("id 7656119…7930", Redactor.redact("id 76561197960287930"))
    }

    @Test fun `long blobs are removed`() {
        val blob = "A".repeat(64)
        assertFalse(Redactor.redact("x $blob y").contains(blob))
    }

    @Test fun `ordinary text survives`() {
        assertEquals("connection lost: Timeout attempt 3", Redactor.redact("connection lost: Timeout attempt 3"))
    }
}

class SecretsDoNotLeakTest {
    @Test fun `secret and credentials toString hide values`() {
        val token = fakeJwt(TEST_STEAM_ID)
        val creds = SessionCredentials(TEST_STEAM_ID, "my_account", token, 1)
        assertFalse(token.toString().contains(token.reveal()))
        assertFalse(creds.toString().contains(token.reveal()))
        assertFalse(creds.toString().contains("my_account"))
        assertFalse("$creds".contains("eyJ"))
    }
}

class JwtClaimsTest {
    @Test fun `reads subject expiry and audience`() {
        val claims = JwtClaims.parse(fakeJwt(TEST_STEAM_ID, expEpochSeconds = 1_900_000_000))!!
        assertEquals(TEST_STEAM_ID, claims.subject)
        assertEquals(1_900_000_000L, claims.expiresAtEpochSeconds)
        assertEquals(listOf("client", "web"), claims.audience)
    }

    @Test fun `non jwt returns null`() {
        assertNull(JwtClaims.parse(Secret("not-a-jwt")))
        assertNull(JwtClaims.parse(Secret("a.!!!.c")))
    }
}

class SteamIdTest {
    @Test fun `renders unsigned and survives large values`() {
        assertEquals("76561197960287930", TEST_STEAM_ID.asDecimalString())
        assertEquals("18446744073709551615", SteamId(-1L).asDecimalString())
    }

    @Test fun `parse validates individual accounts`() {
        assertEquals(TEST_STEAM_ID, SteamId.parse("76561197960287930"))
        assertNull(SteamId.parse("123"))
        assertNull(SteamId.parse("not a number"))
    }

    @Test fun `masked and toString never show the full id`() {
        assertEquals("7656119…7930", TEST_STEAM_ID.masked())
        assertFalse(TEST_STEAM_ID.toString().contains("76561197960287930"))
    }
}

class GameTest {
    @Test fun `app id parsing`() {
        assertEquals(440, Game.parseAppId("440"))
        assertEquals(440, Game.parseAppId(" 440 "))
        assertEquals(16_777_215, Game.parseAppId("16777215"))
        listOf("", "0", "-1", "16777216", "4294967296", "12a", "1.5", "999999999").forEach { assertNull(it, Game.parseAppId(it)) }
    }

    @Test fun `icon url only from valid hash`() {
        assertNull(Game.iconUrl(440, null))
        assertNull(Game.iconUrl(440, "../../evil"))
        assertTrue(Game.iconUrl(440, "a".repeat(40))!!.startsWith("https://media.steampowered.com/"))
    }
}

class SteamErrorMapperTest {
    @Test fun `credential failures are permanent`() {
        listOf(5, 15, 26, 27).forEach {
            val e = SteamErrorMapper.fromResultCode(it)
            assertTrue("$it", e is SteamError.InvalidCredentials && e.isPermanent)
        }
    }

    @Test fun `temporary failures are retryable`() {
        listOf(20, 48, 10).forEach { assertTrue(SteamErrorMapper.fromResultCode(it) is SteamError.ServerUnavailable) }
        listOf(84, 87).forEach { assertTrue(SteamErrorMapper.fromResultCode(it) is SteamError.RateLimited) }
        listOf(6, 34).forEach { assertTrue(SteamErrorMapper.fromResultCode(it) is SteamError.SessionReplaced) }
        assertFalse(SteamErrorMapper.fromResultCode(999).isPermanent)
    }

    @Test fun `restricted accounts are permanent`() {
        listOf(17, 43, 51, 73).forEach { assertTrue(SteamErrorMapper.fromResultCode(it) is SteamError.AccountRestricted) }
    }

    @Test fun `exceptions map to network errors`() {
        assertEquals(SteamError.Timeout, SteamErrorMapper.fromThrowable(SocketTimeoutException()))
        assertEquals(SteamError.DnsFailure, SteamErrorMapper.fromThrowable(UnknownHostException()))
        assertEquals(SteamError.DnsFailure, SteamErrorMapper.fromThrowable(RuntimeException(UnknownHostException())))
        assertTrue(SteamErrorMapper.fromThrowable(IllegalStateException("x")) is SteamError.Unexpected)
    }
}
