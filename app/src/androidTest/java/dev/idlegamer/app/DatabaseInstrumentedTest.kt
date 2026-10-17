package dev.idlegamer.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.idlegamer.app.data.local.ActivitySessionEntity
import dev.idlegamer.app.data.local.AppDatabase
import dev.idlegamer.app.data.local.EventLogEntity
import dev.idlegamer.app.data.local.GameEntity
import dev.idlegamer.app.data.local.SelectedGameEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @After fun close() = db.close()

    private fun game(id: Int, manual: Boolean = false) = GameEntity(id, "Game $id", null, 10, 0, 0, manual, 1)

    @Test fun refreshKeepsSelectionAndManualEntries() = runBlocking {
        val dao = db.games()
        dao.replaceOwned(listOf(game(440), game(570)), refreshedAt = 100)
        dao.insertIgnore(game(999, manual = true))
        dao.select(SelectedGameEntity(570, 1))
        dao.select(SelectedGameEntity(999, 2))

        // Steam no longer reports 440: it disappears, selection and manual entry survive.
        dao.replaceOwned(listOf(game(570), game(730)), refreshedAt = 200)
        val rows = dao.observeAll().first()
        assertEquals(setOf(570, 730, 999), rows.map { it.game.appId }.toSet())
        assertEquals(setOf(570, 999), rows.filter { it.isSelected }.map { it.game.appId }.toSet())
        assertEquals(200L, dao.observeMeta("library_last_refresh_ms").first())
    }

    @Test fun openSessionsAreClosedAsInterruptedAtTheirCheckpoint() = runBlocking {
        val dao = db.sessions()
        val id = dao.insert(ActivitySessionEntity(appIds = "440", startedAtEpochMillis = 1000, endedAtEpochMillis = null, activeMillis = 0, lastCheckpointEpochMillis = 1000, endReason = null))
        dao.checkpoint(id, activeMillis = 120_000, at = 121_000)
        assertEquals(1, dao.closeAllOpen("INTERRUPTED"))
        val row = dao.observeRecent().first().single()
        assertEquals(121_000L, row.endedAtEpochMillis)
        assertEquals(120_000L, row.activeMillis)
        assertEquals("INTERRUPTED", row.endReason)
        // A closed session can no longer be modified by a late checkpoint or close.
        dao.checkpoint(id, 999_999, 999_999)
        dao.close(id, 999_999, 999_999, "USER_STOPPED_UI")
        assertEquals(120_000L, dao.observeRecent().first().single().activeMillis)
    }

    @Test fun eventLogIsBounded() = runBlocking {
        val dao = db.events()
        repeat(50) { dao.insert(EventLogEntity(timestampEpochMillis = it.toLong(), type = "T", message = "m$it")) }
        dao.deleteOlderThan(10)
        dao.keepNewest(20)
        val left = dao.recent(100)
        assertEquals(20, left.size)
        assertTrue(left.all { it.timestampEpochMillis >= 30 })
    }

    @Test fun clearAllRemovesAccountData() = runBlocking {
        val dao = db.games()
        dao.replaceOwned(listOf(game(440)), 1)
        dao.select(SelectedGameEntity(440, 1))
        dao.clearAll()
        assertEquals(0, dao.observeAll().first().size)
        assertNull(dao.observeMeta("library_last_refresh_ms").first())
    }

    @Test fun exportedSchemaV1OpensWithRegisteredMigrations() {
        migrations.createDatabase("migration-test", 1).close()
        Room.databaseBuilder(context, AppDatabase::class.java, "migration-test")
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()
            .apply { openHelper.writableDatabase.close() }
            .close()
        context.deleteDatabase("migration-test")
    }
}
