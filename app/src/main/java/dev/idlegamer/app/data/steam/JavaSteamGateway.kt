package dev.idlegamer.app.data.steam

import dev.idlegamer.app.core.logging.AppLogger
import dev.idlegamer.app.core.security.Secret
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.model.SteamException
import dev.idlegamer.app.domain.model.SteamId
import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.enums.EMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.networking.steam3.ProtocolTypes
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.CAuthentication_Token_Revoke_Request
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthTokenPlatformType
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthTokenRevokeAction
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserver.CMsgClientGamesPlayed
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesPlayerSteamclient.CPlayer_GetOwnedGames_Request
import `in`.dragonbra.javasteam.rpc.service.Authentication
import `in`.dragonbra.javasteam.rpc.service.Player
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesAuthSteamclient.EAuthSessionGuardType
import `in`.dragonbra.javasteam.steam.authentication.AuthSession
import `in`.dragonbra.javasteam.steam.authentication.AuthSessionDetails
import `in`.dragonbra.javasteam.steam.authentication.CredentialsAuthSession
import `in`.dragonbra.javasteam.steam.authentication.AuthenticationException
import `in`.dragonbra.javasteam.steam.authentication.IChallengeUrlChanged
import `in`.dragonbra.javasteam.steam.authentication.QrAuthSession
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.SteamFriends
import `in`.dragonbra.javasteam.steam.handlers.steamfriends.callback.PersonaStateCallback
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails
import `in`.dragonbra.javasteam.steam.handlers.steamuser.SteamUser
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.AccountInfoCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOffCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback
import `in`.dragonbra.javasteam.steam.handlers.steamuser.callback.PlayingSessionStateCallback
import `in`.dragonbra.javasteam.steam.steamclient.SteamClient
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback
import `in`.dragonbra.javasteam.steam.steamclient.configuration.SteamConfiguration
import `in`.dragonbra.javasteam.types.SteamID
import `in`.dragonbra.javasteam.util.log.LogListener
import `in`.dragonbra.javasteam.util.log.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.EnumSet
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/**
 * [SteamGateway] backed by JavaSteam 1.8.0. The only class in the app that imports `in.dragonbra.*`.
 *
 * Threading: JavaSteam posts callbacks to a queue; a single pump coroutine drains it on [Dispatchers.IO]
 * and translates them into [status] / [events]. Request/response pairs (connect, logon) are bridged with
 * [CompletableDeferred]s created *before* the request is sent, so a fast reply cannot be missed.
 *
 * Requires [dev.idlegamer.app.core.security.CryptoProviders.installBouncyCastle] to have run first.
 */
class JavaSteamGateway(
    private val scope: CoroutineScope,
    private val logger: AppLogger,
    private val verboseLibraryLogs: Boolean,
) : SteamGateway {

    private val client: SteamClient by lazy {
        val configuration = SteamConfiguration.create { builder ->
            builder.withProtocolTypes(EnumSet.of(ProtocolTypes.WEB_SOCKET))
            builder.withConnectionTimeout(CONNECT_TIMEOUT_MS)
        }
        SteamClient(configuration)
    }
    private val callbacks: CallbackManager by lazy { CallbackManager(client).also(::subscribeAll) }
    private val steamUser: SteamUser get() = requireNotNull(client.getHandler(SteamUser::class.java))
    private val steamFriends: SteamFriends get() = requireNotNull(client.getHandler(SteamFriends::class.java))
    private val unified: SteamUnifiedMessages
        get() = requireNotNull(client.getHandler(SteamUnifiedMessages::class.java))
    private val playerService: Player by lazy { unified.createService(Player::class.java) }
    private val authService: Authentication by lazy { unified.createService(Authentication::class.java) }

    private val _status = MutableStateFlow<GatewayStatus>(GatewayStatus.Disconnected(DisconnectReason.NeverConnected))
    override val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<SteamEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SteamEvent> = _events.asSharedFlow()

    private val connectMutex = Mutex()
    private var pumpJob: Job? = null

    @Volatile private var pendingConnect: CompletableDeferred<Unit>? = null
    @Volatile private var pendingLogOn: CompletableDeferred<LogOnResult>? = null
    @Volatile private var lastLogOffError: SteamError? = null
    @Volatile private var userInitiatedDisconnect = false

    init {
        // JavaSteam logs nothing unless a listener is registered. Only debug builds register one, and every
        // line still goes through the redacting AppLogger.
        if (verboseLibraryLogs) {
            LogManager.addListener(object : LogListener {
                override fun onLog(clazz: Class<*>, message: String?, throwable: Throwable?) {
                    logger.debug("JavaSteam", "${clazz.simpleName}: ${message.orEmpty()}")
                }

                override fun onError(clazz: Class<*>, message: String?, throwable: Throwable?) {
                    logger.warn("JavaSteam", "${clazz.simpleName}: ${message.orEmpty()}", throwable)
                }
            })
        }
    }

    // region connection

    override suspend fun connect() = connectMutex.withLock {
        when (_status.value) {
            GatewayStatus.Connected, is GatewayStatus.LoggedOn -> return@withLock
            else -> Unit
        }
        ensurePump()
        val connected = CompletableDeferred<Unit>()
        pendingConnect = connected
        lastLogOffError = null
        userInitiatedDisconnect = false
        _status.value = GatewayStatus.Connecting
        try {
            withContext(Dispatchers.IO) { client.connect() }
            withTimeout(CONNECT_TIMEOUT_MS + 5_000) { connected.await() }
        } catch (e: CancellationException) {
            closeQuietly()
            throw e
        } catch (e: SteamException) {
            closeQuietly()
            throw e
        } catch (e: Exception) {
            closeQuietly()
            throw SteamException(SteamErrorMapper.fromThrowable(e), e)
        } finally {
            pendingConnect = null
        }
    }

    override suspend fun disconnect() {
        if (_status.value is GatewayStatus.Disconnected) return
        userInitiatedDisconnect = true
        withContext(Dispatchers.IO) { runCatching { client.disconnect() } }
        withTimeoutOrNull(DISCONNECT_WAIT_MS) { _status.first { it is GatewayStatus.Disconnected } }
            ?: run { _status.value = GatewayStatus.Disconnected(DisconnectReason.UserInitiated) }
    }

    private fun closeQuietly() {
        userInitiatedDisconnect = true
        runCatching { client.disconnect() }
        _status.value = GatewayStatus.Disconnected(DisconnectReason.UserInitiated)
    }

    private fun ensurePump() {
        if (pumpJob?.isActive == true) return
        val manager = callbacks
        pumpJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    manager.runWaitCallbackAsync()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(TAG, "callback handler failed", e)
                }
            }
        }
    }

    // endregion

    // region authentication

    override suspend fun beginQrLogin(deviceFriendlyName: String): QrLoginSession {
        requireTransport()
        val details = AuthSessionDetails().apply {
            this.deviceFriendlyName = deviceFriendlyName
            this.platformType = EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient
            this.persistentSession = true
            this.websiteID = "Client"
        }
        val qr: QrAuthSession = try {
            withTimeout(REQUEST_TIMEOUT_MS) { client.authentication.beginAuthSessionViaQR(details).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SteamException(mapAuthFailure(e), e)
        }
        return JavaSteamQrSession(qr)
    }

    override suspend fun beginCredentialsLogin(
        accountName: String,
        password: CharArray,
        deviceFriendlyName: String,
    ): CredentialsLoginSession {
        requireTransport()
        val details = AuthSessionDetails().apply {
            this.username = accountName
            // JavaSteam's API takes a String; it is RSA-encrypted with Steam's key inside beginAuthSessionViaCredentials
            // and the reference is dropped in `finally`. JVM strings cannot be wiped (SESSION_LIFECYCLE.md §9).
            this.password = String(password)
            this.deviceFriendlyName = deviceFriendlyName
            this.platformType = EAuthTokenPlatformType.k_EAuthTokenPlatformType_SteamClient
            this.persistentSession = true
            this.websiteID = "Client"
            // No IAuthenticator: Steam Guard is handled by IdleGamer's own UI, never automatically.
            this.authenticator = null
        }
        val session: CredentialsAuthSession = try {
            withTimeout(REQUEST_TIMEOUT_MS) { client.authentication.beginAuthSessionViaCredentials(details).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SteamException(mapAuthFailure(e), e)
        } finally {
            details.password = null
        }
        return JavaSteamCredentialsSession(session)
    }

    private inner class JavaSteamQrSession(private val qr: QrAuthSession) : QrLoginSession {
        private val url = MutableStateFlow(qr.challengeUrl)
        override val challengeUrl: StateFlow<String> = url.asStateFlow()
        override val pollIntervalMillis: Long = pollIntervalOf(qr)

        init {
            qr.challengeUrlChanged = IChallengeUrlChanged { session -> session?.challengeUrl?.let { url.value = it } }
        }

        override suspend fun poll(): PollResult = pollOnce(qr)
    }

    private inner class JavaSteamCredentialsSession(private val session: CredentialsAuthSession) : CredentialsLoginSession {
        override val pollIntervalMillis: Long = pollIntervalOf(session)

        override val confirmations: Set<GuardConfirmation> = session.allowedConfirmations.mapNotNull {
            when (it.confirmationType) {
                EAuthSessionGuardType.k_EAuthSessionGuardType_None -> GuardConfirmation.NONE
                EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceConfirmation -> GuardConfirmation.MOBILE_APP_APPROVAL
                EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode -> GuardConfirmation.MOBILE_APP_CODE
                EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode -> GuardConfirmation.EMAIL_CODE
                // EmailConfirmation / MachineToken / Unknown: not offered by Steam for client logins in practice.
                else -> null
            }
        }.toSet()

        override val emailHint: String? = session.allowedConfirmations
            .firstOrNull { it.confirmationType == EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode }
            ?.associatedMessage?.takeIf { it.isNotBlank() }?.take(100)

        override suspend fun poll(): PollResult = pollOnce(session)

        override suspend fun submitCode(code: String, kind: GuardConfirmation): CodeSubmission {
            val type = when (kind) {
                GuardConfirmation.MOBILE_APP_CODE -> EAuthSessionGuardType.k_EAuthSessionGuardType_DeviceCode
                GuardConfirmation.EMAIL_CODE -> EAuthSessionGuardType.k_EAuthSessionGuardType_EmailCode
                else -> return CodeSubmission.Failed(SteamError.Unsupported("code for $kind"))
            }
            return try {
                withTimeout(REQUEST_TIMEOUT_MS) { session.sendSteamGuardCode(code, type).await() }
                CodeSubmission.Accepted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when ((unwrap(e) as? AuthenticationException)?.result) {
                    EResult.InvalidLoginAuthCode, EResult.TwoFactorCodeMismatch -> CodeSubmission.Rejected
                    else -> CodeSubmission.Failed(mapAuthFailure(e))
                }
            }
        }
    }

    // `interval` is in seconds (SteamKit: TimeSpan.FromSeconds). JavaSteam's own polling loop treats it as
    // milliseconds, which is why we never call pollingWaitForResult() (RESEARCH.md §2.3).
    private fun pollIntervalOf(session: AuthSession): Long = (session.pollingInterval * 1000f).toLong().coerceIn(1_000L, 30_000L)

    private suspend fun pollOnce(session: AuthSession): PollResult {
        if (_status.value is GatewayStatus.Disconnected) return PollResult.Failed(SteamError.ConnectionReset)
        return try {
            val result = withTimeout(REQUEST_TIMEOUT_MS) { session.pollAuthSessionStatus().await() }
            when {
                result == null -> PollResult.Pending
                result.refreshToken.isNullOrEmpty() || result.accountName.isNullOrEmpty() ->
                    PollResult.Failed(SteamError.ProtocolIncompatible("poll result without token"))
                else -> PollResult.Approved(result.accountName, Secret(result.refreshToken))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val auth = unwrap(e) as? AuthenticationException
            when (auth?.result) {
                EResult.Expired, EResult.FileNotFound -> PollResult.Expired
                else -> PollResult.Failed(mapAuthFailure(e))
            }
        }
    }

    override suspend fun logOn(accountName: String, refreshToken: Secret, loginId: Int): LogOnResult {
        requireTransport()
        val result = CompletableDeferred<LogOnResult>()
        pendingLogOn = result
        try {
            val details = LogOnDetails(
                username = accountName,
                accessToken = refreshToken.reveal(),
                shouldRememberPassword = true,
                loginID = loginId,
            )
            withContext(Dispatchers.IO) { steamUser.logOn(details) }
            return withTimeoutOrNull(REQUEST_TIMEOUT_MS * 3) { result.await() }
                ?: LogOnResult.Failure(SteamError.Timeout)
        } finally {
            pendingLogOn = null
        }
    }

    override suspend fun logOff() {
        if (_status.value is GatewayStatus.Disconnected) return
        userInitiatedDisconnect = true
        withContext(Dispatchers.IO) { runCatching { steamUser.logOff() } }
        withTimeoutOrNull(DISCONNECT_WAIT_MS) { _status.first { it is GatewayStatus.Disconnected } }
            ?: run { _status.value = GatewayStatus.Disconnected(DisconnectReason.UserInitiated) }
    }

    override suspend fun revokeRefreshToken(refreshToken: Secret): Boolean {
        if (_status.value !is GatewayStatus.LoggedOn) return false
        return try {
            val request = CAuthentication_Token_Revoke_Request.newBuilder()
                .setToken(refreshToken.reveal())
                .setRevokeAction(EAuthTokenRevokeAction.k_EAuthTokenRevokePermanent)
                .build()
            val response = withTimeout(REQUEST_TIMEOUT_MS) { authService.revokeToken(request).await() }
            response.result == EResult.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "token revocation not confirmed", e)
            false
        }
    }

    override suspend fun renewRefreshToken(steamId: SteamId, refreshToken: Secret): Secret? {
        requireLoggedOn()
        return try {
            val result = withTimeout(REQUEST_TIMEOUT_MS) {
                client.authentication.generateAccessTokenForApp(SteamID(steamId.raw), refreshToken.reveal(), true).await()
            }
            result.refreshToken.takeIf { !it.isNullOrEmpty() }?.let(::Secret)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SteamException(mapAuthFailure(e), e)
        }
    }

    // endregion

    // region account data

    override suspend fun fetchOwnedGames(steamId: SteamId, appIdsFilter: List<Int>): List<OwnedGame> {
        requireLoggedOn()
        val request = CPlayer_GetOwnedGames_Request.newBuilder()
            .setSteamid(steamId.raw)
            .setIncludeAppinfo(true)
            .setIncludePlayedFreeGames(true)
            .addAllAppidsFilter(appIdsFilter)
            .build()
        val response = try {
            withTimeout(REQUEST_TIMEOUT_MS * 2) { playerService.getOwnedGames(request).await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SteamException(SteamErrorMapper.fromThrowable(e), e)
        }
        if (response.result != EResult.OK) throw SteamException(SteamErrorMapper.fromResultCode(response.result.code()))
        return response.body.gamesList.map { g ->
            OwnedGame(
                appId = g.appid,
                name = g.name.ifBlank { "App ${g.appid}" },
                playtimeForeverMinutes = g.playtimeForever,
                playtimeTwoWeeksMinutes = g.playtime2Weeks,
                iconHash = g.imgIconUrl.takeIf { it.isNotBlank() },
                lastPlayedEpochSeconds = g.rtimeLastPlayed.toLong() and 0xFFFFFFFFL,
            )
        }
    }

    override suspend fun setGamesPlayed(appIds: List<Int>) {
        requireLoggedOn()
        require(appIds.size <= dev.idlegamer.app.domain.model.Game.MAX_CONCURRENT_GAMES)
        val message = ClientMsgProtobuf<CMsgClientGamesPlayed.Builder>(
            CMsgClientGamesPlayed::class.java,
            EMsg.ClientGamesPlayed,
        )
        appIds.forEach { appId ->
            message.body.addGamesPlayed(CMsgClientGamesPlayed.GamePlayed.newBuilder().setGameId(appId.toLong()))
        }
        try {
            withContext(Dispatchers.IO) { client.send(message) }
        } catch (e: Exception) {
            throw SteamException(SteamErrorMapper.fromThrowable(e), e)
        }
    }

    override suspend fun requestPersona(steamId: SteamId) {
        if (_status.value !is GatewayStatus.LoggedOn) return
        withContext(Dispatchers.IO) { runCatching { steamFriends.requestFriendInfo(SteamID(steamId.raw)) } }
    }

    // endregion

    // region callbacks

    private fun subscribeAll(manager: CallbackManager) {
        manager.subscribe(ConnectedCallback::class.java) {
            _status.value = GatewayStatus.Connected
            pendingConnect?.complete(Unit)
        }
        manager.subscribe(DisconnectedCallback::class.java) { cb ->
            val reason = lastLogOffError?.let(DisconnectReason::LoggedOff)
                ?: if (cb.isUserInitiated || userInitiatedDisconnect) DisconnectReason.UserInitiated else DisconnectReason.TransportLost
            _status.value = GatewayStatus.Disconnected(reason)
            pendingConnect?.completeExceptionally(SteamException(SteamError.ConnectionReset))
            pendingLogOn?.complete(LogOnResult.Failure(SteamError.ConnectionReset))
        }
        manager.subscribe(LoggedOnCallback::class.java) { cb ->
            val outcome = if (cb.result == EResult.OK) {
                val raw = cb.clientSteamID?.convertToUInt64()
                if (raw == null || raw == 0L) {
                    LogOnResult.Failure(SteamError.ProtocolIncompatible("logon without steam id"))
                } else {
                    _status.value = GatewayStatus.LoggedOn(SteamId(raw))
                    LogOnResult.Success(SteamId(raw))
                }
            } else {
                LogOnResult.Failure(SteamErrorMapper.fromResultCode(cb.result.code()))
            }
            pendingLogOn?.complete(outcome)
        }
        manager.subscribe(LoggedOffCallback::class.java) { cb ->
            val error = SteamErrorMapper.fromResultCode(cb.result.code())
            lastLogOffError = error
            if (_status.value is GatewayStatus.LoggedOn) _status.value = GatewayStatus.Connected
            _events.tryEmit(SteamEvent.LoggedOff(error))
        }
        manager.subscribe(AccountInfoCallback::class.java) { cb ->
            _events.tryEmit(SteamEvent.AccountInfo(cb.personaName))
        }
        manager.subscribe(PersonaStateCallback::class.java) { cb ->
            val hash = cb.avatarHash.takeIf { bytes -> bytes.any { it != 0.toByte() } }
                ?.joinToString("") { "%02x".format(it) }
            _events.tryEmit(
                SteamEvent.PersonaUpdate(
                    steamId = SteamId(cb.friendId.convertToUInt64()),
                    name = cb.playerName.takeIf { it.isNotBlank() },
                    avatarHashHex = hash,
                    playingAppId = cb.gamePlayedAppId,
                ),
            )
        }
        manager.subscribe(PlayingSessionStateCallback::class.java) { cb ->
            _events.tryEmit(SteamEvent.PlayingSessionState(cb.isPlayingBlocked, cb.playingAppID))
        }
    }

    // endregion

    private fun requireTransport() {
        if (_status.value is GatewayStatus.Disconnected || _status.value == GatewayStatus.Connecting) {
            throw SteamException(SteamError.ConnectionReset)
        }
    }

    private fun requireLoggedOn() {
        if (_status.value !is GatewayStatus.LoggedOn) throw SteamException(SteamError.ConnectionReset)
    }

    private fun mapAuthFailure(e: Throwable): SteamError {
        val cause = unwrap(e)
        val code = (cause as? AuthenticationException)?.result?.code()
        return when {
            code != null -> SteamErrorMapper.fromResultCode(code)
            cause is IllegalArgumentException -> SteamError.ConnectionReset
            else -> SteamErrorMapper.fromThrowable(cause)
        }
    }

    private fun unwrap(e: Throwable): Throwable =
        if ((e is CompletionException || e is ExecutionException) && e.cause != null) unwrap(e.cause!!) else e

    private companion object {
        const val TAG = "SteamGateway"
        const val CONNECT_TIMEOUT_MS = 30_000L
        const val REQUEST_TIMEOUT_MS = 20_000L
        const val DISCONNECT_WAIT_MS = 5_000L
    }
}
