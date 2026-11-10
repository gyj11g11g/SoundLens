package com.soundlens.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Label(val id: Int, val name: String, val createdAt: Long)
data class Clip(val id: Int, val path: String, val labelId: Int, val createdAt: Long)

/**
 * Plain SQLite storage (no ORM) for labels and recorded clips.
 * label_id = 0 means "未标注" (unlabeled).
 */
class Db(context: Context) : SQLiteOpenHelper(context, "soundlens.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE labels(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, label_id INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: nothing to migrate
    }

    // ---- Labels ----

    fun addLabel(name: String): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict("labels", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getLabels(): List<Label> {
        val out = mutableListOf<Label>()
        readableDatabase.rawQuery("SELECT id, name, created_at FROM labels ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) {
                out.add(Label(c.getInt(0), c.getString(1), c.getLong(2)))
            }
        }
        return out
    }

    fun deleteLabel(id: Int) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("UPDATE clips SET label_id = 0 WHERE label_id = ?", arrayOf(id))
            writableDatabase.execSQL("DELETE FROM labels WHERE id = ?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // ---- Clips ----

    fun addClip(path: String, labelId: Int): Long {
        val cv = ContentValues().apply {
            put("path", path)
            put("label_id", labelId)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("clips", null, cv)
    }

    fun getClips(limit: Int = 500): List<Clip> {
        val out = mutableListOf<Clip>()
        readableDatabase.rawQuery(
            "SELECT id, path, label_id, created_at FROM clips ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Clip(c.getInt(0), c.getString(1), c.getInt(2), c.getLong(3)))
            }
        }
        return out
    }

    fun getClipsWithLabel(limit: Int = 500): List<Triple<Clip, String, Long>> {
        val out = mutableListOf<Triple<Clip, String, Long>>()
        readableDatabase.rawQuery(
            "SELECT c.id, c.path, c.label_id, c.created_at, IFNULL(l.name, '未标注') FROM clips c " +
                "LEFT JOIN labels l ON l.id = c.label_id ORDER BY c.id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Triple(
                        Clip(c.getInt(0), c.getString(1), c.getInt(2), c.getLong(3)),
                        c.getString(4),
                        c.getLong(3)
                    )
                )
            }
        }
        return out
    }

    fun updateClipLabel(clipId: Int, labelId: Int) {
        writableDatabase.execSQL("UPDATE clips SET label_id = ? WHERE id = ?", arrayOf(labelId, clipId))
    }

    fun deleteClip(clipId: Int, path: String) {
        writableDatabase.execSQL("DELETE FROM clips WHERE id = ?", arrayOf(clipId))
        try { java.io.File(path).delete() } catch (_: Exception) {}
    }

    /** Sample counts per label id for the training screen. */
    fun labeledCounts(): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        readableDatabase.rawQuery(
            "SELECT label_id, COUNT(*) FROM clips WHERE label_id > 0 GROUP BY label_id", null
        ).use { c ->
            while (c.moveToNext()) out[c.getInt(0)] = c.getInt(1)
        }
        return out
    }

    fun labeledClips(): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        readableDatabase.rawQuery(
            "SELECT label_id, path FROM clips WHERE label_id > 0 ORDER BY id ASC", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getInt(0) to c.getString(1))
        }
        return out
    }

    fun clipCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM clips", null).use { c ->
            c.moveToFirst()
            return c.getInt(0)
        }
    }
}
