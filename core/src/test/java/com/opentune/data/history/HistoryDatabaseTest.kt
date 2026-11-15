package com.opentune.data.history

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HistoryDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun record(at: Long, ms: Long = 30_000) = PlayRecord("song-$at", "Song", "Artist", playedAt = at, listenedMs = ms)
    private fun encode(records: List<PlayRecord>) = Json.encodeToString(ListSerializer(PlayRecord.serializer()), records)

    @Test fun migrationIncrementalWritesAndClearSurviveRestartWithoutChangingBackup() {
        val name = "history-test-${UUID.randomUUID()}.db"
        val legacy = File(context.cacheDir, "history-${UUID.randomUUID()}.json")
        val backup = encode(listOf(record(3), record(2), record(1)))
        legacy.writeText(backup)
        try {
            val db = HistoryDatabase(context, name)
            assertEquals(listOf(3L, 2L, 1L), db.load(legacy, 3).map { it.playedAt })
            db.add(listOf(record(4)), 3)
            db.updateListened(4, 80_000)
            db.close()
            val reopened = HistoryDatabase(context, name)
            val saved = reopened.load(legacy, 3)
            assertEquals(listOf(4L, 3L, 2L), saved.map { it.playedAt })
            assertEquals(80_000L, saved.first().listenedMs)
            reopened.replace(emptyList())
            reopened.close()
            val afterClear = HistoryDatabase(context, name)
            assertTrue(afterClear.load(legacy, 3).isEmpty())
            afterClear.close()
            assertEquals(backup, legacy.readText())
        } finally {
            context.deleteDatabase(name)
            legacy.delete()
        }
    }

    @Test fun failedMigrationKeepsOriginalAndCanBeRetried() {
        val name = "history-test-${UUID.randomUUID()}.db"
        val legacy = File(context.cacheDir, "history-${UUID.randomUUID()}.json")
        legacy.writeText("broken data")
        try {
            val db = HistoryDatabase(context, name)
            var failed = false
            try { db.load(legacy, 100) } catch (_: Exception) { failed = true }
            assertTrue(failed)
            assertEquals("broken data", legacy.readText())
            legacy.writeText(encode(listOf(record(12))))
            assertEquals(listOf(12L), db.load(legacy, 100).map { it.playedAt })
            db.close()
        } finally {
            context.deleteDatabase(name)
            legacy.delete()
        }
    }

    @Test fun largeArchiveKeepsTheLatestHundredThousand() {
        val name = "history-test-${UUID.randomUUID()}.db"
        val legacy = File(context.cacheDir, "history-${UUID.randomUUID()}.json")
        try {
            val db = HistoryDatabase(context, name)
            assertTrue(db.load(legacy, 100_000).isEmpty())
            db.add((1L..100_000L).map { record(it) }, 100_000)
            db.add(listOf(record(100_001), record(0)), 100_000)
            db.close()
            val reopened = HistoryDatabase(context, name)
            val saved = reopened.load(legacy, 100_000)
            assertEquals(100_000, saved.size)
            assertEquals(100_001L, saved.first().playedAt)
            assertEquals(2L, saved.last().playedAt)
            assertFalse(legacy.exists())
            reopened.close()
        } finally {
            context.deleteDatabase(name)
            legacy.delete()
        }
    }
}
