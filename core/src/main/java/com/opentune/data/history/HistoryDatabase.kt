package com.opentune.data.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.json.Json

/**
 * Indexed, disk-backed listening history. The existing history.json remains the
 * canonical recovery copy during the migration; no old data is deleted.
 *
 * The database is intentionally populated in a transaction and is only marked
 * ready once its record count matches the imported snapshot.
 */
internal class HistoryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "listening_history.db", null, 1) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE listens (
                position INTEGER PRIMARY KEY,
                video_id TEXT NOT NULL,
                played_at INTEGER NOT NULL,
                payload TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX listens_by_date ON listens(played_at DESC)")
        db.execSQL("CREATE INDEX listens_by_song ON listens(video_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Never drop listening history during an upgrade.
        throw IllegalStateException("Unsupported history database migration: $oldVersion -> $newVersion")
    }

    /** Atomically mirrors the current history, keeping the original JSON untouched. */
    @Synchronized
    fun replaceAll(records: List<PlayRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("listens", null, null)
            val statement = db.compileStatement(
                "INSERT INTO listens(position, video_id, played_at, payload) VALUES(?, ?, ?, ?)"
            )
            records.forEachIndexed { index, record ->
                statement.clearBindings()
                statement.bindLong(1, index.toLong())
                statement.bindString(2, record.videoId)
                statement.bindLong(3, record.playedAt)
                statement.bindString(4, json.encodeToString(PlayRecord.serializer(), record))
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Read a small window without deserializing the full Spotify archive. */
    @Synchronized
    fun recent(limit: Int): List<PlayRecord> {
        if (limit <= 0) return emptyList()
        val result = ArrayList<PlayRecord>(limit)
        readableDatabase.rawQuery(
            "SELECT payload FROM listens ORDER BY position ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(json.decodeFromString(PlayRecord.serializer(), cursor.getString(0)))
            }
        }
        return result
    }

    /** Restore the existing public history API from the indexed store. */
    @Synchronized
    fun all(): List<PlayRecord> {
        val result = ArrayList<PlayRecord>()
        readableDatabase.rawQuery(
            "SELECT payload FROM listens ORDER BY position ASC", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(json.decodeFromString(PlayRecord.serializer(), cursor.getString(0)))
            }
        }
        return result
    }

    @Synchronized
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM listens", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }
}
