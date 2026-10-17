package dev.idlegamer.app.data.steam

import dev.idlegamer.app.domain.model.SteamError
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Maps Steam `EResult` codes and transport exceptions to [SteamError]. Uses the numeric codes (stable
 * across library versions; see `in.dragonbra.javasteam.enums.EResult`) so the mapping has no
 * JavaSteam dependency and is unit-testable.
 */
object SteamErrorMapper {
    // EResult numeric values (EResult.java, JavaSteam 1.8.0).
    const val OK = 1
    const val FAIL = 2
    const val NO_CONNECTION = 3
    const val INVALID_PASSWORD = 5
    const val LOGGED_IN_ELSEWHERE = 6
    const val INVALID_PROTOCOL_VER = 7
    const val FILE_NOT_FOUND = 9
    const val BUSY = 10
    const val ACCESS_DENIED = 15
    const val TIMEOUT = 16
    const val BANNED = 17
    const val ACCOUNT_NOT_FOUND = 18
    const val SERVICE_UNAVAILABLE = 20
    const val NOT_LOGGED_ON = 21
    const val LIMIT_EXCEEDED = 25
    const val REVOKED = 26
    const val EXPIRED = 27
    const val LOGON_SESSION_REPLACED = 34
    const val CONNECT_FAILED = 35
    const val REMOTE_DISCONNECT = 38
    const val ACCOUNT_DISABLED = 43
    const val TRY_ANOTHER_CM = 48
    const val SUSPENDED = 51
    const val ACCOUNT_LOGON_DENIED = 63
    const val ACCOUNT_LOCKED_DOWN = 73
    const val RATE_LIMIT_EXCEEDED = 84
    const val ACCOUNT_LOGIN_DENIED_THROTTLE = 87
    const val IP_BANNED = 105
    const val INVALID_SIGNATURE = 121

    fun fromResultCode(code: Int): SteamError = when (code) {
        INVALID_PASSWORD, ACCESS_DENIED, REVOKED, EXPIRED, INVALID_SIGNATURE, ACCOUNT_NOT_FOUND, ACCOUNT_LOGON_DENIED ->
            SteamError.InvalidCredentials(code)

        BANNED, ACCOUNT_DISABLED, SUSPENDED, ACCOUNT_LOCKED_DOWN, IP_BANNED ->
            SteamError.AccountRestricted(code)

        RATE_LIMIT_EXCEEDED, ACCOUNT_LOGIN_DENIED_THROTTLE, LIMIT_EXCEEDED ->
            SteamError.RateLimited(code)

        SERVICE_UNAVAILABLE, TRY_ANOTHER_CM, BUSY ->
            SteamError.ServerUnavailable(code)

        LOGGED_IN_ELSEWHERE, LOGON_SESSION_REPLACED ->
            SteamError.SessionReplaced(code)

        NO_CONNECTION, CONNECT_FAILED, REMOTE_DISCONNECT, NOT_LOGGED_ON -> SteamError.ConnectionReset
        TIMEOUT -> SteamError.Timeout
        INVALID_PROTOCOL_VER -> SteamError.ProtocolIncompatible("InvalidProtocolVer")
        else -> SteamError.Unexpected(code)
    }

    fun fromThrowable(t: Throwable): SteamError = when (t) {
        is TimeoutCancellationException, is TimeoutException, is SocketTimeoutException -> SteamError.Timeout
        is UnknownHostException -> SteamError.DnsFailure
        is NoRouteToHostException, is ConnectException -> SteamError.NetworkUnavailable
        is SocketException, is EOFException -> SteamError.ConnectionReset
        is IOException -> SteamError.ConnectionReset
        else -> t.cause?.takeIf { it !== t }?.let(::fromThrowable)
            ?: SteamError.Unexpected(FAIL, t.javaClass.simpleName)
    }
}
