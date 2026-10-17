package dev.idlegamer.app.session

import dev.idlegamer.app.domain.model.SteamError
import kotlin.math.min
import kotlin.random.Random

/**
 * Bounded exponential backoff with "equal jitter": the delay for attempt n is uniformly distributed in
 * [d/2, d] where d = min(max, base * 2^(n-1)). Permanent errors are never retried.
 */
class ReconnectPolicy(
    private val baseDelayMillis: Long = 2_000,
    private val maxDelayMillis: Long = 5 * 60_000,
    private val rateLimitedMinDelayMillis: Long = 10 * 60_000,
    private val sessionReplacedMinDelayMillis: Long = 5 * 60_000,
    /** After this many consecutive failures the manager gives up and reports FAILED. */
    val maxAttempts: Int = 25,
    /** A connection that stayed authenticated this long resets the attempt counter. */
    val stableConnectionMillis: Long = 2 * 60_000,
    private val random: Random = Random.Default,
) {
    init {
        require(baseDelayMillis in 1..maxDelayMillis)
    }

    sealed interface Decision {
        data class RetryAfter(val delayMillis: Long) : Decision
        data object GiveUp : Decision
    }

    /** @param attempt 1-based number of the reconnect attempt about to be scheduled. */
    fun decide(attempt: Int, error: SteamError): Decision {
        if (error.isPermanent || attempt > maxAttempts) return Decision.GiveUp
        val exponent = (attempt - 1).coerceIn(0, 30)
        val ceiling = min(maxDelayMillis, baseDelayMillis shl exponent.coerceAtMost(20))
        val jittered = ceiling / 2 + random.nextLong(ceiling / 2 + 1)
        val floor = when (error) {
            is SteamError.RateLimited -> rateLimitedMinDelayMillis
            is SteamError.SessionReplaced, is SteamError.PlayingBlocked -> sessionReplacedMinDelayMillis
            else -> 0L
        }
        return Decision.RetryAfter(maxOf(jittered, floor))
    }
}
