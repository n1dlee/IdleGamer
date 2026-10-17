package dev.idlegamer.app.data.steam

import dev.idlegamer.app.core.security.Secret
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.model.SteamId
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Protocol boundary (R1). Everything above this interface is independent of JavaSteam; replacing the
 * library or adapting to a Steam protocol change touches only the implementation.
 *
 * All suspend functions throw [dev.idlegamer.app.domain.model.SteamException] on failure and honour
 * coroutine cancellation.
 */
interface SteamGateway {
    val status: StateFlow<GatewayStatus>
    val events: SharedFlow<SteamEvent>

    /** Opens the CM transport. Returns once Steam accepted the connection. */
    suspend fun connect()

    /** Closes the transport. Idempotent. */
    suspend fun disconnect()

    /** Requires a connected transport. */
    suspend fun beginQrLogin(deviceFriendlyName: String): QrLoginSession

    /** Logs on with a refresh token. Returns Steam's verdict; never retries by itself. */
    suspend fun logOn(accountName: String, refreshToken: Secret, loginId: Int): LogOnResult

    /** Sends ClientLogOff and closes the transport. */
    suspend fun logOff()

    suspend fun fetchOwnedGames(steamId: SteamId, appIdsFilter: List<Int> = emptyList()): List<OwnedGame>

    /** Replaces the presence list. An empty list clears presence. */
    suspend fun setGamesPlayed(appIds: List<Int>)

    /**
     * Asks Steam to revoke [refreshToken] (IAuthenticationService.RevokeToken). Returns true only if Steam
     * answered OK; false means "not confirmed" and the user must revoke manually.
     */
    suspend fun revokeRefreshToken(refreshToken: Secret): Boolean

    /** GenerateAccessTokenForApp with renewal allowed. Returns a new refresh token if Steam issued one. */
    suspend fun renewRefreshToken(steamId: SteamId, refreshToken: Secret): Secret?

    /** Requests a persona update for the given account (results arrive as [SteamEvent.PersonaUpdate]). */
    suspend fun requestPersona(steamId: SteamId)
}

sealed interface GatewayStatus {
    data class Disconnected(val reason: DisconnectReason) : GatewayStatus
    data object Connecting : GatewayStatus
    data object Connected : GatewayStatus
    data class LoggedOn(val steamId: SteamId) : GatewayStatus
}

sealed interface DisconnectReason {
    data object NeverConnected : DisconnectReason
    data object UserInitiated : DisconnectReason
    data object TransportLost : DisconnectReason
    /** Steam ended the logon session (LoggedOffCallback) with this error. */
    data class LoggedOff(val error: SteamError) : DisconnectReason
}

sealed interface SteamEvent {
    data class AccountInfo(val personaName: String) : SteamEvent
    data class PersonaUpdate(val steamId: SteamId, val name: String?, val avatarHashHex: String?, val playingAppId: Int) : SteamEvent
    data class PlayingSessionState(val playingBlocked: Boolean, val playingAppId: Int) : SteamEvent
    data class LoggedOff(val error: SteamError) : SteamEvent
}

sealed interface LogOnResult {
    data class Success(val steamId: SteamId) : LogOnResult
    data class Failure(val error: SteamError) : LogOnResult
}

data class OwnedGame(
    val appId: Int,
    val name: String,
    val playtimeForeverMinutes: Int,
    val playtimeTwoWeeksMinutes: Int,
    val iconHash: String?,
    val lastPlayedEpochSeconds: Long,
)

/** A live QR auth session. Cancelling the owning coroutine abandons it. */
interface QrLoginSession {
    /** Current challenge URL; Steam may rotate it while polling. Never log it. */
    val challengeUrl: StateFlow<String>

    /** Interval Steam asked us to poll at. */
    val pollIntervalMillis: Long

    /** One poll. Does not loop and does not sleep. */
    suspend fun poll(): QrPollResult
}

sealed interface QrPollResult {
    data object Pending : QrPollResult
    data class Approved(val accountName: String, val refreshToken: Secret) : QrPollResult {
        override fun toString(): String = "Approved(***)"
    }
    data object Expired : QrPollResult
    data class Failed(val error: SteamError) : QrPollResult
}
