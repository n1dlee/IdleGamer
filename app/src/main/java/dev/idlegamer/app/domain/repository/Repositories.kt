package dev.idlegamer.app.domain.repository

import dev.idlegamer.app.domain.model.ActivitySessionRecord
import dev.idlegamer.app.domain.model.EventLogEntry
import dev.idlegamer.app.domain.model.Game
import dev.idlegamer.app.domain.model.SessionCredentials
import dev.idlegamer.app.domain.model.SessionEndReason
import dev.idlegamer.app.domain.model.SteamId
import kotlinx.coroutines.flow.Flow

/**
 * The only place credentials are persisted. Implementations must encrypt at rest.
 *
 * [load] returns null when nothing is stored and throws [CredentialStoreException] when something is
 * stored but cannot be read — callers must then [clear] and ask the user to sign in again.
 */
interface SecureCredentialStore {
    suspend fun save(credentials: SessionCredentials)
    suspend fun load(): SessionCredentials?
    suspend fun clear()
}

class CredentialStoreException(val kind: Kind, cause: Throwable? = null) :
    Exception("Credential store failure: $kind", cause) {
    enum class Kind {
        /** Ciphertext failed authentication or the envelope is malformed. */
        CORRUPTED,

        /** The Keystore key is missing, invalidated or unusable. */
        KEY_UNAVAILABLE,

        /** File-system error writing the vault. */
        IO,
    }
}

interface GameRepository {
    fun observeGames(): Flow<List<Game>>

    /** Epoch millis of the last successful refresh from Steam, null if never. */
    fun observeLastRefresh(): Flow<Long?>

    /** Fetches the owned-games list from Steam and replaces the cached OWNED rows. */
    suspend fun refreshFromSteam(steamId: SteamId)

    /** Re-reads playtime for [appIds] from Steam; returns appId -> playtime_forever minutes. */
    suspend fun verifyPlaytime(steamId: SteamId, appIds: List<Int>): Map<Int, Int>

    suspend fun setSelected(appId: Int, selected: Boolean)
    suspend fun clearSelection()
    suspend fun selectedAppIds(): List<Int>
    suspend fun addManualGame(appId: Int)
    suspend fun removeManualGame(appId: Int)
    suspend fun clearAll()
}

interface ActivityHistoryRepository {
    fun observeSessions(): Flow<List<ActivitySessionRecord>>
    suspend fun openSession(appIds: List<Int>, startedAtEpochMillis: Long): Long
    suspend fun checkpoint(sessionId: Long, activeMillis: Long, atEpochMillis: Long)
    suspend fun closeSession(sessionId: Long, activeMillis: Long, endedAtEpochMillis: Long, reason: SessionEndReason)

    /**
     * Closes every session left open by a previous process as [SessionEndReason.INTERRUPTED], keeping the
     * duration recorded at its last checkpoint. Returns how many were closed.
     */
    suspend fun closeInterruptedSessions(nowEpochMillis: Long): Int
    suspend fun clearAll()
}

interface EventLogRepository {
    fun observeRecent(limit: Int): Flow<List<EventLogEntry>>
    suspend fun append(type: String, message: String, atEpochMillis: Long)
    suspend fun recent(limit: Int): List<EventLogEntry>
    suspend fun prune(olderThanEpochMillis: Long, keepAtMost: Int)
    suspend fun clearAll()
}
