package dev.idlegamer.app.session

import dev.idlegamer.app.core.logging.AppEvent
import dev.idlegamer.app.core.logging.AppLogger
import dev.idlegamer.app.core.network.NetworkMonitor
import dev.idlegamer.app.core.security.JwtClaims
import dev.idlegamer.app.core.time.Clock
import dev.idlegamer.app.data.steam.DisconnectReason
import dev.idlegamer.app.data.steam.GatewayStatus
import dev.idlegamer.app.data.steam.LogOnResult
import dev.idlegamer.app.data.steam.QrPollResult
import dev.idlegamer.app.data.steam.SteamEvent
import dev.idlegamer.app.data.steam.SteamGateway
import dev.idlegamer.app.domain.model.AccountIdentity
import dev.idlegamer.app.domain.model.AuthError
import dev.idlegamer.app.domain.model.AuthEvent
import dev.idlegamer.app.domain.model.AuthState
import dev.idlegamer.app.domain.model.AuthStateMachine
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.ConnectionStateMachine
import dev.idlegamer.app.domain.model.ReauthReason
import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.model.SteamException
import dev.idlegamer.app.domain.model.SteamId
import dev.idlegamer.app.domain.model.UserProfile
import dev.idlegamer.app.domain.repository.CredentialStoreException
import dev.idlegamer.app.domain.repository.SecureCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the Steam session: QR login, the connection/reconnect loop, logout. Single instance per process.
 *
 * Concurrency (R5):
 *  * [opMutex] serialises login, logout and initialisation.
 *  * Exactly one connection loop exists; it runs only while someone needs a session ([Demand]) *and*
 *    credentials are valid, and is restarted via `collectLatest`, which cancels the previous one first.
 *  * Starting a new QR login cancels the previous one.
 */
class SteamSessionManager(
    private val gateway: SteamGateway,
    private val credentialStore: SecureCredentialStore,
    private val network: NetworkMonitor,
    private val clock: Clock,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val deviceFriendlyName: String,
    private val loginId: suspend () -> Int,
    /** Called after a successful QR login, before credentials are saved (clears data of a previous account). */
    private val onAccountSignedIn: suspend (SteamId) -> Unit,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val qrLifetimeMillis: Long = 5 * 60_000,
    private val uiReleaseGraceMillis: Long = 30_000,
) {
    enum class Demand { UI, ACTIVITY }

    private val _auth = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _auth.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _profile = MutableStateFlow<UserProfile?>(null)
    val profile: StateFlow<UserProfile?> = _profile.asStateFlow()

    private val demand = MutableStateFlow<Set<Demand>>(emptySet())
    private val retryToken = MutableStateFlow(0)

    private val opMutex = Mutex()
    private var loginJob: Job? = null
    private var uiReleaseJob: Job? = null
    private var supervisorStarted = false

    /** In-memory copy of the vault contents. Never exposed, never logged. */
    @Volatile private var credentials: SessionCredentials? = null
    @Volatile private var shuttingDown = false

    /** Number of state changes that violated [ConnectionStateMachine]; tests assert it stays 0. */
    val invalidTransitionCount = AtomicInteger(0)

    // region public API

    suspend fun initialize() = opMutex.withLock {
        if (_auth.value != AuthState.Unknown) return@withLock
        val loaded = try {
            credentialStore.load()
        } catch (e: CredentialStoreException) {
            logger.event(AppEvent.STORAGE_FAILURE, "vault unreadable: ${e.kind}")
            runCatching { credentialStore.clear() }
            dispatch(AuthEvent.StoredCredentialsUnreadable)
            startSupervisor()
            return@withLock
        }
        if (loaded == null) {
            dispatch(AuthEvent.NoStoredCredentials)
        } else {
            credentials = loaded
            _profile.value = UserProfile(loaded.steamId, personaName = null, avatarUrl = null)
            dispatch(AuthEvent.StoredCredentialsFound(AccountIdentity(loaded.steamId)))
        }
        startSupervisor()
    }

    fun startQrLogin() {
        loginJob?.cancel()
        loginJob = scope.launch { opMutex.withLock { runQrLogin() } }
    }

    fun cancelQrLogin() {
        loginJob?.cancel()
    }

    /** Ask for a live session. The connection loop runs while at least one demand is held. */
    fun requestDemand(d: Demand) {
        if (d == Demand.UI) uiReleaseJob?.cancel()
        demand.update { it + d }
    }

    fun releaseDemand(d: Demand) {
        if (d == Demand.UI) {
            uiReleaseJob?.cancel()
            uiReleaseJob = scope.launch {
                delay(uiReleaseGraceMillis)
                demand.update { it - Demand.UI }
            }
        } else {
            demand.update { it - d }
        }
    }

    /** Manual retry after [ConnectionState.Failed]. */
    fun retryNow() {
        retryToken.update { it + 1 }
    }

    /** Sends the presence list on the current authenticated connection. */
    suspend fun sendPresence(appIds: List<Int>) {
        if (_connection.value !is ConnectionState.Authenticated) throw SteamException(SteamError.ConnectionReset)
        gateway.setGamesPlayed(appIds)
    }

    fun currentSteamId(): SteamId? = credentials?.steamId

    data class LogoutResult(val revocationConfirmed: Boolean, val wasOnline: Boolean)

    /**
     * Ends the session for good: best-effort server-side revocation (while still logged on), ClientLogOff,
     * then local wipe. Local deletion happens even if every network step fails.
     */
    suspend fun logout(): LogoutResult = opMutex.withLock {
        loginJob?.cancel()
        shuttingDown = true
        try {
            val creds = credentials
            var revoked = false
            var wasOnline = false
            if (creds != null && network.isOnline.value && _auth.value is AuthState.Authenticated) {
                // Give an in-flight logon a moment to finish so revocation can be attempted.
                withTimeoutOrNull(10_000) { gateway.status.first { it is GatewayStatus.LoggedOn } }
                if (gateway.status.value is GatewayStatus.LoggedOn) {
                    wasOnline = true
                    revoked = runCatching { gateway.revokeRefreshToken(creds.refreshToken) }.getOrDefault(false)
                }
            }
            logger.event(AppEvent.TOKEN_REVOCATION, if (revoked) "confirmed by Steam" else "not confirmed")
            runCatching { gateway.setGamesPlayed(emptyList()) }
            runCatching { gateway.logOff() }
            runCatching { gateway.disconnect() }
            try {
                credentialStore.clear()
            } catch (e: Exception) {
                logger.error(TAG, "vault clear failed", e)
                logger.event(AppEvent.STORAGE_FAILURE, "vault clear failed")
            }
            credentials = null
            _profile.value = null
            dispatch(AuthEvent.LoggedOut)
            setConnection(ConnectionState.Disconnected, force = true)
            logger.event(AppEvent.LOGGED_OUT)
            LogoutResult(revocationConfirmed = revoked, wasOnline = wasOnline)
        } finally {
            shuttingDown = false
        }
    }

    // endregion

    // region QR login

    private suspend fun runQrLogin() {
        dispatch(AuthEvent.QrLoginRequested)
        if (_auth.value != AuthState.Initializing) return
        logger.event(AppEvent.AUTH_INITIATED, "qr")
        var completed = false
        try {
            if (!network.isOnline.value) throw LoginFailure(AuthError.Steam(SteamError.NetworkUnavailable))
            // Coming from AuthExpired / Failed of a previous session: start the lifecycle over.
            setConnection(ConnectionState.Disconnected, force = true)
            setConnection(ConnectionState.Connecting)
            gateway.connect()
            setConnection(ConnectionState.Connected)

            val qr = gateway.beginQrLogin(deviceFriendlyName)
            val deadline = clock.elapsedRealtimeMillis() + qrLifetimeMillis
            dispatch(AuthEvent.QrChallengeIssued(qr.challengeUrl.value, deadline))
            val urlWatcher = scope.launch {
                qr.challengeUrl.drop(1).collect { dispatch(AuthEvent.QrChallengeRefreshed(it, deadline)) }
            }
            val approved = try {
                val maxPolls = (qrLifetimeMillis / qr.pollIntervalMillis).toInt() + 1
                var polls = 0
                var result: QrPollResult.Approved? = null
                while (result == null) {
                    delay(qr.pollIntervalMillis)
                    if (clock.elapsedRealtimeMillis() >= deadline) throw LoginFailure(AuthError.QrExpired)
                    if (++polls > maxPolls) throw LoginFailure(AuthError.PollingLimitReached)
                    when (val poll = qr.poll()) {
                        QrPollResult.Pending -> Unit
                        is QrPollResult.Approved -> result = poll
                        QrPollResult.Expired -> throw LoginFailure(AuthError.QrExpired)
                        is QrPollResult.Failed -> throw LoginFailure(AuthError.Steam(poll.error))
                    }
                }
                result
            } finally {
                urlWatcher.cancel()
            }

            dispatch(AuthEvent.QrApproved)
            setConnection(ConnectionState.Authenticating)
            val steamId = when (val logOn = gateway.logOn(approved.accountName, approved.refreshToken, loginId())) {
                is LogOnResult.Failure -> throw LoginFailure(AuthError.Steam(logOn.error))
                is LogOnResult.Success -> logOn.steamId
            }
            val claims = JwtClaims.parse(approved.refreshToken)
            if (claims?.subject != null && claims.subject != steamId) {
                runCatching { gateway.logOff() }
                throw LoginFailure(AuthError.Steam(SteamError.IdentityMismatch))
            }
            if (claims == null) logger.warn(TAG, "refresh token payload unreadable; identity checked via logon only")

            onAccountSignedIn(steamId)
            val creds = SessionCredentials(steamId, approved.accountName, approved.refreshToken, clock.currentTimeMillis())
            try {
                credentialStore.save(creds)
            } catch (e: CredentialStoreException) {
                logger.event(AppEvent.STORAGE_FAILURE, "vault save failed: ${e.kind}")
                runCatching { gateway.logOff() }
                throw LoginFailure(AuthError.StorageFailure)
            }
            credentials = creds
            _profile.value = UserProfile(steamId, personaName = null, avatarUrl = null)
            setConnection(ConnectionState.Authenticated(steamId, clock.elapsedRealtimeMillis()))
            dispatch(AuthEvent.LoginSucceeded(AccountIdentity(steamId)))
            logger.event(AppEvent.AUTH_SUCCEEDED, "account ${steamId.masked()}")
            runCatching { gateway.requestPersona(steamId) }
            completed = true
        } catch (e: CancellationException) {
            dispatch(AuthEvent.LoginCancelled)
            logger.event(AppEvent.AUTH_CANCELLED)
            throw e
        } catch (e: LoginFailure) {
            dispatch(AuthEvent.LoginFailed(e.error))
            logger.event(AppEvent.AUTH_FAILED, describe(e.error))
        } catch (e: SteamException) {
            dispatch(AuthEvent.LoginFailed(AuthError.Steam(e.error)))
            logger.event(AppEvent.AUTH_FAILED, describe(AuthError.Steam(e.error)))
        } finally {
            if (!completed) {
                withContext(NonCancellable) {
                    runCatching { gateway.disconnect() }
                    setConnection(ConnectionState.Disconnected, force = true)
                }
            }
        }
    }

    private class LoginFailure(val error: AuthError) : Exception()

    // endregion

    // region connection loop

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startSupervisor() {
        if (supervisorStarted) return
        supervisorStarted = true
        scope.launch { observeGatewayEvents() }
        scope.launch {
            combine(demand, _auth, retryToken) { d, auth, token ->
                (d.isNotEmpty() && auth is AuthState.Authenticated) to token
            }.distinctUntilChanged().collectLatest { (wanted, _) ->
                if (wanted) runConnectionLoop() else teardownIfIdle()
            }
        }
    }

    private suspend fun teardownIfIdle() {
        if (_auth.value is AuthState.Authenticated && gateway.status.value !is GatewayStatus.Disconnected) {
            setConnection(ConnectionState.Stopping)
            runCatching { gateway.disconnect() }
            setConnection(ConnectionState.Disconnected)
        }
    }

    private suspend fun runConnectionLoop() {
        var attempt = 0
        try {
            while (true) {
                val creds = credentials ?: return
                if (!network.isOnline.value) {
                    setConnection(
                        ConnectionState.Reconnecting(attempt, clock.elapsedRealtimeMillis(), SteamError.NetworkUnavailable, waitingForNetwork = true),
                    )
                    network.awaitOnline()
                }
                val session = establishAndHold(creds)
                if (shuttingDown) return
                if (session.stableMillis >= policy.stableConnectionMillis) attempt = 0
                val error = session.endedWith

                if (error is SteamError.InvalidCredentials || error is SteamError.IdentityMismatch) {
                    onCredentialsRejected(error)
                    return
                }
                attempt++
                when (val decision = policy.decide(attempt, error)) {
                    ReconnectPolicy.Decision.GiveUp -> {
                        setConnection(ConnectionState.Failed(error))
                        logger.event(AppEvent.CONNECTION_LOST, "giving up after $attempt attempts: ${describe(error)}")
                        return
                    }
                    is ReconnectPolicy.Decision.RetryAfter -> {
                        setConnection(
                            ConnectionState.Reconnecting(
                                attempt = attempt,
                                nextAttemptAtElapsedMillis = clock.elapsedRealtimeMillis() + decision.delayMillis,
                                lastError = error,
                                waitingForNetwork = false,
                            ),
                        )
                        logger.event(AppEvent.RECONNECT_SCHEDULED, "attempt $attempt in ${decision.delayMillis / 1000}s after ${describe(error)}")
                        waitBeforeRetry(decision.delayMillis, error)
                    }
                }
            }
        } finally {
            if (!shuttingDown && _auth.value is AuthState.Authenticated) {
                withContext(NonCancellable) {
                    if (gateway.status.value !is GatewayStatus.Disconnected) {
                        setConnection(ConnectionState.Stopping)
                        runCatching { gateway.disconnect() }
                    }
                    if (_connection.value !is ConnectionState.Failed) setConnection(ConnectionState.Disconnected)
                }
            }
        }
    }

    private class HeldSession(val endedWith: SteamError, val stableMillis: Long)

    /** Connects and logs on if needed, then suspends until the session ends. Returns why it ended. */
    private suspend fun establishAndHold(creds: SessionCredentials): HeldSession {
        if (gateway.status.value !is GatewayStatus.LoggedOn) {
            setConnection(ConnectionState.Connecting)
            try {
                gateway.connect()
            } catch (e: SteamException) {
                return HeldSession(e.error, 0)
            }
            setConnection(ConnectionState.Connected)
            setConnection(ConnectionState.Authenticating)
            when (val result = gateway.logOn(creds.accountName, creds.refreshToken, loginId())) {
                is LogOnResult.Failure -> {
                    runCatching { gateway.disconnect() }
                    return HeldSession(result.error, 0)
                }
                is LogOnResult.Success -> if (result.steamId != creds.steamId) {
                    runCatching { gateway.logOff() }
                    return HeldSession(SteamError.IdentityMismatch, 0)
                }
            }
        }
        val since = clock.elapsedRealtimeMillis()
        setConnection(ConnectionState.Authenticated(creds.steamId, since))
        logger.event(AppEvent.CONNECTION_ESTABLISHED)
        runCatching { gateway.requestPersona(creds.steamId) }
        val renewJob = scope.launch { renewTokenIfExpiringSoon(creds) }
        try {
            val ended = gateway.status.first { it is GatewayStatus.Disconnected } as GatewayStatus.Disconnected
            val error = when (val reason = ended.reason) {
                is DisconnectReason.LoggedOff -> reason.error
                else -> SteamError.ConnectionReset
            }
            logger.event(AppEvent.CONNECTION_LOST, describe(error))
            return HeldSession(error, clock.elapsedRealtimeMillis() - since)
        } finally {
            renewJob.cancel()
        }
    }

    private suspend fun waitBeforeRetry(delayMillis: Long, error: SteamError) {
        if (error is SteamError.Network) {
            // A network change (e.g. Wi-Fi -> LTE comes up) cuts the wait short; nothing else does.
            withTimeoutOrNull(delayMillis) {
                network.isOnline.first { !it }
                network.isOnline.first { it }
            }
        } else {
            delay(delayMillis)
        }
    }

    private suspend fun onCredentialsRejected(error: SteamError) {
        setConnection(ConnectionState.AuthExpired)
        val reason = if (error is SteamError.InvalidCredentials && error.resultCode == REVOKED) {
            ReauthReason.SessionRevoked
        } else {
            ReauthReason.TokenRejected
        }
        logger.event(AppEvent.SESSION_EXPIRED, describe(error))
        runCatching { credentialStore.clear() }
        credentials = null
        dispatch(AuthEvent.SessionRejected(reason))
    }

    private suspend fun renewTokenIfExpiringSoon(creds: SessionCredentials) {
        val exp = JwtClaims.parse(creds.refreshToken)?.expiresAtEpochSeconds ?: return
        val remainingMillis = exp * 1000 - clock.currentTimeMillis()
        if (remainingMillis > RENEW_WHEN_REMAINING_MILLIS) return
        try {
            val renewed = gateway.renewRefreshToken(creds.steamId, creds.refreshToken) ?: return
            val updated = creds.copy(refreshToken = renewed, createdAtEpochMillis = clock.currentTimeMillis())
            credentialStore.save(updated)
            credentials = updated
            logger.event(AppEvent.AUTH_SUCCEEDED, "refresh token renewed")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "token renewal failed; keeping current token", e)
        }
    }

    // endregion

    private suspend fun observeGatewayEvents() {
        gateway.events.collect { event ->
            when (event) {
                is SteamEvent.AccountInfo -> _profile.update { it?.copy(personaName = event.personaName) }
                is SteamEvent.PersonaUpdate -> _profile.update { current ->
                    if (current == null || current.steamId != event.steamId) {
                        current
                    } else {
                        current.copy(
                            personaName = event.name ?: current.personaName,
                            avatarUrl = event.avatarHashHex?.let { "https://avatars.steamstatic.com/${it}_full.jpg" } ?: current.avatarUrl,
                        )
                    }
                }
                is SteamEvent.LoggedOff -> logger.debug(TAG, "logged off: ${describe(event.error)}")
                is SteamEvent.PlayingSessionState -> Unit
            }
        }
    }

    private fun dispatch(event: AuthEvent) {
        _auth.update { current ->
            val next = AuthStateMachine.reduce(current, event)
            if (next == current && !AuthStateMachine.isValidTransition(current, event)) {
                logger.debug(TAG, "ignored auth event ${event::class.simpleName} in ${current::class.simpleName}")
            }
            next
        }
    }

    private fun setConnection(next: ConnectionState, force: Boolean = false) {
        val current = _connection.value
        if (current == next) return
        if (!force && current.kind != next.kind && !ConnectionStateMachine.canTransition(current, next)) {
            invalidTransitionCount.incrementAndGet()
            logger.warn(TAG, "unexpected connection transition ${current.kind} -> ${next.kind}")
        }
        _connection.value = next
    }

    private fun describe(error: AuthError): String = when (error) {
        is AuthError.Steam -> describe(error.error)
        else -> error::class.simpleName.orEmpty()
    }

    private fun describe(error: SteamError): String = error.toString()

    private companion object {
        const val TAG = "Session"
        const val REVOKED = 26
        const val RENEW_WHEN_REMAINING_MILLIS = 30L * 24 * 3600 * 1000
    }
}
