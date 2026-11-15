package com.opentune.data.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A new store, separate from the abandoned listening_history.db experiment.
 * The old JSON and database are left untouched as recovery copies. Migration
 * and the initialized marker commit in the same transaction.
 */
internal class HistoryDatabase(context: Context, name: String = "listening_history_v2.db") :
    SQLiteOpenHelper(context.applicationContext, name, null, 1) {
    private val json = Json { ignoreUnknownKeys = true }
    private var size = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE listens (id INTEGER PRIMARY KEY, played_at INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX listens_by_date ON listens(played_at DESC, id DESC)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported history database migration: $oldVersion -> $newVersion")
    }

    /** Called off the main thread. A failed migration never changes the source JSON. */
    fun load(legacy: File, limit: Int): List<PlayRecord> {
        val db = writableDatabase
        val initialized = db.rawQuery("SELECT 1 FROM metadata WHERE key='initialized'", null).use { it.moveToFirst() }
        if (!initialized) {
            val initial = if (legacy.exists()) {
                json.decodeFromString(ListSerializer(PlayRecord.serializer()), legacy.readText())
                    .sortedByDescending { it.playedAt }.take(limit)
            } else emptyList()
            replace(initial)
            return initial
        }
        val records = ArrayList<PlayRecord>()
        db.rawQuery("SELECT payload FROM listens ORDER BY played_at DESC, id DESC", null).use { cursor ->
            while (cursor.moveToNext()) records += json.decodeFromString(PlayRecord.serializer(), cursor.getString(0))
        }
        size = records.size
        return records
    }

    /** An import or restore is one transaction; a crash leaves the old state intact. */
    fun replace(records: List<PlayRecord>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("listens", null, null)
            // Reversing preserves the input order when timestamps are equal.
            insert(db, records.asReversed())
            db.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES('initialized','1')")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        size = records.size
    }

    /** Only new listens are written, even with a large imported archive. */
    fun add(records: List<PlayRecord>, limit: Int) {
        if (records.isEmpty()) return
        val db = writableDatabase
        val excess = (size + records.size - limit).coerceAtLeast(0)
        db.beginTransaction()
        try {
            insert(db, records.asReversed())
            if (excess > 0) db.execSQL(
                "DELETE FROM listens WHERE id IN (SELECT id FROM listens ORDER BY played_at ASC, id ASC LIMIT ?)",
                arrayOf(excess),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        size = (size + records.size).coerceAtMost(limit)
    }

    fun updateListened(playedAt: Long, listenedMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val updates = ArrayList<Pair<Long, PlayRecord>>()
            db.rawQuery("SELECT id, payload FROM listens WHERE played_at=?", arrayOf(playedAt.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    updates += cursor.getLong(0) to json.decodeFromString(PlayRecord.serializer(), cursor.getString(1)).copy(listenedMs = listenedMs)
                }
            }
            db.compileStatement("UPDATE listens SET payload=? WHERE id=?").use { statement ->
                for ((id, record) in updates) {
                    statement.bindString(1, json.encodeToString(PlayRecord.serializer(), record))
                    statement.bindLong(2, id)
                    statement.executeUpdateDelete()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insert(db: SQLiteDatabase, records: List<PlayRecord>) {
        db.compileStatement("INSERT INTO listens(played_at,payload) VALUES(?,?)").use { statement ->
            for (record in records) {
                statement.bindLong(1, record.playedAt)
                statement.bindString(2, json.encodeToString(PlayRecord.serializer(), record))
                statement.executeInsert()
            }
        }
    }
}
