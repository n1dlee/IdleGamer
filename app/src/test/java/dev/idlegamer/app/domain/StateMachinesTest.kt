package dev.idlegamer.app.domain

import dev.idlegamer.app.domain.model.AccountIdentity
import dev.idlegamer.app.domain.model.ActivityState
import dev.idlegamer.app.domain.model.ActivityStateMachine
import dev.idlegamer.app.domain.model.AuthError
import dev.idlegamer.app.domain.model.AuthEvent
import dev.idlegamer.app.domain.model.AuthState
import dev.idlegamer.app.domain.model.AuthStateMachine
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.ConnectionStateMachine
import dev.idlegamer.app.domain.model.ReauthReason
import dev.idlegamer.app.domain.model.RemotePresence
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.testing.TEST_STEAM_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStateMachineTest {
    private val identity = AccountIdentity(TEST_STEAM_ID)
    private val waiting = AuthState.WaitingForApproval("url", 1000)
    private val failed = AuthState.Failed(AuthError.QrExpired)
    private val reauth = AuthState.ReauthenticationRequired(ReauthReason.TokenRejected)
    private val allStates: List<AuthState> = listOf(
        AuthState.Unknown, AuthState.LoggedOut, AuthState.Initializing, waiting, AuthState.Authenticating,
        AuthState.Authenticated(identity), reauth, failed,
    )

    private fun reduce(s: AuthState, e: AuthEvent) = AuthStateMachine.reduce(s, e)

    @Test fun `startup resolves Unknown only`() {
        assertEquals(AuthState.Authenticated(identity), reduce(AuthState.Unknown, AuthEvent.StoredCredentialsFound(identity)))
        assertEquals(AuthState.LoggedOut, reduce(AuthState.Unknown, AuthEvent.NoStoredCredentials))
        assertEquals(
            AuthState.ReauthenticationRequired(ReauthReason.LocalCredentialsUnavailable),
            reduce(AuthState.Unknown, AuthEvent.StoredCredentialsUnreadable),
        )
        // Startup events never override a later state.
        allStates.filter { it != AuthState.Unknown }.forEach { s ->
            assertEquals(s, reduce(s, AuthEvent.NoStoredCredentials))
            assertEquals(s, reduce(s, AuthEvent.StoredCredentialsFound(identity)))
        }
    }

    @Test fun `happy QR path`() {
        var s: AuthState = AuthState.LoggedOut
        s = reduce(s, AuthEvent.QrLoginRequested); assertEquals(AuthState.Initializing, s)
        s = reduce(s, AuthEvent.QrChallengeIssued("a", 10)); assertEquals(AuthState.WaitingForApproval("a", 10), s)
        s = reduce(s, AuthEvent.QrChallengeRefreshed("b", 10)); assertEquals(AuthState.WaitingForApproval("b", 10), s)
        s = reduce(s, AuthEvent.QrApproved); assertEquals(AuthState.Authenticating, s)
        s = reduce(s, AuthEvent.LoginSucceeded(identity)); assertEquals(AuthState.Authenticated(identity), s)
    }

    @Test fun `login can be requested from every signed-out state but not while authenticated`() {
        listOf(AuthState.LoggedOut, reauth, failed, waiting).forEach { assertEquals(AuthState.Initializing, reduce(it, AuthEvent.QrLoginRequested)) }
        listOf(AuthState.Authenticated(identity), AuthState.Authenticating, AuthState.Unknown).forEach {
            assertEquals(it, reduce(it, AuthEvent.QrLoginRequested))
        }
    }

    @Test fun `cancel and failure only apply to in-progress login`() {
        listOf(AuthState.Initializing, waiting, AuthState.Authenticating).forEach {
            assertEquals(AuthState.LoggedOut, reduce(it, AuthEvent.LoginCancelled))
            assertEquals(AuthState.Failed(AuthError.StorageFailure), reduce(it, AuthEvent.LoginFailed(AuthError.StorageFailure)))
        }
        val authed = AuthState.Authenticated(identity)
        assertEquals(authed, reduce(authed, AuthEvent.LoginCancelled))
        assertEquals(authed, reduce(authed, AuthEvent.LoginFailed(AuthError.QrExpired)))
    }

    @Test fun `out of order events are ignored`() {
        assertEquals(AuthState.LoggedOut, reduce(AuthState.LoggedOut, AuthEvent.QrApproved))
        assertEquals(AuthState.LoggedOut, reduce(AuthState.LoggedOut, AuthEvent.QrChallengeIssued("x", 1)))
        assertEquals(AuthState.Initializing, reduce(AuthState.Initializing, AuthEvent.LoginSucceeded(identity)))
        assertEquals(waiting, reduce(waiting, AuthEvent.LoginSucceeded(identity)))
    }

    @Test fun `session rejection only from authenticated`() {
        assertEquals(reauth, reduce(AuthState.Authenticated(identity), AuthEvent.SessionRejected(ReauthReason.TokenRejected)))
        assertEquals(AuthState.LoggedOut, reduce(AuthState.LoggedOut, AuthEvent.SessionRejected(ReauthReason.TokenRejected)))
    }

    @Test fun `logout always ends in LoggedOut`() {
        allStates.forEach { assertEquals(AuthState.LoggedOut, reduce(it, AuthEvent.LoggedOut)) }
    }

    @Test fun `QR url is never printed`() {
        assertFalse(AuthState.WaitingForApproval("https://s.team/q/1/secret", 1).toString().contains("s.team"))
        assertFalse(AuthEvent.QrChallengeIssued("https://s.team/q/1/secret", 1).toString().contains("s.team"))
    }
}

class ConnectionStateMachineTest {
    private val authed = ConnectionState.Authenticated(TEST_STEAM_ID, 0)
    private val reconnecting = ConnectionState.Reconnecting(1, 0, SteamError.Timeout, false)

    @Test fun `normal lifecycle is allowed`() {
        val path = listOf(
            ConnectionState.Disconnected, ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.Authenticating,
            authed, reconnecting, ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.Authenticating, authed,
            ConnectionState.Stopping, ConnectionState.Disconnected,
        )
        path.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", ConnectionStateMachine.canTransition(a, b)) }
    }

    @Test fun `transport connection is not authentication`() {
        assertFalse(ConnectionStateMachine.canTransition(ConnectionState.Disconnected, authed))
        assertFalse(ConnectionStateMachine.canTransition(ConnectionState.Connecting, authed))
        assertFalse(ConnectionStateMachine.canTransition(ConnectionState.Connected, authed))
    }

    @Test fun `auth expired cannot silently reconnect`() {
        assertFalse(ConnectionStateMachine.canTransition(ConnectionState.AuthExpired, ConnectionState.Connecting))
        assertFalse(ConnectionStateMachine.canTransition(ConnectionState.AuthExpired, reconnecting))
    }
}

class ActivityStateMachineTest {
    private val active = ActivityState.Active(listOf(440), 1, 0, 0, 0, RemotePresence.Unconfirmed)

    @Test fun `start stop lifecycle`() {
        val path = listOf(
            ActivityState.Idle, ActivityState.Starting(listOf(440)), active,
            ActivityState.Reconnecting(listOf(440), 1, 0, 0), active, ActivityState.Stopping, ActivityState.Stopped(null),
            ActivityState.Starting(listOf(570)),
        )
        path.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", ActivityStateMachine.canTransition(a, b)) }
    }

    @Test fun `cannot jump to active without starting`() {
        assertFalse(ActivityStateMachine.canTransition(ActivityState.Idle, active))
        assertFalse(ActivityStateMachine.canTransition(ActivityState.Stopped(null), active))
        assertFalse(ActivityStateMachine.canTransition(ActivityState.Stopping, active))
    }

    @Test fun `open session flag`() {
        assertTrue(active.hasOpenSession)
        assertFalse(ActivityState.Idle.hasOpenSession)
        assertFalse(ActivityState.Stopped(null).hasOpenSession)
        assertFalse(ActivityState.Stopping.hasOpenSession)
    }
}
