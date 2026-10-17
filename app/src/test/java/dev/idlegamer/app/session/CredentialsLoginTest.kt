package dev.idlegamer.app.session

import dev.idlegamer.app.data.steam.GuardConfirmation
import dev.idlegamer.app.data.steam.PollResult
import dev.idlegamer.app.domain.model.AuthError
import dev.idlegamer.app.domain.model.AuthState
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.GuardCodeKind
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.testing.FakeNetworkMonitor
import dev.idlegamer.app.testing.FakeSteamGateway
import dev.idlegamer.app.testing.InMemoryCredentialStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialsLoginTest {
    private val gateway = FakeSteamGateway()
    private val store = InMemoryCredentialStore()
    private val network = FakeNetworkMonitor()
    private val logger = RecordingLogger()
    private val approved = PollResult.Approved("acc", fakeJwt(TEST_STEAM_ID))

    private suspend fun TestScope.manager(): SteamSessionManager {
        gateway.timeSource = { testScheduler.currentTime }
        return SteamSessionManager(
            gateway, store, network, TestClock(this), logger, backgroundScope, "IdleGamer (test)", { 7 }, { },
            ReconnectPolicy(random = Random(1)),
        ).also { it.initialize() }
    }

    private fun guard(m: SteamSessionManager) = m.authState.value as AuthState.AwaitingSteamGuard

    @Test fun `push approval in Steam Mobile signs in and stores only the token`() = runTest {
        gateway.credentialPolls = ArrayDeque(listOf(PollResult.Pending, approved))
        val m = manager()
        m.startCredentialsLogin("acc", "hunter2".toCharArray())
        runCurrent()
        val g = guard(m)
        assertTrue(g.mobileApproval)
        assertEquals(GuardCodeKind.MOBILE_APP, g.codeKind)
        assertNull(store.stored)

        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(m.authState.value is AuthState.Authenticated)
        assertEquals(TEST_STEAM_ID, store.stored?.steamId)
        assertEquals(listOf("hunter2"), gateway.passwordsSeenAtBegin)
        assertFalse(store.stored.toString().contains("hunter2"))
    }

    @Test fun `password arrays are wiped`() = runTest {
        val m = manager()
        val password = "hunter2".toCharArray()
        m.startCredentialsLogin("acc", password)
        // The caller's array is wiped synchronously, before anything is sent.
        assertTrue(password.all { it == '\u0000' })
        runCurrent()
        // The internal copy is wiped right after Steam received it.
        assertTrue(gateway.passwordArrayGiven!!.all { it == '\u0000' })
        m.cancelLogin()
    }

    @Test fun `wrong password is reported without retry`() = runTest {
        gateway.credentialsBeginError = SteamError.InvalidCredentials(5)
        val m = manager()
        m.startCredentialsLogin("acc", "wrong".toCharArray())
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.WrongCredentials), m.authState.value)
        assertEquals(ConnectionState.Disconnected, m.connectionState.value)
        assertNull(store.stored)
        advanceTimeBy(600_000)
        assertEquals(1, gateway.passwordsSeenAtBegin.size)
    }

    @Test fun `rate limited sign in surfaces the steam error`() = runTest {
        gateway.credentialsBeginError = SteamError.RateLimited(87)
        val m = manager()
        m.startCredentialsLogin("acc", "pw".toCharArray())
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.Steam(SteamError.RateLimited(87))), m.authState.value)
    }

    @Test fun `empty input never contacts steam`() = runTest {
        val m = manager()
        m.startCredentialsLogin("  ", "pw".toCharArray())
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.WrongCredentials), m.authState.value)
        assertEquals(0, gateway.connectCalls.value)
    }

    @Test fun `rejected code can be corrected`() = runTest {
        gateway.credentialConfirmations = setOf(GuardConfirmation.MOBILE_APP_CODE)
        gateway.approvalOnCorrectCode = approved
        val m = manager()
        m.startCredentialsLogin("acc", "pw".toCharArray())
        runCurrent()
        assertFalse(guard(m).mobileApproval)

        m.submitGuardCode("WRONG")
        runCurrent()
        assertTrue(guard(m).codeRejected)

        m.submitGuardCode(" abcde ")
        runCurrent()
        assertFalse(guard(m).codeRejected)
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(m.authState.value is AuthState.Authenticated)
        assertEquals(listOf("WRONG" to GuardConfirmation.MOBILE_APP_CODE, "ABCDE" to GuardConfirmation.MOBILE_APP_CODE), gateway.submittedCodes)
    }

    @Test fun `email guard shows the hint and sends an email code`() = runTest {
        gateway.credentialConfirmations = setOf(GuardConfirmation.EMAIL_CODE)
        gateway.credentialsEmailHint = "gm***.com"
        gateway.approvalOnCorrectCode = approved
        val m = manager()
        m.startCredentialsLogin("acc", "pw".toCharArray())
        runCurrent()
        assertEquals(GuardCodeKind.EMAIL, guard(m).codeKind)
        assertEquals("gm***.com", guard(m).emailHint)
        m.submitGuardCode("ABCDE")
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(GuardConfirmation.EMAIL_CODE, gateway.submittedCodes.single().second)
        assertTrue(m.authState.value is AuthState.Authenticated)
    }

    @Test fun `account without steam guard signs in directly`() = runTest {
        gateway.credentialConfirmations = setOf(GuardConfirmation.NONE)
        gateway.credentialPolls = ArrayDeque(listOf(approved))
        val m = manager()
        m.startCredentialsLogin("acc", "pw".toCharArray())
        runCurrent()
        assertEquals(AuthState.Initializing, m.authState.value)
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(m.authState.value is AuthState.Authenticated)
    }

    @Test fun `unknown guard method fails explicitly`() = runTest {
        gateway.credentialConfirmations = emptySet()
        val m = manager()
        m.startCredentialsLogin("acc", "pw".toCharArray())
        runCurrent()
        assertTrue((m.authState.value as AuthState.Failed).error is AuthError.Steam)
    }

    @Test fun `cancel during steam guard returns to logged out`() = runTest {
        val m = manager()
        m.startCredentialsLogin("acc", "pw".toCharArray())
        runCurrent()
        m.cancelLogin()
        runCurrent()
        assertEquals(AuthState.LoggedOut, m.authState.value)
        assertEquals(ConnectionState.Disconnected, m.connectionState.value)
    }

    @Test fun `approval wait is bounded and polls at steam's interval`() = runTest {
        val m = manager()
        m.startCredentialsLogin("acc", "pw".toCharArray())
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(gateway.credentialPollCalls in 11..12)
        advanceTimeBy(5 * 60_000)
        runCurrent()
        assertEquals(AuthState.Failed(AuthError.ApprovalExpired), m.authState.value)
    }

    @Test fun `codes outside a login are ignored`() = runTest {
        val m = manager()
        m.submitGuardCode("ABCDE")
        runCurrent()
        assertEquals(AuthState.LoggedOut, m.authState.value)
        assertTrue(gateway.submittedCodes.isEmpty())
    }

    @Test fun `logs never contain the password or the code`() = runTest {
        gateway.credentialConfirmations = setOf(GuardConfirmation.MOBILE_APP_CODE)
        gateway.approvalOnCorrectCode = approved
        val m = manager()
        m.startCredentialsLogin("acc", "hunter2-Secret".toCharArray())
        runCurrent()
        m.submitGuardCode("ABCDE")
        advanceTimeBy(5_001)
        runCurrent()
        m.logout()
        val all = logger.lines + logger.events.map { "${it.first} ${it.second}" }
        assertTrue(all.isNotEmpty())
        all.forEach { line ->
            assertFalse(line, line.contains("hunter2") || line.contains("ABCDE") || line.contains("eyJ"))
        }
    }
}
