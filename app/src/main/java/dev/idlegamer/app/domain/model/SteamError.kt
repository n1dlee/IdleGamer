package dev.idlegamer.app.domain.model

/**
 * Typed failures of anything that talks to Steam. The protocol adapter converts library-specific
 * exceptions and result codes into these; nothing above the adapter sees JavaSteam types.
 */
sealed interface SteamError {
    /** True when retrying with the same input can never succeed (e.g. an invalid token). */
    val isPermanent: Boolean

    sealed interface Network : SteamError {
        override val isPermanent: Boolean get() = false
    }

    data object NetworkUnavailable : Network
    data object Timeout : Network
    data object ConnectionReset : Network
    data object DnsFailure : Network

    /** Steam reported a temporary server-side problem (ServiceUnavailable, TryAnotherCM, Busy, maintenance). */
    data class ServerUnavailable(val resultCode: Int) : SteamError {
        override val isPermanent: Boolean get() = false
    }

    /** RateLimitExceeded / AccountLoginDeniedThrottle. Retried only after a long delay. */
    data class RateLimited(val resultCode: Int) : SteamError {
        override val isPermanent: Boolean get() = false
    }

    /** The stored refresh token is not accepted any more. Requires a new QR login. */
    data class InvalidCredentials(val resultCode: Int) : SteamError {
        override val isPermanent: Boolean get() = true
    }

    /** Account banned / disabled / locked down. Nothing IdleGamer can or should retry. */
    data class AccountRestricted(val resultCode: Int) : SteamError {
        override val isPermanent: Boolean get() = true
    }

    /** Another client took over the session (LoggedInElsewhere / LogonSessionReplaced). */
    data class SessionReplaced(val resultCode: Int) : SteamError {
        override val isPermanent: Boolean get() = false
    }

    /** The account is playing a game on another device; Steam blocks presence from this session. */
    data class PlayingBlocked(val playingAppId: Int) : SteamError {
        override val isPermanent: Boolean get() = false
    }

    /** Steam answered in a way the adapter does not understand (likely protocol change, R1). */
    data class ProtocolIncompatible(val detail: String) : SteamError {
        override val isPermanent: Boolean get() = true
    }

    /** Operation not supported by the library / Steam in this form (R18). */
    data class Unsupported(val operation: String) : SteamError {
        override val isPermanent: Boolean get() = true
    }

    /** A Steam result code with no dedicated mapping. Treated as temporary but bounded by retry limits. */
    data class Unexpected(val resultCode: Int, val detail: String? = null) : SteamError {
        override val isPermanent: Boolean get() = false
    }

    /** The logged-on account differs from the one the token belongs to. */
    data object IdentityMismatch : SteamError {
        override val isPermanent: Boolean get() = true
    }
}

class SteamException(val error: SteamError, cause: Throwable? = null) :
    Exception("Steam operation failed: ${error::class.simpleName}", cause)
