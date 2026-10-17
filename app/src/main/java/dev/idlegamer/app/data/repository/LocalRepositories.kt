package dev.idlegamer.app.data.repository

import dev.idlegamer.app.core.logging.Redactor
import dev.idlegamer.app.core.time.Clock
import dev.idlegamer.app.data.local.ActivitySessionDao
import dev.idlegamer.app.data.local.ActivitySessionEntity
import dev.idlegamer.app.data.local.EventLogDao
import dev.idlegamer.app.data.local.EventLogEntity
import dev.idlegamer.app.data.local.GameDao
import dev.idlegamer.app.data.local.GameEntity
import dev.idlegamer.app.data.local.SelectedGameEntity
import dev.idlegamer.app.data.steam.SteamGateway
import dev.idlegamer.app.domain.model.ActivitySessionRecord
import dev.idlegamer.app.domain.model.EventLogEntry
import dev.idlegamer.app.domain.model.Game
import dev.idlegamer.app.domain.model.SessionEndReason
import dev.idlegamer.app.domain.model.SteamId
import dev.idlegamer.app.domain.repository.ActivityHistoryRepository
import dev.idlegamer.app.domain.repository.EventLogRepository
import dev.idlegamer.app.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomGameRepository(
    private val dao: GameDao,
    private val gateway: SteamGateway,
    private val clock: Clock,
) : GameRepository {

    override fun observeGames(): Flow<List<Game>> = dao.observeAll().map { rows ->
        rows.map { row ->
            val g = row.game
            Game(
                appId = g.appId,
                name = g.name,
                iconUrl = Game.iconUrl(g.appId, g.iconHash),
                headerImageUrl = if (g.isManual) null else Game.headerUrl(g.appId),
                ownership = if (g.isManual) Game.Ownership.UNKNOWN else Game.Ownership.OWNED,
                playtimeForeverMinutes = g.playtimeForeverMinutes,
                playtimeTwoWeeksMinutes = g.playtimeTwoWeeksMinutes,
                lastPlayedEpochSeconds = g.lastPlayedEpochSeconds?.takeIf { it > 0 },
                lastUpdatedEpochMillis = g.lastUpdatedEpochMillis,
                isSelected = row.isSelected,
            )
        }
    }

    override fun observeLastRefresh(): Flow<Long?> = dao.observeMeta(GameDao.META_LAST_REFRESH)

    override suspend fun refreshFromSteam(steamId: SteamId) {
        val now = clock.currentTimeMillis()
        val owned = gateway.fetchOwnedGames(steamId)
        val entities = owned.map { o ->
            GameEntity(
                appId = o.appId,
                name = o.name,
                iconHash = o.iconHash,
                playtimeForeverMinutes = o.playtimeForeverMinutes,
                playtimeTwoWeeksMinutes = o.playtimeTwoWeeksMinutes,
                lastPlayedEpochSeconds = o.lastPlayedEpochSeconds,
                isManual = false,
                lastUpdatedEpochMillis = now,
            )
        }
        dao.replaceOwned(entities, now)
    }

    override suspend fun verifyPlaytime(steamId: SteamId, appIds: List<Int>): Map<Int, Int> {
        if (appIds.isEmpty()) return emptyMap()
        val now = clock.currentTimeMillis()
        val result = gateway.fetchOwnedGames(steamId, appIds).associate { it.appId to it.playtimeForeverMinutes }
        result.forEach { (appId, minutes) -> dao.updatePlaytime(appId, minutes, now) }
        return result
    }

    override suspend fun setSelected(appId: Int, selected: Boolean) {
        if (selected) dao.select(SelectedGameEntity(appId, clock.currentTimeMillis())) else dao.unselect(appId)
    }

    override suspend fun clearSelection() = dao.clearSelection()

    override suspend fun selectedAppIds(): List<Int> = dao.selectedIds()

    override suspend fun addManualGame(appId: Int) {
        dao.insertIgnore(
            GameEntity(
                appId = appId,
                name = "App $appId",
                iconHash = null,
                playtimeForeverMinutes = null,
                playtimeTwoWeeksMinutes = null,
                lastPlayedEpochSeconds = null,
                isManual = true,
                lastUpdatedEpochMillis = clock.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeManualGame(appId: Int) {
        dao.unselect(appId)
        dao.deleteManual(appId)
    }

    override suspend fun clearAll() = dao.clearAll()
}

class RoomActivityHistoryRepository(private val dao: ActivitySessionDao) : ActivityHistoryRepository {

    override fun observeSessions(): Flow<List<ActivitySessionRecord>> = dao.observeRecent().map { rows ->
        rows.map { e ->
            ActivitySessionRecord(
                id = e.id,
                appIds = e.appIds.split(',').mapNotNull { it.toIntOrNull() },
                startedAtEpochMillis = e.startedAtEpochMillis,
                endedAtEpochMillis = e.endedAtEpochMillis,
                activeMillis = e.activeMillis,
                endReason = e.endReason?.let { runCatching { SessionEndReason.valueOf(it) }.getOrNull() },
            )
        }
    }

    override suspend fun openSession(appIds: List<Int>, startedAtEpochMillis: Long): Long = dao.insert(
        ActivitySessionEntity(
            appIds = appIds.joinToString(","),
            startedAtEpochMillis = startedAtEpochMillis,
            endedAtEpochMillis = null,
            activeMillis = 0,
            lastCheckpointEpochMillis = startedAtEpochMillis,
            endReason = null,
        ),
    )

    override suspend fun checkpoint(sessionId: Long, activeMillis: Long, atEpochMillis: Long) =
        dao.checkpoint(sessionId, activeMillis, atEpochMillis)

    override suspend fun closeSession(sessionId: Long, activeMillis: Long, endedAtEpochMillis: Long, reason: SessionEndReason) =
        dao.close(sessionId, activeMillis, endedAtEpochMillis, reason.name)

    override suspend fun closeInterruptedSessions(nowEpochMillis: Long): Int =
        dao.closeAllOpen(SessionEndReason.INTERRUPTED.name)

    override suspend fun clearAll() = dao.deleteAll()
}

class RoomEventLogRepository(private val dao: EventLogDao) : EventLogRepository {
    override fun observeRecent(limit: Int): Flow<List<EventLogEntry>> =
        dao.observeRecent(limit).map { rows -> rows.map { EventLogEntry(it.id, it.timestampEpochMillis, it.type, it.message) } }

    override suspend fun append(type: String, message: String, atEpochMillis: Long) {
        dao.insert(EventLogEntity(timestampEpochMillis = atEpochMillis, type = type, message = Redactor.redact(message)))
    }

    override suspend fun recent(limit: Int): List<EventLogEntry> =
        dao.recent(limit).map { EventLogEntry(it.id, it.timestampEpochMillis, it.type, it.message) }

    override suspend fun prune(olderThanEpochMillis: Long, keepAtMost: Int) {
        dao.deleteOlderThan(olderThanEpochMillis)
        dao.keepNewest(keepAtMost)
    }

    override suspend fun clearAll() = dao.deleteAll()
}
