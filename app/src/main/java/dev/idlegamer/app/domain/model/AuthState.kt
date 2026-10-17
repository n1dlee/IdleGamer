package dev.idlegamer.app.domain.model

/**
 * Whether IdleGamer holds usable credentials for an account. This is independent of whether a CM
 * connection is currently open — that is [ConnectionState].
 */
sealed interface AuthState {
    /** Nothing loaded yet (app start). */
    data object Unknown : AuthState

    data object LoggedOut : AuthState

    /** A QR login was requested; connecting to Steam and asking for a challenge. */
    data object Initializing : AuthState

    /**
     * A QR challenge is on screen. [challengeUrl] is sensitive while valid: whoever approves it in
     * the Steam app signs *this* device in, so it is only rendered locally and never logged.
     */
    data class WaitingForApproval(val challengeUrl: String, val expiresAtElapsedMillis: Long) : AuthState {
        override fun toString(): String = "WaitingForApproval(challengeUrl=***, expiresAt=$expiresAtElapsedMillis)"
    }

    /** Approved in the Steam app; logging on and verifying the identity before persisting anything. */
    data object Authenticating : AuthState

    data class Authenticated(val identity: AccountIdentity) : AuthState

    data class ReauthenticationRequired(val reason: ReauthReason) : AuthState

    data class Failed(val error: AuthError) : AuthState
}

enum class ReauthReason {
    /** Steam rejected the stored refresh token (expired, revoked, access denied). */
    TokenRejected,

    /** The vault could not be decrypted (key invalidated, device transfer, corruption). */
    LocalCredentialsUnavailable,

    /** Steam told us the session was revoked while connected. */
    SessionRevoked,
}

sealed interface AuthError {
    data object QrExpired : AuthError
    data object PollingLimitReached : AuthError
    data class Steam(val error: SteamError) : AuthError
    data object StorageFailure : AuthError
}

sealed interface AuthEvent {
    data class StoredCredentialsFound(val identity: AccountIdentity) : AuthEvent
    data object NoStoredCredentials : AuthEvent
    data object StoredCredentialsUnreadable : AuthEvent
    data object QrLoginRequested : AuthEvent
    data class QrChallengeIssued(val challengeUrl: String, val expiresAtElapsedMillis: Long) : AuthEvent {
        override fun toString(): String = "QrChallengeIssued(***)"
    }
    data class QrChallengeRefreshed(val challengeUrl: String, val expiresAtElapsedMillis: Long) : AuthEvent {
        override fun toString(): String = "QrChallengeRefreshed(***)"
    }
    data object QrApproved : AuthEvent
    data class LoginSucceeded(val identity: AccountIdentity) : AuthEvent
    data class LoginFailed(val error: AuthError) : AuthEvent
    data object LoginCancelled : AuthEvent
    data class SessionRejected(val reason: ReauthReason) : AuthEvent
    data object LoggedOut : AuthEvent
}

/**
 * Pure transition function. Events that are not valid in the current state are ignored and the
 * state is returned unchanged; [isValidTransition] lets callers log such events.
 */
object AuthStateMachine {

    fun reduce(state: AuthState, event: AuthEvent): AuthState = when (event) {
        is AuthEvent.StoredCredentialsFound ->
            if (state is AuthState.Unknown) AuthState.Authenticated(event.identity) else state

        AuthEvent.NoStoredCredentials ->
            if (state is AuthState.Unknown) AuthState.LoggedOut else state

        AuthEvent.StoredCredentialsUnreadable ->
            if (state is AuthState.Unknown) {
                AuthState.ReauthenticationRequired(ReauthReason.LocalCredentialsUnavailable)
            } else {
                state
            }

        AuthEvent.QrLoginRequested -> when (state) {
            AuthState.LoggedOut,
            is AuthState.ReauthenticationRequired,
            is AuthState.Failed,
            -> AuthState.Initializing
            // Restarting an in-progress QR flow is allowed (expired / user pressed "new code").
            is AuthState.WaitingForApproval -> AuthState.Initializing
            else -> state
        }

        is AuthEvent.QrChallengeIssued ->
            if (state is AuthState.Initializing) {
                AuthState.WaitingForApproval(event.challengeUrl, event.expiresAtElapsedMillis)
            } else {
                state
            }

        is AuthEvent.QrChallengeRefreshed ->
            if (state is AuthState.WaitingForApproval) {
                AuthState.WaitingForApproval(event.challengeUrl, event.expiresAtElapsedMillis)
            } else {
                state
            }

        AuthEvent.QrApproved ->
            if (state is AuthState.WaitingForApproval) AuthState.Authenticating else state

        is AuthEvent.LoginSucceeded ->
            if (state is AuthState.Authenticating) AuthState.Authenticated(event.identity) else state

        is AuthEvent.LoginFailed -> when (state) {
            AuthState.Initializing, is AuthState.WaitingForApproval, AuthState.Authenticating ->
                AuthState.Failed(event.error)
            else -> state
        }

        AuthEvent.LoginCancelled -> when (state) {
            AuthState.Initializing, is AuthState.WaitingForApproval, AuthState.Authenticating ->
                AuthState.LoggedOut
            else -> state
        }

        is AuthEvent.SessionRejected ->
            if (state is AuthState.Authenticated) AuthState.ReauthenticationRequired(event.reason) else state

        AuthEvent.LoggedOut -> AuthState.LoggedOut
    }

    fun isValidTransition(state: AuthState, event: AuthEvent): Boolean =
        event == AuthEvent.LoggedOut || reduce(state, event) != state ||
            (event is AuthEvent.QrChallengeRefreshed && state is AuthState.WaitingForApproval)
}
