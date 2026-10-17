package dev.idlegamer.app.testing

import dev.idlegamer.app.core.logging.AppEvent
import dev.idlegamer.app.core.logging.AppLogger
import dev.idlegamer.app.core.network.NetworkMonitor
import dev.idlegamer.app.core.security.CipherFailure
import dev.idlegamer.app.core.security.CredentialCipher
import dev.idlegamer.app.core.security.EncryptedPayload
import dev.idlegamer.app.core.security.Secret
import dev.idlegamer.app.core.time.Clock
import dev.idlegamer.app.data.steam.CodeSubmission
import dev.idlegamer.app.data.steam.CredentialsLoginSession
import dev.idlegamer.app.data.steam.DisconnectReason
import dev.idlegamer.app.data.steam.GuardConfirmation
import dev.idlegamer.app.data.steam.GatewayStatus
import dev.idlegamer.app.data.steam.LogOnResult
import dev.idlegamer.app.data.steam.OwnedGame
import dev.idlegamer.app.data.steam.QrLoginSession
import dev.idlegamer.app.data.steam.PollResult
import dev.idlegamer.app.data.steam.SteamEvent
import dev.idlegamer.app.data.steam.SteamGateway
import dev.idlegamer.app.domain.model.ActivitySessionRecord
import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.model.SessionEndReason
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.model.SteamException
import dev.idlegamer.app.domain.model.SteamId
import dev.idlegamer.app.domain.repository.ActivityHistoryRepository
import dev.idlegamer.app.domain.repository.CredentialStoreException
import dev.idlegamer.app.domain.repository.SecureCredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val TEST_STEAM_ID = SteamId(76561197960287930L)
val OTHER_STEAM_ID = SteamId(76561197960287931L)

/** Builds an unsigned JWT-shaped string with the given subject. Test data only — not a real token. */
fun fakeJwt(subject: SteamId, expEpochSeconds: Long = 4_102_444_800L): Secret {
    val enc = Base64.getUrlEncoder().withoutPadding()
    val header = enc.encodeToString("""{"typ":"JWT","alg":"none"}""".toByteArray())
    val payload = enc.encodeToString(
        """{"iss":"test","sub":"${subject.asDecimalString()}","aud":["client","web"],"exp":$expEpochSeconds}""".toByteArray(),
    )
    return Secret("$header.$payload.dGVzdC1zaWduYXR1cmU")
}

class TestClock(private val scope: TestScope) : Clock {
    var wallOffset = 1_700_000_000_000L
    override fun elapsedRealtimeMillis(): Long = scope.testScheduler.currentTime
    override fun currentTimeMillis(): Long = wallOffset + scope.testScheduler.currentTime
}

class RecordingLogger : AppLogger {
    val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val events: MutableList<Pair<AppEvent, String>> = Collections.synchronizedList(mutableListOf())
    override fun debug(tag: String, message: String) { lines += "D/$tag $message" }
    override fun warn(tag: String, message: String, throwable: Throwable?) { lines += "W/$tag $message" }
    override fun error(tag: String, message: String, throwable: Throwable?) { lines += "E/$tag $message" }
    override fun event(event: AppEvent, details: String) { events += event to details }
}

class FakeNetworkMonitor(online: Boolean = true) : NetworkMonitor {
    val state = MutableStateFlow(online)
    override val isOnline: StateFlow<Boolean> = state.asStateFlow()
}

class InMemoryCredentialStore : SecureCredentialStore {
    var stored: SessionCredentials? = null
    var failLoadWith: CredentialStoreException.Kind? = null
    var failSaveWith: CredentialStoreException.Kind? = null
    var clearCount = 0
    override suspend fun save(credentials: SessionCredentials) {
        failSaveWith?.let { throw CredentialStoreException(it) }
        stored = credentials
    }
    override suspend fun load(): SessionCredentials? {
        failLoadWith?.let { throw CredentialStoreException(it) }
        return stored
    }
    override suspend fun clear() {
        clearCount++
        stored = null
    }
}

/** Software AES-256-GCM with the same contract as the Keystore cipher (random 12-byte IV per call). */
class SoftwareCipher(override val keyAlias: String = "test.alias") : CredentialCipher {
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val random = SecureRandom()
    val ivsUsed = mutableListOf<String>()

    override fun encrypt(plaintext: ByteArray, aad: ByteArray): EncryptedPayload {
        val k = key ?: throw CipherFailure.KeyUnavailable()
        val iv = ByteArray(12).also(random::nextBytes)
        ivsUsed += iv.joinToString("") { "%02x".format(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return EncryptedPayload(iv, c.doFinal(plaintext))
    }

    override fun decrypt(payload: EncryptedPayload, aad: ByteArray): ByteArray {
        val k = key ?: throw CipherFailure.KeyUnavailable()
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, payload.iv))
            c.updateAAD(aad)
            c.doFinal(payload.ciphertext)
        } catch (e: AEADBadTagException) {
            throw CipherFailure.Corrupted(e)
        }
    }

    override fun deleteKey() {
        key = null
    }

    fun regenerateKey() {
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}

/**
 * Scriptable Steam: every protocol interaction is recorded, outcomes are configured per test.
 * Behaves like the real adapter at the contract level (status transitions, logon results, presence).
 */
class FakeSteamGateway : SteamGateway {
    private val _status = MutableStateFlow<GatewayStatus>(GatewayStatus.Disconnected(DisconnectReason.NeverConnected))
    override val status: StateFlow<GatewayStatus> = _status.asStateFlow()
    val eventFlow = MutableSharedFlow<SteamEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<SteamEvent> = eventFlow

    var connectFailures: ArrayDeque<SteamError> = ArrayDeque()
    var logOnResults: ArrayDeque<LogOnResult> = ArrayDeque()
    var defaultLogOn: LogOnResult = LogOnResult.Success(TEST_STEAM_ID)
    var qrPolls: ArrayDeque<PollResult> = ArrayDeque()
    var qrIntervalMillis = 5_000L
    var ownedGames: List<OwnedGame> = emptyList()
    var revokeAnswer = true
    var renewAnswer: Secret? = null
    var presenceFailure: SteamError? = null

    val connectCalls = MutableStateFlow(0)
    var logOnCalls = 0
    var qrPollCalls = 0
    val qrPollTimes = mutableListOf<Long>()
    var revokeCalls = 0
    var logOffCalls = 0
    val presenceHistory = mutableListOf<List<Int>>()
    val loggedOnTokens = mutableListOf<String>()
    var timeSource: () -> Long = { 0L }

    override suspend fun connect() {
        connectCalls.value++
        if (_status.value is GatewayStatus.Connected || _status.value is GatewayStatus.LoggedOn) return
        connectFailures.removeFirstOrNull()?.let {
            _status.value = GatewayStatus.Disconnected(DisconnectReason.TransportLost)
            throw SteamException(it)
        }
        _status.value = GatewayStatus.Connected
    }

    override suspend fun disconnect() {
        if (_status.value !is GatewayStatus.Disconnected) _status.value = GatewayStatus.Disconnected(DisconnectReason.UserInitiated)
    }

    override suspend fun beginQrLogin(deviceFriendlyName: String): QrLoginSession {
        if (_status.value !is GatewayStatus.Connected) throw SteamException(SteamError.ConnectionReset)
        val url = MutableStateFlow("https://s.team/q/1/1234567890")
        return object : QrLoginSession {
            override val challengeUrl: StateFlow<String> = url
            override val pollIntervalMillis: Long = qrIntervalMillis
            override suspend fun poll(): PollResult {
                qrPollCalls++
                qrPollTimes += timeSource()
                return qrPolls.removeFirstOrNull() ?: PollResult.Pending
            }
        }
    }

    // region credentials login (scriptable)

    var credentialsBeginError: SteamError? = null
    var credentialConfirmations: Set<GuardConfirmation> = setOf(GuardConfirmation.MOBILE_APP_APPROVAL, GuardConfirmation.MOBILE_APP_CODE)
    var credentialsEmailHint: String? = null
    /** Polls returned in order; after they run out: Pending, or Approved once a correct code was submitted. */
    var credentialPolls: ArrayDeque<PollResult> = ArrayDeque()
    var correctCode = "ABCDE"
    var approvalOnCorrectCode: PollResult.Approved? = null
    val passwordsSeenAtBegin = mutableListOf<String>()
    var passwordArrayGiven: CharArray? = null
    val submittedCodes = mutableListOf<Pair<String, GuardConfirmation>>()
    var credentialPollCalls = 0

    override suspend fun beginCredentialsLogin(accountName: String, password: CharArray, deviceFriendlyName: String): CredentialsLoginSession {
        if (_status.value !is GatewayStatus.Connected) throw SteamException(SteamError.ConnectionReset)
        passwordArrayGiven = password
        passwordsSeenAtBegin += String(password)
        credentialsBeginError?.let { throw SteamException(it) }
        var codeAccepted = false
        return object : CredentialsLoginSession {
            override val confirmations: Set<GuardConfirmation> = credentialConfirmations
            override val emailHint: String? = credentialsEmailHint
            override val pollIntervalMillis: Long = qrIntervalMillis
            override suspend fun poll(): PollResult {
                credentialPollCalls++
                credentialPolls.removeFirstOrNull()?.let { return it }
                return if (codeAccepted) approvalOnCorrectCode ?: PollResult.Pending else PollResult.Pending
            }
            override suspend fun submitCode(code: String, kind: GuardConfirmation): CodeSubmission {
                submittedCodes += code to kind
                return if (code == correctCode) CodeSubmission.Accepted.also { codeAccepted = true } else CodeSubmission.Rejected
            }
        }
    }

    // endregion

    override suspend fun logOn(accountName: String, refreshToken: Secret, loginId: Int): LogOnResult {
        logOnCalls++
        loggedOnTokens += refreshToken.reveal()
        if (_status.value !is GatewayStatus.Connected) return LogOnResult.Failure(SteamError.ConnectionReset)
        val result = logOnResults.removeFirstOrNull() ?: defaultLogOn
        if (result is LogOnResult.Success) _status.value = GatewayStatus.LoggedOn(result.steamId)
        return result
    }

    override suspend fun logOff() {
        logOffCalls++
        _status.value = GatewayStatus.Disconnected(DisconnectReason.UserInitiated)
    }

    override suspend fun fetchOwnedGames(steamId: SteamId, appIdsFilter: List<Int>): List<OwnedGame> {
        if (_status.value !is GatewayStatus.LoggedOn) throw SteamException(SteamError.ConnectionReset)
        return if (appIdsFilter.isEmpty()) ownedGames else ownedGames.filter { it.appId in appIdsFilter }
    }

    override suspend fun setGamesPlayed(appIds: List<Int>) {
        if (_status.value !is GatewayStatus.LoggedOn) throw SteamException(SteamError.ConnectionReset)
        presenceFailure?.let { throw SteamException(it) }
        presenceHistory += appIds
    }

    override suspend fun revokeRefreshToken(refreshToken: Secret): Boolean {
        revokeCalls++
        return _status.value is GatewayStatus.LoggedOn && revokeAnswer
    }

    override suspend fun renewRefreshToken(steamId: SteamId, refreshToken: Secret): Secret? = renewAnswer

    override suspend fun requestPersona(steamId: SteamId) = Unit

    // Test controls

    /** Simulates Steam/the network dropping the connection. */
    fun dropConnection(reason: DisconnectReason = DisconnectReason.TransportLost) {
        _status.value = GatewayStatus.Disconnected(reason)
    }

    fun forceLoggedOff(error: SteamError) {
        eventFlow.tryEmit(SteamEvent.LoggedOff(error))
        _status.value = GatewayStatus.Disconnected(DisconnectReason.LoggedOff(error))
    }
}

class InMemoryHistory : ActivityHistoryRepository {
    private var nextId = 1L
    val rows = MutableStateFlow<Map<Long, ActivitySessionRecord>>(emptyMap())
    val checkpoints = mutableListOf<Pair<Long, Long>>()

    override fun observeSessions(): Flow<List<ActivitySessionRecord>> = rows.map { it.values.sortedByDescending { r -> r.startedAtEpochMillis } }

    override suspend fun openSession(appIds: List<Int>, startedAtEpochMillis: Long): Long {
        val id = nextId++
        rows.value = rows.value + (id to ActivitySessionRecord(id, appIds, startedAtEpochMillis, null, 0, null))
        return id
    }

    override suspend fun checkpoint(sessionId: Long, activeMillis: Long, atEpochMillis: Long) {
        checkpoints += sessionId to activeMillis
        rows.value[sessionId]?.takeIf { it.endedAtEpochMillis == null }?.let {
            rows.value = rows.value + (sessionId to it.copy(activeMillis = activeMillis))
        }
    }

    override suspend fun closeSession(sessionId: Long, activeMillis: Long, endedAtEpochMillis: Long, reason: SessionEndReason) {
        rows.value[sessionId]?.takeIf { it.endedAtEpochMillis == null }?.let {
            rows.value = rows.value + (sessionId to it.copy(activeMillis = activeMillis, endedAtEpochMillis = endedAtEpochMillis, endReason = reason))
        }
    }

    override suspend fun closeInterruptedSessions(nowEpochMillis: Long): Int {
        val open = rows.value.values.filter { it.endedAtEpochMillis == null }
        open.forEach { rows.value = rows.value + (it.id to it.copy(endedAtEpochMillis = it.startedAtEpochMillis, endReason = SessionEndReason.INTERRUPTED)) }
        return open.size
    }

    override suspend fun clearAll() {
        rows.value = emptyMap()
    }
}
