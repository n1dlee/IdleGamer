package dev.idlegamer.app.domain.model

/**
 * State of the user-controlled presence engine.
 *
 * Definition of [Active]: all three hold at the same time —
 *  1. the user started a local session for [Active.appIds];
 *  2. the CM connection is [ConnectionState.Authenticated];
 *  3. a `ClientGamesPlayed` message for exactly these app ids was handed to that live connection
 *     without error.
 * It does **not** mean Steam recorded playtime — Steam sends no acknowledgement for presence. Remote
 * evidence, if any, is reported separately in [RemotePresence].
 */
sealed interface ActivityState {
    data object Idle : ActivityState

    data class Starting(val appIds: List<Int>) : ActivityState

    data class Active(
        val appIds: List<Int>,
        val sessionId: Long,
        val sessionStartedElapsedMillis: Long,
        val activeSinceElapsedMillis: Long,
        val accumulatedActiveMillis: Long,
        val remote: RemotePresence,
    ) : ActivityState

    /** Presence is not live because the connection dropped; the local session is still open. */
    data class Reconnecting(
        val appIds: List<Int>,
        val sessionId: Long,
        val sessionStartedElapsedMillis: Long,
        val accumulatedActiveMillis: Long,
    ) : ActivityState

    data object Stopping : ActivityState

    data class Stopped(val summary: SessionSummary?) : ActivityState

    data class Error(
        val error: ActivityError,
        val appIds: List<Int>,
        val sessionId: Long?,
        val accumulatedActiveMillis: Long,
    ) : ActivityState

    val kind: Kind
        get() = when (this) {
            Idle -> Kind.IDLE
            is Starting -> Kind.STARTING
            is Active -> Kind.ACTIVE
            is Reconnecting -> Kind.RECONNECTING
            Stopping -> Kind.STOPPING
            is Stopped -> Kind.STOPPED
            is Error -> Kind.ERROR
        }

    /** True while a local session is open (Stop is meaningful). */
    val hasOpenSession: Boolean
        get() = this is Starting || this is Active || this is Reconnecting ||
            (this is Error && sessionId != null)

    enum class Kind { IDLE, STARTING, ACTIVE, STOPPING, STOPPED, RECONNECTING, ERROR }
}

sealed interface RemotePresence {
    /** Presence was sent; Steam has not echoed it back (normal: Steam has no ack for this message). */
    data object Unconfirmed : RemotePresence

    /** Steam pushed a persona update for our own account showing one of the app ids. */
    data class EchoedByPersonaUpdate(val appId: Int, val atEpochMillis: Long) : RemotePresence
}

sealed interface ActivityError {
    data object NotSignedIn : ActivityError
    data object RiskNotAcknowledged : ActivityError
    data object NothingSelected : ActivityError
    data class TooManyGames(val max: Int) : ActivityError
    data class InvalidAppId(val appId: Long) : ActivityError
    data class Steam(val error: SteamError) : ActivityError
    data object ForegroundServiceNotAllowed : ActivityError
    data object StoppedBySystem : ActivityError
}

data class SessionSummary(
    val sessionId: Long,
    val appIds: List<Int>,
    val activeMillis: Long,
    val endReason: SessionEndReason,
)

enum class SessionEndReason {
    USER_STOPPED_UI,
    USER_STOPPED_NOTIFICATION,
    LOGGED_OUT,
    AUTH_LOST,
    AUTO_STOP_LIMIT,
    SYSTEM_STOPPED,
    /** Found open after process death / reboot; duration capped at the last checkpoint. */
    INTERRUPTED,
    ERROR,
}

object ActivityStateMachine {
    private val allowed: Map<ActivityState.Kind, Set<ActivityState.Kind>> = mapOf(
        ActivityState.Kind.IDLE to setOf(ActivityState.Kind.STARTING),
        ActivityState.Kind.STARTING to setOf(
            ActivityState.Kind.ACTIVE, ActivityState.Kind.RECONNECTING, ActivityState.Kind.ERROR,
            ActivityState.Kind.STOPPING,
        ),
        ActivityState.Kind.ACTIVE to setOf(
            ActivityState.Kind.ACTIVE, ActivityState.Kind.RECONNECTING, ActivityState.Kind.ERROR,
            ActivityState.Kind.STOPPING,
        ),
        ActivityState.Kind.RECONNECTING to setOf(
            ActivityState.Kind.ACTIVE, ActivityState.Kind.RECONNECTING, ActivityState.Kind.ERROR,
            ActivityState.Kind.STOPPING,
        ),
        ActivityState.Kind.ERROR to setOf(
            ActivityState.Kind.STARTING, ActivityState.Kind.ACTIVE, ActivityState.Kind.STOPPING,
            ActivityState.Kind.ERROR, ActivityState.Kind.RECONNECTING,
        ),
        ActivityState.Kind.STOPPING to setOf(ActivityState.Kind.STOPPED),
        ActivityState.Kind.STOPPED to setOf(ActivityState.Kind.STARTING),
    )

    fun canTransition(from: ActivityState, to: ActivityState): Boolean = to.kind in allowed.getValue(from.kind)
}
