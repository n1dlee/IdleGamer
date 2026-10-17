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

    /**
     * Credentials were accepted; Steam Guard must confirm the sign-in. Either the user approves the push request in
     * Steam Mobile ([mobileApproval]) or types a code ([codeKind]). Nothing secret is held in this state.
     */
    data class AwaitingSteamGuard(
        val mobileApproval: Boolean,
        val codeKind: GuardCodeKind?,
        val emailHint: String?,
        val expiresAtElapsedMillis: Long,
        val codeRejected: Boolean = false,
        val submittingCode: Boolean = false,
    ) : AuthState

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

enum class GuardCodeKind { MOBILE_APP, EMAIL }

sealed interface AuthError {
    /** BeginAuthSessionViaCredentials rejected the account name or password. */
    data object WrongCredentials : AuthError
    data object QrExpired : AuthError

    /** The Steam Guard confirmation of a credentials login was not given in time. */
    data object ApprovalExpired : AuthError
    data object PollingLimitReached : AuthError
    data class Steam(val error: SteamError) : AuthError
    data object StorageFailure : AuthError
}

sealed interface AuthEvent {
    data class StoredCredentialsFound(val identity: AccountIdentity) : AuthEvent
    data object NoStoredCredentials : AuthEvent
    data object StoredCredentialsUnreadable : AuthEvent
    data object LoginRequested : AuthEvent
    data class QrChallengeIssued(val challengeUrl: String, val expiresAtElapsedMillis: Long) : AuthEvent {
        override fun toString(): String = "QrChallengeIssued(***)"
    }
    data class QrChallengeRefreshed(val challengeUrl: String, val expiresAtElapsedMillis: Long) : AuthEvent {
        override fun toString(): String = "QrChallengeRefreshed(***)"
    }
    data class SteamGuardRequired(
        val mobileApproval: Boolean,
        val codeKind: GuardCodeKind?,
        val emailHint: String?,
        val expiresAtElapsedMillis: Long,
    ) : AuthEvent
    data object GuardCodeSubmitted : AuthEvent
    data object GuardCodeRejected : AuthEvent
    data object GuardCodeAccepted : AuthEvent
    data object LoginApproved : AuthEvent
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

        AuthEvent.LoginRequested -> when (state) {
            AuthState.LoggedOut,
            is AuthState.ReauthenticationRequired,
            is AuthState.Failed,
            -> AuthState.Initializing
            // Restarting an in-progress flow is allowed (expired / "new code" / switching method).
            is AuthState.WaitingForApproval, is AuthState.AwaitingSteamGuard -> AuthState.Initializing
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

        is AuthEvent.SteamGuardRequired ->
            if (state is AuthState.Initializing) {
                AuthState.AwaitingSteamGuard(event.mobileApproval, event.codeKind, event.emailHint, event.expiresAtElapsedMillis)
            } else {
                state
            }

        AuthEvent.GuardCodeSubmitted ->
            if (state is AuthState.AwaitingSteamGuard && state.codeKind != null) {
                state.copy(submittingCode = true, codeRejected = false)
            } else {
                state
            }

        AuthEvent.GuardCodeRejected ->
            if (state is AuthState.AwaitingSteamGuard) state.copy(submittingCode = false, codeRejected = true) else state

        AuthEvent.GuardCodeAccepted ->
            if (state is AuthState.AwaitingSteamGuard) state.copy(submittingCode = false) else state

        AuthEvent.LoginApproved ->
            // Initializing: a credentials login for an account without Steam Guard is approved without a prompt.
            if (state is AuthState.Initializing || state is AuthState.WaitingForApproval || state is AuthState.AwaitingSteamGuard) {
                AuthState.Authenticating
            } else {
                state
            }

        is AuthEvent.LoginSucceeded ->
            if (state is AuthState.Authenticating) AuthState.Authenticated(event.identity) else state

        is AuthEvent.LoginFailed -> when (state) {
            AuthState.Initializing, is AuthState.WaitingForApproval, is AuthState.AwaitingSteamGuard, AuthState.Authenticating ->
                AuthState.Failed(event.error)
            else -> state
        }

        AuthEvent.LoginCancelled -> when (state) {
            AuthState.Initializing, is AuthState.WaitingForApproval, is AuthState.AwaitingSteamGuard, AuthState.Authenticating ->
                AuthState.LoggedOut
            else -> state
        }

        is AuthEvent.SessionRejected ->
            if (state is AuthState.Authenticated) AuthState.ReauthenticationRequired(event.reason) else state

        AuthEvent.LoggedOut -> AuthState.LoggedOut
    }

    fun isValidTransition(state: AuthState, event: AuthEvent): Boolean =
        event == AuthEvent.LoggedOut || reduce(state, event) != state ||
            (event is AuthEvent.QrChallengeRefreshed && state is AuthState.WaitingForApproval) ||
            (event == AuthEvent.GuardCodeAccepted && state is AuthState.AwaitingSteamGuard)
}
