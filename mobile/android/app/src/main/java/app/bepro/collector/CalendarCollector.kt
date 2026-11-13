package app.bepro.collector

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

/**
 * Treats calendar blocks whose title looks like deep work ("Focus", "Deep work", "Study"…) as focus
 * sessions. Only the matched keyword is uploaded, never the event title.
 */
class CalendarCollector(private val context: Context, private val settings: Settings) : Collector {

  override val name = "calendar"

  override suspend fun collect(now: Long): CollectResult {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
      return CollectResult.EMPTY
    }
    val from = maxOf(settings.calendarCursor - DAY_MS, now - 3 * DAY_MS)
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
      ContentUris.appendId(it, from)
      ContentUris.appendId(it, now)
    }.build()

    val events = ArrayList<Event>()
    context.contentResolver.query(
      uri,
      arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
      "${CalendarContract.Instances.ALL_DAY} = 0",
      null,
      null,
    )?.use { c ->
      while (c.moveToNext()) {
        val title = c.getString(0) ?: continue
        val begin = c.getLong(1)
        val end = c.getLong(2)
        if (end > now || end <= begin || end - begin > 8 * HOUR_MS) continue
        val keyword = FOCUS_KEYWORDS.firstOrNull { title.contains(it, ignoreCase = true) } ?: continue
        events += Event(
          category = "focus",
          subCategory = "calendar",
          value = (end - begin) / 60_000.0,
          unit = "minutes",
          startedAt = begin,
          endedAt = end,
          source = "calendar",
          metadata = mapOf("keyword" to keyword.lowercase()),
        )
      }
    }
    return CollectResult(events) { settings.calendarCursor = now }
  }

  private companion object {
    const val HOUR_MS = 60 * 60 * 1000L
    const val DAY_MS = 24 * HOUR_MS
    val FOCUS_KEYWORDS = listOf("deep work", "deep-work", "focus", "study", "coding", "writing", "no meetings")
  }
}
