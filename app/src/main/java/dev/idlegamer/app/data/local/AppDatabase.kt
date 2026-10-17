package dev.idlegamer.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

/**
 * Structured, non-secret data only. Schema history is exported to `app/schemas/` and every version bump
 * must ship an explicit [Migration] in [AppDatabase.MIGRATIONS]; destructive fallback is never enabled,
 * so a failed migration surfaces as an error instead of silently deleting the user's history (R7).
 */
@Database(
    entities = [GameEntity::class, SelectedGameEntity::class, ActivitySessionEntity::class, EventLogEntity::class, SyncMetaEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun sessions(): ActivitySessionDao
    abstract fun events(): EventLogDao

    companion object {
        const val NAME = "idlegamer.db"
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey @ColumnInfo(name = "app_id") val appId: Int,
    val name: String,
    @ColumnInfo(name = "icon_hash") val iconHash: String?,
    @ColumnInfo(name = "playtime_forever_min") val playtimeForeverMinutes: Int?,
    @ColumnInfo(name = "playtime_2weeks_min") val playtimeTwoWeeksMinutes: Int?,
    @ColumnInfo(name = "last_played_epoch_s") val lastPlayedEpochSeconds: Long?,
    @ColumnInfo(name = "is_manual") val isManual: Boolean,
    @ColumnInfo(name = "last_updated_ms") val lastUpdatedEpochMillis: Long,
)

@Entity(tableName = "selected_games")
data class SelectedGameEntity(
    @PrimaryKey @ColumnInfo(name = "app_id") val appId: Int,
    @ColumnInfo(name = "selected_at_ms") val selectedAtEpochMillis: Long,
)

@Entity(tableName = "activity_sessions", indices = [Index("started_at_ms"), Index("ended_at_ms")])
data class ActivitySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Comma-separated app ids, e.g. "440,570". */
    @ColumnInfo(name = "app_ids") val appIds: String,
    @ColumnInfo(name = "started_at_ms") val startedAtEpochMillis: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtEpochMillis: Long?,
    @ColumnInfo(name = "active_ms") val activeMillis: Long,
    @ColumnInfo(name = "last_checkpoint_ms") val lastCheckpointEpochMillis: Long?,
    @ColumnInfo(name = "end_reason") val endReason: String?,
)

@Entity(tableName = "event_log", indices = [Index("timestamp_ms")])
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_ms") val timestampEpochMillis: Long,
    val type: String,
    val message: String,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: Long,
)

data class GameWithSelection(
    @androidx.room.Embedded val game: GameEntity,
    @ColumnInfo(name = "is_selected") val isSelected: Boolean,
)

@Dao
interface GameDao {
    @Query(
        """
        SELECT g.*, (s.app_id IS NOT NULL) AS is_selected
        FROM games g LEFT JOIN selected_games s ON s.app_id = g.app_id
        ORDER BY g.name COLLATE NOCASE
        """,
    )
    fun observeAll(): Flow<List<GameWithSelection>>

    @Upsert
    suspend fun upsertAll(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE is_manual = 0 AND app_id NOT IN (:keep)")
    suspend fun deleteOwnedNotIn(keep: List<Int>)

    @Query("SELECT * FROM games WHERE app_id = :appId")
    suspend fun find(appId: Int): GameEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(game: GameEntity)

    @Query("DELETE FROM games WHERE app_id = :appId AND is_manual = 1")
    suspend fun deleteManual(appId: Int)

    @Query("UPDATE games SET playtime_forever_min = :minutes, last_updated_ms = :at WHERE app_id = :appId")
    suspend fun updatePlaytime(appId: Int, minutes: Int, at: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun select(entity: SelectedGameEntity)

    @Query("DELETE FROM selected_games WHERE app_id = :appId")
    suspend fun unselect(appId: Int)

    @Query("DELETE FROM selected_games")
    suspend fun clearSelection()

    @Query("SELECT app_id FROM selected_games ORDER BY selected_at_ms")
    suspend fun selectedIds(): List<Int>

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    fun observeMeta(key: String): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(entity: SyncMetaEntity)

    @Query("DELETE FROM games")
    suspend fun deleteAllGames()

    @Query("DELETE FROM sync_meta")
    suspend fun deleteAllMeta()

    @Transaction
    suspend fun replaceOwned(games: List<GameEntity>, refreshedAt: Long) {
        upsertAll(games)
        deleteOwnedNotIn(games.map { it.appId })
        putMeta(SyncMetaEntity(META_LAST_REFRESH, refreshedAt))
    }

    @Transaction
    suspend fun clearAll() {
        clearSelection()
        deleteAllGames()
        deleteAllMeta()
    }

    companion object {
        const val META_LAST_REFRESH = "library_last_refresh_ms"
    }
}

@Dao
interface ActivitySessionDao {
    @Query("SELECT * FROM activity_sessions ORDER BY started_at_ms DESC LIMIT 500")
    fun observeRecent(): Flow<List<ActivitySessionEntity>>

    @Insert
    suspend fun insert(entity: ActivitySessionEntity): Long

    @Query("UPDATE activity_sessions SET active_ms = :activeMillis, last_checkpoint_ms = :at WHERE id = :id AND ended_at_ms IS NULL")
    suspend fun checkpoint(id: Long, activeMillis: Long, at: Long)

    @Query("UPDATE activity_sessions SET active_ms = :activeMillis, ended_at_ms = :endedAt, end_reason = :reason WHERE id = :id AND ended_at_ms IS NULL")
    suspend fun close(id: Long, activeMillis: Long, endedAt: Long, reason: String)

    /** Interrupted sessions keep their checkpointed active_ms; the end time is the last checkpoint, not "now". */
    @Query(
        """
        UPDATE activity_sessions
        SET ended_at_ms = COALESCE(last_checkpoint_ms, started_at_ms), end_reason = :reason
        WHERE ended_at_ms IS NULL
        """,
    )
    suspend fun closeAllOpen(reason: String): Int

    @Query("DELETE FROM activity_sessions")
    suspend fun deleteAll()
}

@Dao
interface EventLogDao {
    @Query("SELECT * FROM event_log ORDER BY timestamp_ms DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<EventLogEntity>>

    @Query("SELECT * FROM event_log ORDER BY timestamp_ms DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventLogEntity>

    @Insert
    suspend fun insert(entity: EventLogEntity)

    @Query("DELETE FROM event_log WHERE timestamp_ms < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM event_log WHERE id NOT IN (SELECT id FROM event_log ORDER BY timestamp_ms DESC, id DESC LIMIT :keep)")
    suspend fun keepNewest(keep: Int)

    @Query("DELETE FROM event_log")
    suspend fun deleteAll()
}
