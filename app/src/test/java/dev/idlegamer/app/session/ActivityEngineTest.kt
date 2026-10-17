package dev.idlegamer.app.session

import dev.idlegamer.app.data.steam.LogOnResult
import dev.idlegamer.app.data.steam.SteamEvent
import dev.idlegamer.app.domain.model.ActivityError
import dev.idlegamer.app.domain.model.ActivityState
import dev.idlegamer.app.domain.model.RemotePresence
import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.model.SessionEndReason
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.testing.FakeNetworkMonitor
import dev.idlegamer.app.testing.FakeSteamGateway
import dev.idlegamer.app.testing.InMemoryCredentialStore
import dev.idlegamer.app.testing.InMemoryHistory
import dev.idlegamer.app.testing.RecordingLogger
import dev.idlegamer.app.testing.TEST_STEAM_ID
import dev.idlegamer.app.testing.TestClock
import dev.idlegamer.app.testing.fakeJwt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityEngineTest {
    private val gateway = FakeSteamGateway()
    private val store = InMemoryCredentialStore().apply { stored = SessionCredentials(TEST_STEAM_ID, "acc", fakeJwt(TEST_STEAM_ID), 1) }
    private val network = FakeNetworkMonitor()
    private val history = InMemoryHistory()
    private val logger = RecordingLogger()
    private var riskAck = true

    private suspend fun TestScope.setup(autoStopHours: Int = 0): Pair<SteamSessionManager, ActivityEngine> {
        val clock = TestClock(this)
        val session = SteamSessionManager(
            gateway, store, network, clock, logger, backgroundScope, "test", { 7 }, { }, ReconnectPolicy(random = Random(3)),
        )
        session.initialize()
        val engine = ActivityEngine(session, gateway.events, history, clock, logger, backgroundScope, { riskAck }, { autoStopHours })
        return session to engine
    }

    private fun closedRows() = history.rows.value.values.filter { it.endedAtEpochMillis != null }

    @Test fun `validation errors`() = runTest {
        val (_, engine) = setup()
        assertEquals(ActivityError.NothingSelected, engine.start(emptyList()))
        assertEquals(ActivityError.TooManyGames(32), engine.start((1..33).toList()))
        assertEquals(ActivityError.InvalidAppId(0), engine.start(listOf(0)))
        assertEquals(ActivityError.InvalidAppId(16_777_216), engine.start(listOf(16_777_216)))
        riskAck = false
        assertEquals(ActivityError.RiskNotAcknowledged, engine.start(listOf(440)))
        assertEquals(ActivityState.Idle, engine.state.value)
        assertTrue(history.rows.value.isEmpty())
    }

    @Test fun `requires sign in`() = runTest {
        store.stored = null
        val (_, engine) = setup()
        assertEquals(ActivityError.NotSignedIn, engine.start(listOf(440)))
    }

    @Test fun `start sends presence and stop clears it with exact monotonic duration`() = runTest {
        val (_, engine) = setup()
        assertNull(engine.start(listOf(440, 570)))
        runCurrent()
        val active = engine.state.value as ActivityState.Active
        assertEquals(listOf(440, 570), active.appIds)
        assertEquals(listOf(440, 570), gateway.presenceHistory.last())
        assertEquals(RemotePresence.Unconfirmed, active.remote)

        advanceTimeBy(600_000)
        engine.stop(SessionEndReason.USER_STOPPED_UI)
        assertEquals(emptyList<Int>(), gateway.presenceHistory.last())
        val row = closedRows().single()
        assertEquals(600_000L, row.activeMillis)
        assertEquals(SessionEndReason.USER_STOPPED_UI, row.endReason)
        assertTrue(engine.state.value is ActivityState.Stopped)
    }

    @Test fun `stop is idempotent`() = runTest {
        val (_, engine) = setup()
        engine.stop(SessionEndReason.USER_STOPPED_UI)
        assertEquals(ActivityState.Idle, engine.state.value)
        engine.start(listOf(440))
        runCurrent()
        engine.stop(SessionEndReason.USER_STOPPED_UI)
        engine.stop(SessionEndReason.USER_STOPPED_NOTIFICATION)
        assertEquals(1, closedRows().size)
        assertEquals(SessionEndReason.USER_STOPPED_UI, closedRows().single().endReason)
    }

    @Test fun `time is not counted while disconnected`() = runTest {
        val (_, engine) = setup()
        engine.start(listOf(440))
        runCurrent()
        advanceTimeBy(600_000)
        network.state.value = false
        gateway.dropConnection()
        runCurrent()
        assertTrue(engine.state.value is ActivityState.Reconnecting)
        advanceTimeBy(300_000)
        network.state.value = true
        runCurrent()
        assertTrue(engine.state.value is ActivityState.Active)
        assertEquals(listOf(440), gateway.presenceHistory.last())
        advanceTimeBy(600_000)
        engine.stop(SessionEndReason.USER_STOPPED_UI)
        assertEquals(1_200_000L, closedRows().single().activeMillis)
    }

    @Test fun `playing on another device pauses presence without counting time`() = runTest {
        val (_, engine) = setup()
        engine.start(listOf(440))
        runCurrent()
        advanceTimeBy(60_000)
        gateway.eventFlow.emit(SteamEvent.PlayingSessionState(playingBlocked = true, playingAppId = 570))
        runCurrent()
        val error = engine.state.value as ActivityState.Error
        assertEquals(ActivityError.Steam(SteamError.PlayingBlocked(570)), error.error)
        advanceTimeBy(120_000)
        gateway.eventFlow.emit(SteamEvent.PlayingSessionState(playingBlocked = false, playingAppId = 0))
        runCurrent()
        assertTrue(engine.state.value is ActivityState.Active)
        advanceTimeBy(60_000)
        engine.stop(SessionEndReason.USER_STOPPED_UI)
        assertEquals(120_000L, closedRows().single().activeMillis)
    }

    @Test fun `lost credentials end the session as auth lost`() = runTest {
        val (_, engine) = setup()
        engine.start(listOf(440))
        runCurrent()
        gateway.defaultLogOn = LogOnResult.Failure(SteamError.InvalidCredentials(26))
        gateway.dropConnection()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(engine.state.value is ActivityState.Stopped)
        assertEquals(SessionEndReason.AUTH_LOST, closedRows().single().endReason)
    }

    @Test fun `presence failure is reported, not hidden`() = runTest {
        val (_, engine) = setup()
        gateway.presenceFailure = SteamError.ConnectionReset
        engine.start(listOf(440))
        runCurrent()
        assertEquals(ActivityError.Steam(SteamError.ConnectionReset), (engine.state.value as ActivityState.Error).error)
        engine.stop(SessionEndReason.USER_STOPPED_UI)
        assertEquals(0L, closedRows().single().activeMillis)
    }

    @Test fun `persona echo marks remote confirmation`() = runTest {
        val (_, engine) = setup()
        engine.start(listOf(440))
        runCurrent()
        gateway.eventFlow.emit(SteamEvent.PersonaUpdate(TEST_STEAM_ID, "me", null, 440))
        runCurrent()
        assertTrue((engine.state.value as ActivityState.Active).remote is RemotePresence.EchoedByPersonaUpdate)
    }

    @Test fun `checkpoints are written every minute`() = runTest {
        val (_, engine) = setup()
        engine.start(listOf(440))
        runCurrent()
        advanceTimeBy(180_001)
        assertEquals(listOf(60_000L, 120_000L, 180_000L), history.checkpoints.map { it.second })
        engine.stop(SessionEndReason.USER_STOPPED_UI)
    }

    @Test fun `interrupted sessions are closed at startup`() = runTest {
        val id = history.openSession(listOf(440), 1)
        history.checkpoint(id, 120_000, 2)
        val (_, engine) = setup()
        engine.recoverInterruptedSessions()
        val row = history.rows.value.getValue(id)
        assertEquals(SessionEndReason.INTERRUPTED, row.endReason)
        assertEquals(120_000L, row.activeMillis)
    }

    @Test fun `concurrent start and stop leave a consistent state`() = runTest {
        val (_, engine) = setup()
        repeat(20) {
            launch { engine.start(listOf(440)) }
            launch { engine.stop(SessionEndReason.USER_STOPPED_UI) }
            runCurrent()
        }
        engine.stop(SessionEndReason.USER_STOPPED_UI)
        assertTrue(engine.state.value is ActivityState.Stopped)
        assertTrue(history.rows.value.values.all { it.endedAtEpochMillis != null })
        assertEquals(emptyList<Int>(), gateway.presenceHistory.last())
    }

    @Test fun `switching games closes the previous session`() = runTest {
        val (_, engine) = setup()
        engine.start(listOf(440))
        runCurrent()
        advanceTimeBy(1_000)
        engine.start(listOf(570))
        runCurrent()
        assertEquals(listOf(570), (engine.state.value as ActivityState.Active).appIds)
        assertEquals(1, closedRows().size)
        assertEquals(2, history.rows.value.size)
    }

    @Test fun `auto stop limit ends the session`() = runTest {
        val (_, engine) = setup(autoStopHours = 2)
        engine.start(listOf(440))
        runCurrent()
        advanceTimeBy(2 * 3_600_000L + 1)
        runCurrent()
        assertEquals(SessionEndReason.AUTO_STOP_LIMIT, closedRows().single().endReason)
    }
}
