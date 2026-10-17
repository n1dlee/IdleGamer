package dev.idlegamer.app.domain.model

/**
 * CM connection lifecycle. A transport connection ([Connected]) is *not* a signed-in session;
 * only [Authenticated] means Steam accepted our logon.
 */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Authenticating : ConnectionState
    data class Authenticated(val steamId: SteamId, val sinceElapsedMillis: Long) : ConnectionState
    data class Reconnecting(
        val attempt: Int,
        val nextAttemptAtElapsedMillis: Long,
        val lastError: SteamError,
        val waitingForNetwork: Boolean,
    ) : ConnectionState
    data object AuthExpired : ConnectionState
    data class Failed(val error: SteamError) : ConnectionState
    data object Stopping : ConnectionState

    val kind: Kind
        get() = when (this) {
            Disconnected -> Kind.DISCONNECTED
            Connecting -> Kind.CONNECTING
            Connected -> Kind.CONNECTED
            Authenticating -> Kind.AUTHENTICATING
            is Authenticated -> Kind.AUTHENTICATED
            is Reconnecting -> Kind.RECONNECTING
            AuthExpired -> Kind.AUTH_EXPIRED
            is Failed -> Kind.FAILED
            Stopping -> Kind.STOPPING
        }

    enum class Kind { DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATING, AUTHENTICATED, RECONNECTING, AUTH_EXPIRED, FAILED, STOPPING }
}

object ConnectionStateMachine {
    private val allowed: Map<ConnectionState.Kind, Set<ConnectionState.Kind>> = with(ConnectionState.Kind.entries) {
        mapOf(
            ConnectionState.Kind.DISCONNECTED to setOf(ConnectionState.Kind.CONNECTING, ConnectionState.Kind.STOPPING),
            ConnectionState.Kind.CONNECTING to setOf(
                ConnectionState.Kind.CONNECTED, ConnectionState.Kind.RECONNECTING, ConnectionState.Kind.FAILED,
                ConnectionState.Kind.STOPPING, ConnectionState.Kind.DISCONNECTED,
            ),
            ConnectionState.Kind.CONNECTED to setOf(
                ConnectionState.Kind.AUTHENTICATING, ConnectionState.Kind.RECONNECTING, ConnectionState.Kind.STOPPING,
                ConnectionState.Kind.DISCONNECTED, ConnectionState.Kind.FAILED,
            ),
            ConnectionState.Kind.AUTHENTICATING to setOf(
                ConnectionState.Kind.AUTHENTICATED, ConnectionState.Kind.AUTH_EXPIRED, ConnectionState.Kind.RECONNECTING,
                ConnectionState.Kind.FAILED, ConnectionState.Kind.STOPPING, ConnectionState.Kind.DISCONNECTED,
            ),
            ConnectionState.Kind.AUTHENTICATED to setOf(
                ConnectionState.Kind.RECONNECTING, ConnectionState.Kind.AUTH_EXPIRED, ConnectionState.Kind.STOPPING,
                ConnectionState.Kind.FAILED, ConnectionState.Kind.DISCONNECTED,
            ),
            ConnectionState.Kind.RECONNECTING to setOf(
                ConnectionState.Kind.RECONNECTING, ConnectionState.Kind.CONNECTING, ConnectionState.Kind.FAILED,
                ConnectionState.Kind.STOPPING, ConnectionState.Kind.DISCONNECTED,
            ),
            ConnectionState.Kind.AUTH_EXPIRED to setOf(ConnectionState.Kind.STOPPING, ConnectionState.Kind.DISCONNECTED),
            ConnectionState.Kind.FAILED to setOf(
                ConnectionState.Kind.CONNECTING, ConnectionState.Kind.STOPPING, ConnectionState.Kind.DISCONNECTED,
            ),
            ConnectionState.Kind.STOPPING to setOf(ConnectionState.Kind.DISCONNECTED),
        ).also { table -> check(table.keys == toSet()) }
    }

    fun canTransition(from: ConnectionState, to: ConnectionState): Boolean =
        to.kind in allowed.getValue(from.kind)
}
