package app.bepro.collector

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** One event as it will be uploaded. Times are epoch millis. */
data class Event(
  val category: String,
  val subCategory: String,
  val value: Double,
  val unit: String,
  val startedAt: Long,
  val endedAt: Long?,
  val source: String,
  val metadata: Map<String, Any> = emptyMap(),
)

data class BufferedEvent(val rowId: Long, val event: Event)

/**
 * Local SQLite buffer. Every reading lands here first and is only marked synced after the server
 * returns 200, so nothing is lost when the network or the process dies.
 *
 * Rows are unique on (source, category, sub_category, started_at), mirroring the server's dedupe
 * key: re-collecting an overlapping window replaces the row and marks it for upload again.
 */
class EventBuffer private constructor(context: Context) :
  SQLiteOpenHelper(context, "bepro_buffer.db", null, 1) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE pending_event (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        category TEXT NOT NULL,
        sub_category TEXT NOT NULL,
        value REAL NOT NULL,
        unit TEXT NOT NULL,
        started_at INTEGER NOT NULL,
        ended_at INTEGER,
        source TEXT NOT NULL,
        metadata TEXT NOT NULL,
        synced INTEGER NOT NULL DEFAULT 0,
        created_at INTEGER NOT NULL,
        UNIQUE (source, category, sub_category, started_at) ON CONFLICT REPLACE
      )
      """.trimIndent(),
    )
    db.execSQL("CREATE INDEX pending_event_synced ON pending_event (synced, id)")
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  fun add(events: List<Event>) {
    if (events.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      val now = System.currentTimeMillis()
      for (e in events) {
        if (isUnchangedAndSynced(db, e)) continue
        db.insert(
          "pending_event",
          null,
          ContentValues().apply {
            put("category", e.category)
            put("sub_category", e.subCategory)
            put("value", e.value)
            put("unit", e.unit)
            put("started_at", e.startedAt)
            put("ended_at", e.endedAt)
            put("source", e.source)
            put("metadata", JSONObject(e.metadata).toString())
            put("synced", 0)
            put("created_at", now)
          },
        )
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  /** Re-collected windows repeat many rows; skip ones the server already has, to save mobile data. */
  private fun isUnchangedAndSynced(db: SQLiteDatabase, e: Event): Boolean =
    db.rawQuery(
      "SELECT value, ended_at FROM pending_event " +
        "WHERE source = ? AND category = ? AND sub_category = ? AND started_at = ? AND synced = 1",
      arrayOf(e.source, e.category, e.subCategory, e.startedAt.toString()),
    ).use { c ->
      c.moveToFirst() && c.getDouble(0) == e.value && (if (c.isNull(1)) null else c.getLong(1)) == e.endedAt
    }

  fun pending(limit: Int): List<BufferedEvent> {
    val result = ArrayList<BufferedEvent>()
    readableDatabase.rawQuery(
      "SELECT id, category, sub_category, value, unit, started_at, ended_at, source, metadata " +
        "FROM pending_event WHERE synced = 0 ORDER BY id LIMIT ?",
      arrayOf(limit.toString()),
    ).use { c ->
      while (c.moveToNext()) {
        val metadata = JSONObject(c.getString(8)).let { json -> json.keys().asSequence().associateWith { json.get(it) } }
        result += BufferedEvent(
          c.getLong(0),
          Event(
            category = c.getString(1),
            subCategory = c.getString(2),
            value = c.getDouble(3),
            unit = c.getString(4),
            startedAt = c.getLong(5),
            endedAt = if (c.isNull(6)) null else c.getLong(6),
            source = c.getString(7),
            metadata = metadata,
          ),
        )
      }
    }
    return result
  }

  /**
   * Marks uploaded rows. A row replaced after it was read gets a new id, so it stays pending —
   * exactly what we want, since the newer value hasn't been uploaded yet.
   */
  fun markSynced(rowIds: List<Long>) {
    if (rowIds.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
      rowIds.chunked(500).forEach { chunk ->
        db.execSQL("UPDATE pending_event SET synced = 1 WHERE id IN (${chunk.joinToString(",")})")
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun pendingCount(): Int =
    readableDatabase.rawQuery("SELECT count(*) FROM pending_event WHERE synced = 0", null).use { c ->
      c.moveToFirst()
      c.getInt(0)
    }

  /** Keep synced rows for a week (handy for debugging), then drop them. */
  fun prune(olderThanMillis: Long) {
    writableDatabase.execSQL("DELETE FROM pending_event WHERE synced = 1 AND created_at < ?", arrayOf(olderThanMillis))
  }

  fun clear() {
    writableDatabase.execSQL("DELETE FROM pending_event")
  }

  companion object {
    @Volatile private var instance: EventBuffer? = null

    fun get(context: Context): EventBuffer =
      instance ?: synchronized(this) {
        instance ?: EventBuffer(context.applicationContext).also { instance = it }
      }
  }
}
