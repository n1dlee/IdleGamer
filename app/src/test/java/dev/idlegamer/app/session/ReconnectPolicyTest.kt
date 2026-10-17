package dev.idlegamer.app.session

import dev.idlegamer.app.domain.model.SteamError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {
    private val policy = ReconnectPolicy(random = Random(42))

    private fun delayOf(attempt: Int, error: SteamError = SteamError.ConnectionReset): Long =
        (policy.decide(attempt, error) as ReconnectPolicy.Decision.RetryAfter).delayMillis

    @Test fun `delays grow exponentially within jitter bounds`() {
        repeat(200) {
            val first = delayOf(1)
            assertTrue(first in 1_000..2_000)
            val fourth = delayOf(4)
            assertTrue(fourth in 8_000..16_000)
        }
    }

    @Test fun `delay is capped at max`() {
        repeat(200) { assertTrue(delayOf(20) in 150_000..300_000) }
    }

    @Test fun `jitter actually varies`() {
        val samples = (1..50).map { delayOf(6) }.toSet()
        assertTrue("expected jitter, got $samples", samples.size > 10)
    }

    @Test fun `permanent errors are never retried`() {
        listOf(
            SteamError.InvalidCredentials(5), SteamError.AccountRestricted(17), SteamError.IdentityMismatch,
            SteamError.ProtocolIncompatible("x"), SteamError.Unsupported("x"),
        ).forEach { assertEquals(ReconnectPolicy.Decision.GiveUp, policy.decide(1, it)) }
    }

    @Test fun `rate limiting waits at least ten minutes`() {
        repeat(50) { assertTrue(delayOf(1, SteamError.RateLimited(84)) >= 10 * 60_000) }
    }

    @Test fun `session replaced waits at least five minutes`() {
        assertTrue(delayOf(1, SteamError.SessionReplaced(6)) >= 5 * 60_000)
    }

    @Test fun `gives up after max attempts`() {
        assertEquals(ReconnectPolicy.Decision.GiveUp, policy.decide(policy.maxAttempts + 1, SteamError.Timeout))
        assertTrue(policy.decide(policy.maxAttempts, SteamError.Timeout) is ReconnectPolicy.Decision.RetryAfter)
    }
}
