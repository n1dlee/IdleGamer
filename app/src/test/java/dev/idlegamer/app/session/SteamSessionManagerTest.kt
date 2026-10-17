package dev.idlegamer.app.session

import dev.idlegamer.app.data.steam.LogOnResult
import dev.idlegamer.app.data.steam.QrPollResult
import dev.idlegamer.app.domain.model.AuthError
import dev.idlegamer.app.domain.model.AuthState
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.ReauthReason
import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.repository.CredentialStoreException
import dev.idlegamer.app.testing.FakeNetworkMonitor
import dev.idlegamer.app.testing.FakeSteamGateway
import dev.idlegamer.app.testing.InMemoryCredentialStore
import dev.idlegamer.app.testing.OTHER_STEAM_ID
import dev.idlegamer.app.testing.RecordingLogger
import dev.idlegamer.app.testing.TEST_STEAM_ID
import dev.idlegamer.app.testing.TestClock
import dev.idlegamer.app.testing.fakeJwt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class SteamSessionManagerTest {
    private val gateway = FakeSteamGateway()
    private val store = InMemoryCredentialStore()
    private val network = FakeNetworkMonitor()
    private val logger = RecordingLogger()
    private val storedCreds = SessionCredentials(TEST_STEAM_ID, "acc", fakeJwt(TEST_STEAM_ID), 1)
    private val signedIn = mutableListOf<Long>()

    private fun TestScope.manager(policy: ReconnectPolicy = ReconnectPolicy(random = Random(1))): SteamSessionManager {
        gateway.timeSource = { testScheduler.currentTime }
        return SteamSessionManager(
            gateway = gateway,
            credentialStore = store,
            network = network,
            clock = TestClock(this),
            logger = logger,
            scope = backgroundScope,
            deviceFriendlyName = "IdleGamer (test)",
            loginId = { 7 },
            onAccountSignedIn = { signedIn += it.raw },
            policy = policy,
        )
    }

    // region startup

    @Test fun `no stored credentials means logged out and no connection`() = runTest {
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        assertEquals(AuthState.LoggedOut, m.authState.value)
        assertEquals(0, gateway.connectCalls.value)
    }

    @Test fun `unreadable vault is wiped and requires reauthentication`() = runTest {
        store.stored = storedCreds
        store.failLoadWith = CredentialStoreException.Kind.KEY_UNAVAILABLE
        val m = manager()
        m.initialize()
        assertEquals(AuthState.ReauthenticationRequired(ReauthReason.LocalCredentialsUnavailable), m.authState.value)
        assertEquals(1, store.clearCount)
    }

    // endregion

    // region QR login

    @Test fun `qr login happy path stores credentials only after verified logon`() = runTest {
        gateway.qrPolls = ArrayDeque(listOf(QrPollResult.Pending, QrPollResult.Pending, QrPollResult.Approved("acc", fakeJwt(TEST_STEAM_ID))))
        val m = manager()
        m.initialize()
        m.startQrLogin()
        runCurrent()
        assertTrue(m.authState.value is AuthState.WaitingForApproval)
        assertNull(store.stored)

        advanceTimeBy(15_001)
        runCurrent()
        assertTrue(m.authState.value is AuthState.Authenticated)
        assertEquals(TEST_STEAM_ID, store.stored?.steamId)
        assertTrue(m.connectionState.value is ConnectionState.Authenticated)
        assertEquals(listOf(TEST_STEAM_ID.raw), signedIn)
        assertEquals(0, m.invalidTransitionCount.get())
    }

    @Test fun `qr polling honours the interval in seconds`() = runTest {
        gateway.qrIntervalMillis = 5_000
        val m = manager()
        m.initialize()
        m.startQrLogin()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue("polled ${gateway.qrPollCalls} times in 60s", gateway.qrPollCalls in 11..12)
        gateway.qrPollTimes.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 5_000) }
        m.cancelQrLogin()
    }

    @Test fun `expired qr fails and disconnects`() = runTest {
        gateway.qrPolls = ArrayDeque(listOf(QrPollResult.Pending, QrPollResult.Expired))
        val m = manager()
        m.initialize()
        m.startQrLogin()
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.QrExpired), m.authState.value)
        assertEquals(ConnectionState.Disconnected, m.connectionState.value)
        assertNull(store.stored)
    }

    @Test fun `qr login gives up when the lifetime is exceeded`() = runTest {
        val m = manager()
        m.initialize()
        m.startQrLogin()
        advanceTimeBy(6 * 60_000)
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.QrExpired), m.authState.value)
    }

    @Test fun `token for another account is rejected`() = runTest {
        gateway.qrPolls = ArrayDeque(listOf(QrPollResult.Approved("acc", fakeJwt(OTHER_STEAM_ID))))
        val m = manager()
        m.initialize()
        m.startQrLogin()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.Steam(SteamError.IdentityMismatch)), m.authState.value)
        assertNull(store.stored)
        assertTrue(gateway.logOffCalls >= 1)
    }

    @Test fun `vault failure aborts login without keeping a session`() = runTest {
        gateway.qrPolls = ArrayDeque(listOf(QrPollResult.Approved("acc", fakeJwt(TEST_STEAM_ID))))
        store.failSaveWith = CredentialStoreException.Kind.IO
        val m = manager()
        m.initialize()
        m.startQrLogin()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.StorageFailure), m.authState.value)
        assertEquals(ConnectionState.Disconnected, m.connectionState.value)
    }

    @Test fun `cancel returns to logged out`() = runTest {
        val m = manager()
        m.initialize()
        m.startQrLogin()
        runCurrent()
        m.cancelQrLogin()
        runCurrent()
        assertEquals(AuthState.LoggedOut, m.authState.value)
        assertEquals(ConnectionState.Disconnected, m.connectionState.value)
    }

    @Test fun `starting a second login cancels the first`() = runTest {
        val m = manager()
        m.initialize()
        m.startQrLogin()
        runCurrent()
        m.startQrLogin()
        runCurrent()
        assertTrue(m.authState.value is AuthState.WaitingForApproval)
        advanceTimeBy(5_001)
        runCurrent()
        // Only one poll loop is alive.
        assertEquals(1, gateway.qrPollCalls)
        m.cancelQrLogin()
    }

    @Test fun `offline login fails fast`() = runTest {
        network.state.value = false
        val m = manager()
        m.initialize()
        m.startQrLogin()
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.Steam(SteamError.NetworkUnavailable)), m.authState.value)
        assertEquals(0, gateway.connectCalls.value)
    }

    // endregion

    // region session restore and reconnect

    @Test fun `stored session connects when demanded`() = runTest {
        store.stored = storedCreds
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        assertTrue(m.connectionState.value is ConnectionState.Authenticated)
        assertEquals(listOf(storedCreds.refreshToken.reveal()), gateway.loggedOnTokens)
    }

    @Test fun `invalid token stops retrying and requires reauthentication`() = runTest {
        store.stored = storedCreds
        gateway.defaultLogOn = LogOnResult.Failure(SteamError.InvalidCredentials(5))
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        assertEquals(AuthState.ReauthenticationRequired(ReauthReason.TokenRejected), m.authState.value)
        assertEquals(ConnectionState.AuthExpired, m.connectionState.value)
        assertNull(store.stored)
        advanceTimeBy(3_600_000)
        assertEquals(1, gateway.logOnCalls)
    }

    @Test fun `revoked token maps to session revoked`() = runTest {
        store.stored = storedCreds
        gateway.defaultLogOn = LogOnResult.Failure(SteamError.InvalidCredentials(26))
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        assertEquals(AuthState.ReauthenticationRequired(ReauthReason.SessionRevoked), m.authState.value)
    }

    @Test fun `reconnects after an unexpected drop`() = runTest {
        store.stored = storedCreds
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        gateway.dropConnection()
        runCurrent()
        val reconnecting = m.connectionState.value as ConnectionState.Reconnecting
        assertEquals(1, reconnecting.attempt)
        advanceTimeBy(2_001)
        runCurrent()
        assertTrue(m.connectionState.value is ConnectionState.Authenticated)
        assertEquals(2, gateway.connectCalls.value)
        assertEquals(0, m.invalidTransitionCount.get())
    }

    @Test fun `waits for network instead of retrying while offline`() = runTest {
        store.stored = storedCreds
        network.state.value = false
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        assertTrue((m.connectionState.value as ConnectionState.Reconnecting).waitingForNetwork)
        advanceTimeBy(10 * 60_000)
        assertEquals(0, gateway.connectCalls.value)
        network.state.value = true
        runCurrent()
        assertTrue(m.connectionState.value is ConnectionState.Authenticated)
    }

    @Test fun `network change cuts a network backoff short`() = runTest {
        store.stored = storedCreds
        val m = manager(ReconnectPolicy(baseDelayMillis = 60_000, random = Random(1)))
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        gateway.dropConnection()
        runCurrent()
        network.state.value = false
        runCurrent()
        network.state.value = true
        runCurrent()
        assertTrue(m.connectionState.value is ConnectionState.Authenticated)
        assertEquals(2, gateway.connectCalls.value)
    }

    @Test fun `gives up after max attempts and retry restarts`() = runTest {
        store.stored = storedCreds
        gateway.connectFailures = ArrayDeque(List(10) { SteamError.Timeout })
        val m = manager(ReconnectPolicy(maxAttempts = 3, random = Random(1)))
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(ConnectionState.Failed(SteamError.Timeout), m.connectionState.value)
        assertEquals(4, gateway.connectCalls.value)
        advanceTimeBy(3_600_000)
        assertEquals(4, gateway.connectCalls.value)
        m.retryNow()
        runCurrent()
        assertEquals(5, gateway.connectCalls.value)
    }

    @Test fun `rate limiting waits at least ten minutes`() = runTest {
        store.stored = storedCreds
        gateway.logOnResults = ArrayDeque(listOf(LogOnResult.Failure(SteamError.RateLimited(84))))
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        advanceTimeBy(9 * 60_000)
        assertEquals(1, gateway.logOnCalls)
        advanceTimeBy(11 * 60_000)
        runCurrent()
        assertEquals(2, gateway.logOnCalls)
        assertTrue(m.connectionState.value is ConnectionState.Authenticated)
    }

    @Test fun `releasing the last demand disconnects after the grace period`() = runTest {
        store.stored = storedCreds
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        m.releaseDemand(SteamSessionManager.Demand.UI)
        advanceTimeBy(10_000)
        assertTrue(m.connectionState.value is ConnectionState.Authenticated)
        advanceTimeBy(25_000)
        runCurrent()
        assertEquals(ConnectionState.Disconnected, m.connectionState.value)
    }

    @Test fun `token close to expiry is renewed and persisted`() = runTest {
        val clockNowSeconds = 1_700_000_000L
        store.stored = storedCreds.copy(refreshToken = fakeJwt(TEST_STEAM_ID, expEpochSeconds = clockNowSeconds + 3600))
        val renewed = fakeJwt(TEST_STEAM_ID, expEpochSeconds = clockNowSeconds + 200L * 86400)
        gateway.renewAnswer = renewed
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        assertEquals(renewed, store.stored?.refreshToken)
    }

    // endregion

    // region logout

    @Test fun `logout revokes deletes and never reconnects`() = runTest {
        store.stored = storedCreds
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        val result = m.logout()
        runCurrent()
        assertTrue(result.revocationConfirmed)
        assertEquals(1, gateway.revokeCalls)
        assertNull(store.stored)
        assertEquals(AuthState.LoggedOut, m.authState.value)
        assertEquals(ConnectionState.Disconnected, m.connectionState.value)
        advanceTimeBy(3_600_000)
        assertEquals(1, gateway.connectCalls.value)
        assertNull(m.currentSteamId())
    }

    @Test fun `offline logout still deletes locally and reports unconfirmed revocation`() = runTest {
        store.stored = storedCreds
        network.state.value = false
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        val result = m.logout()
        assertFalse(result.revocationConfirmed)
        assertFalse(result.wasOnline)
        assertEquals(0, gateway.revokeCalls)
        assertNull(store.stored)
        assertEquals(AuthState.LoggedOut, m.authState.value)
    }

    @Test fun `revocation refused by steam is reported honestly`() = runTest {
        store.stored = storedCreds
        gateway.revokeAnswer = false
        val m = manager()
        m.initialize()
        m.requestDemand(SteamSessionManager.Demand.UI)
        runCurrent()
        val result = m.logout()
        assertFalse(result.revocationConfirmed)
        assertTrue(result.wasOnline)
        assertNull(store.stored)
    }

    // endregion

    @Test fun `logs never contain the refresh token`() = runTest {
        gateway.qrPolls = ArrayDeque(listOf(QrPollResult.Approved("acc", fakeJwt(TEST_STEAM_ID))))
        val m = manager()
        m.initialize()
        m.startQrLogin()
        advanceTimeBy(5_001)
        runCurrent()
        m.logout()
        val token = fakeJwt(TEST_STEAM_ID).reveal()
        val all = logger.lines + logger.events.map { it.second }
        assertNotNull(all.firstOrNull())
        all.forEach { line -> assertFalse(line, line.contains(token) || line.contains("eyJ") || line.contains("s.team/q")) }
    }
}
