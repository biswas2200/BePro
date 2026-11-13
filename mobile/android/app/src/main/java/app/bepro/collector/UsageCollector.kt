package app.bepro.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Reads app foreground sessions, screen on/off and unlocks from UsageStatsManager.
 *
 * Android keeps several days of this history, so a run that MIUI skipped loses nothing: the next
 * run simply reads from the saved cursor.
 */
class UsageCollector(private val context: Context, private val settings: Settings) : Collector {

  override val name = "usage"

  private val labels = HashMap<String, String>()

  override suspend fun collect(now: Long): CollectResult {
    if (!Permissions.hasUsageAccess(context)) return CollectResult.EMPTY
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val from = maxOf(settings.usageCursor, now - BACKFILL_MS)

    val raw = ArrayList<RawUsageEvent>()
    val stream = usm.queryEvents(from, now)
    val event = UsageEvents.Event()
    while (stream.hasNextEvent()) {
      stream.getNextEvent(event)
      raw += RawUsageEvent(event.eventType, event.packageName, event.timeStamp)
    }

    val builder = UsageSessionBuilder(excludedPackages())
    val sessions = builder.build(raw, now)
    val events = ArrayList<Event>(sessions.apps.size + sessions.screen.size + sessions.unlocks.size)
    sessions.apps.forEach { s ->
      events += Event(
        category = "usage",
        subCategory = s.key,
        value = s.minutes,
        unit = "minutes",
        startedAt = s.start,
        endedAt = s.end,
        source = "usage_stats",
        metadata = mapOf("label" to label(s.key)),
      )
    }
    sessions.screen.forEach { s ->
      events += Event("screen", "session", s.minutes, "minutes", s.start, s.end, "usage_stats")
    }
    sessions.unlocks.forEach { t ->
      events += Event("screen", "unlock", 1.0, "count", t, null, "usage_stats")
    }
    val cursor = builder.nextCursor(sessions, now)
    return CollectResult(events) { settings.usageCursor = cursor }
  }

  /** The launcher and system UI aren't "apps you used"; time there is dropped. */
  private fun excludedPackages(): Set<String> {
    val pm = context.packageManager
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val launchers = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
      .map { it.activityInfo.packageName }
    return launchers.toSet() + setOf("com.android.systemui", "android", "com.miui.home", "com.mi.android.globallauncher")
  }

  private fun label(pkg: String): String = labels.getOrPut(pkg) {
    try {
      val pm = context.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
      pkg
    }
  }

  private companion object {
    const val BACKFILL_MS = 7L * 24 * 60 * 60 * 1000
  }
}
