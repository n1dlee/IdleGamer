package dev.idlegamer.app.domain.model

import dev.idlegamer.app.core.security.Secret

/**
 * The only long-lived secret IdleGamer keeps: a Steam refresh token for the "client" audience,
 * plus the account name that `CMsgClientLogon` requires alongside it.
 *
 * The access token is deliberately absent: CM logon does not need it, so it is never persisted.
 */
data class SessionCredentials(
    val steamId: SteamId,
    val accountName: String,
    val refreshToken: Secret,
    val createdAtEpochMillis: Long,
) {
    init {
        require(accountName.isNotBlank()) { "accountName must not be blank" }
    }

    override fun toString(): String =
        "SessionCredentials(steamId=$steamId, accountName=***, refreshToken=***, createdAt=$createdAtEpochMillis)"
}

/** Public, non-secret identity of the signed-in account. */
data class AccountIdentity(val steamId: SteamId) {
    override fun toString(): String = "AccountIdentity($steamId)"
}

data class UserProfile(
    val steamId: SteamId,
    val personaName: String?,
    val avatarUrl: String?,
)
