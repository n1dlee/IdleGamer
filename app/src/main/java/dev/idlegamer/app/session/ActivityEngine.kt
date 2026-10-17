package dev.idlegamer.app.session

import dev.idlegamer.app.core.logging.AppEvent
import dev.idlegamer.app.core.logging.AppLogger
import dev.idlegamer.app.core.time.Clock
import dev.idlegamer.app.data.steam.SteamEvent
import dev.idlegamer.app.domain.model.ActivityError
import dev.idlegamer.app.domain.model.ActivityState
import dev.idlegamer.app.domain.model.ActivityStateMachine
import dev.idlegamer.app.domain.model.AuthState
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.Game
import dev.idlegamer.app.domain.model.RemotePresence
import dev.idlegamer.app.domain.model.SessionEndReason
import dev.idlegamer.app.domain.model.SessionSummary
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.model.SteamException
import dev.idlegamer.app.domain.repository.ActivityHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * User-controlled presence engine (Phase 8). Starts and stops `ClientGamesPlayed` for the games the user
 * picked, keeps an honest local record, and never claims Steam counted any time.
 *
 * Time accounting uses the monotonic clock and only accrues while the state is [ActivityState.Active].
 * A checkpoint is written every [checkpointIntervalMillis]; after process death the session is closed
 * with the checkpointed value, so at most one interval of real activity is lost and none is invented.
 */
class ActivityEngine(
    private val session: SteamSessionManager,
    private val steamEvents: Flow<SteamEvent>,
    private val history: ActivityHistoryRepository,
    private val clock: Clock,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val riskAcknowledged: suspend () -> Boolean,
    private val autoStopAfterHours: suspend () -> Int,
    private val checkpointIntervalMillis: Long = 60_000,
) {
    private val _state = MutableStateFlow<ActivityState>(ActivityState.Idle)
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var runJob: Job? = null
    private var autoStopJob: Job? = null
    private val meter = ActiveTimeMeter()
    private var openSessionId: Long? = null
    private var openAppIds: List<Int> = emptyList()
    private var sessionStartedElapsed = 0L

    /** Closes sessions left open by a dead process. Call once at startup, before [start] can be used. */
    suspend fun recoverInterruptedSessions() = mutex.withLock {
        val closed = history.closeInterruptedSessions(clock.currentTimeMillis())
        if (closed > 0) logger.event(AppEvent.INTERRUPTED_SESSIONS_RECOVERED, "$closed session(s) closed as INTERRUPTED")
    }

    fun validate(appIds: List<Int>): ActivityError? = when {
        appIds.isEmpty() -> ActivityError.NothingSelected
        appIds.size > Game.MAX_CONCURRENT_GAMES -> ActivityError.TooManyGames(Game.MAX_CONCURRENT_GAMES)
        else -> appIds.firstOrNull { it < 1 || it > Game.MAX_APP_ID }?.let { ActivityError.InvalidAppId(it.toLong()) }
    }

    /** @return null on success, or why the session could not be started. */
    suspend fun start(appIds: List<Int>): ActivityError? = mutex.withLock {
        val ids = appIds.distinct()
        validate(ids)?.let { return@withLock it }
        if (session.authState.value !is AuthState.Authenticated) return@withLock ActivityError.NotSignedIn
        if (!riskAcknowledged()) return@withLock ActivityError.RiskNotAcknowledged
        if (_state.value.hasOpenSession) {
            if (ids == openAppIds) return@withLock null
            finishLocked(SessionEndReason.USER_STOPPED_UI)
        }

        logger.event(AppEvent.START_REQUESTED, "apps=${ids.joinToString(",")}")
        transition(ActivityState.Starting(ids))
        val sessionId = history.openSession(ids, clock.currentTimeMillis())
        openSessionId = sessionId
        openAppIds = ids
        sessionStartedElapsed = clock.elapsedRealtimeMillis()
        meter.reset()
        session.requestDemand(SteamSessionManager.Demand.ACTIVITY)
        runJob = scope.launch { runSession(sessionId, ids) }
        autoStopJob = autoStopAfterHours().takeIf { it > 0 }?.let { hours ->
            scope.launch {
                delay(hours * 3_600_000L)
                stop(SessionEndReason.AUTO_STOP_LIMIT)
            }
        }
        null
    }

    /** Idempotent: stopping when nothing runs is a no-op. */
    suspend fun stop(reason: SessionEndReason) {
        mutex.withLock { finishLocked(reason) }
    }

    private suspend fun finishLocked(reason: SessionEndReason) {
        val current = _state.value
        if (!current.hasOpenSession) {
            if (current is ActivityState.Error) transition(ActivityState.Stopping).also { transition(ActivityState.Stopped(null)) }
            return
        }
        logger.event(AppEvent.STOP_REQUESTED, "reason=$reason")
        transition(ActivityState.Stopping)
        withContext(NonCancellable) {
            autoStopJob?.cancel()
            runJob?.cancelAndJoin()
            runJob = null
            val activeMillis = meter.pause(clock.elapsedRealtimeMillis())
            if (session.connectionState.value is ConnectionState.Authenticated) {
                withTimeoutOrNull(5_000) { runCatching { session.sendPresence(emptyList()) } }
            }
            val id = openSessionId
            if (id != null) {
                runCatching { history.closeSession(id, activeMillis, clock.currentTimeMillis(), reason) }
                    .onFailure { logger.error(TAG, "could not close session record", it) }
            }
            session.releaseDemand(SteamSessionManager.Demand.ACTIVITY)
            val summary = id?.let { SessionSummary(it, openAppIds, activeMillis, reason) }
            openSessionId = null
            openAppIds = emptyList()
            transition(ActivityState.Stopped(summary))
            logger.event(AppEvent.ACTIVITY_STATE, "stopped after ${activeMillis / 1000}s active")
        }
    }

    private suspend fun runSession(sessionId: Long, appIds: List<Int>) = coroutineScope {
        val remote = AtomicReference<RemotePresence>(RemotePresence.Unconfirmed)

        launch {
            while (true) {
                delay(checkpointIntervalMillis)
                runCatching { history.checkpoint(sessionId, meter.total(clock.elapsedRealtimeMillis()), clock.currentTimeMillis()) }
            }
        }

        val playingState = MutableStateFlow<SteamEvent.PlayingSessionState?>(null)
        launch {
            steamEvents.collect { event ->
                when (event) {
                    is SteamEvent.PlayingSessionState -> playingState.value = event
                    is SteamEvent.PersonaUpdate -> {
                        val self = session.currentSteamId()
                        val current = _state.value
                        if (self != null && event.steamId == self && event.playingAppId in appIds && current is ActivityState.Active) {
                            val echoed = RemotePresence.EchoedByPersonaUpdate(event.playingAppId, clock.currentTimeMillis())
                            remote.set(echoed)
                            transition(current.copy(remote = echoed))
                        }
                    }
                    else -> Unit
                }
            }
        }

        combine(session.connectionState, playingState) { c, p -> c to p }
            .distinctUntilChanged()
            .collect { (connection, playing) ->
                val now = clock.elapsedRealtimeMillis()
                val blockedBy = playing?.takeIf { it.playingBlocked }?.playingAppId
                when {
                    connection is ConnectionState.Authenticated && blockedBy == null -> {
                        try {
                            session.sendPresence(appIds)
                            meter.resume(now)
                            transition(
                                ActivityState.Active(
                                    appIds = appIds,
                                    sessionId = sessionId,
                                    sessionStartedElapsedMillis = sessionStartedElapsed,
                                    activeSinceElapsedMillis = now,
                                    accumulatedActiveMillis = meter.total(now),
                                    remote = remote.get(),
                                ),
                            )
                            logger.event(AppEvent.ACTIVITY_STATE, "presence sent for ${appIds.size} app(s)")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: SteamException) {
                            meter.pause(now)
                            transition(errorState(ActivityError.Steam(e.error), sessionId, appIds, now))
                        }
                    }
                    connection is ConnectionState.Authenticated -> {
                        meter.pause(now)
                        transition(errorState(ActivityError.Steam(SteamError.PlayingBlocked(blockedBy ?: 0)), sessionId, appIds, now))
                    }
                    connection is ConnectionState.AuthExpired -> {
                        meter.pause(now)
                        scope.launch { stop(SessionEndReason.AUTH_LOST) }
                    }
                    connection is ConnectionState.Failed -> {
                        meter.pause(now)
                        transition(errorState(ActivityError.Steam(connection.error), sessionId, appIds, now))
                    }
                    else -> {
                        meter.pause(now)
                        remote.set(RemotePresence.Unconfirmed)
                        transition(
                            ActivityState.Reconnecting(appIds, sessionId, sessionStartedElapsed, meter.total(now)),
                        )
                    }
                }
            }
    }

    private fun errorState(error: ActivityError, sessionId: Long, appIds: List<Int>, now: Long) =
        ActivityState.Error(error, appIds, sessionId, meter.total(now))

    private fun transition(next: ActivityState) {
        val current = _state.value
        if (current.kind != next.kind && !ActivityStateMachine.canTransition(current, next)) {
            logger.warn(TAG, "unexpected activity transition ${current.kind} -> ${next.kind}")
        }
        _state.value = next
    }

    private companion object {
        const val TAG = "Activity"
    }
}

/** Accumulates time only between [resume] and [pause]. Thread-safe. */
internal class ActiveTimeMeter {
    private var accumulated = 0L
    private var since: Long? = null

    @Synchronized fun reset() {
        accumulated = 0L
        since = null
    }

    @Synchronized fun resume(now: Long) {
        if (since == null) since = now
    }

    /** @return total accumulated time after pausing. */
    @Synchronized fun pause(now: Long): Long {
        since?.let { accumulated += (now - it).coerceAtLeast(0) }
        since = null
        return accumulated
    }

    @Synchronized fun total(now: Long): Long = accumulated + (since?.let { (now - it).coerceAtLeast(0) } ?: 0L)
}
