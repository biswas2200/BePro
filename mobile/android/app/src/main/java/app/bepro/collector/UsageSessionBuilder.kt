package app.bepro.collector

/** A raw row from UsageStatsManager.queryEvents, decoupled from Android types so it can be unit-tested. */
data class RawUsageEvent(val type: Int, val packageName: String?, val timestamp: Long)

data class Session(val key: String, val start: Long, val end: Long) {
  val minutes: Double get() = (end - start) / 60_000.0
}

data class UsageSessions(
  val apps: List<Session>,
  val screen: List<Session>,
  val unlocks: List<Long>,
  /** Start of a session still running at the end of the window; the next read must begin here. */
  val openSince: Long?,
)

/**
 * Turns the UsageEvents stream into per-app foreground sessions, screen-on sessions and unlocks.
 *
 * Moving between two activities of the same app produces PAUSED then RESUMED a few ms apart, so a
 * pause is only treated as the end of the session if the same app doesn't resume within [mergeGapMs].
 */
class UsageSessionBuilder(
  private val excludedPackages: Set<String>,
  private val mergeGapMs: Long = 2_000,
  private val minSessionMs: Long = 1_000,
) {

  fun build(events: List<RawUsageEvent>, windowEnd: Long): UsageSessions {
    val apps = ArrayList<Session>()
    val screen = ArrayList<Session>()
    val unlocks = ArrayList<Long>()

    var currentApp: String? = null
    var appStart = 0L
    var pausedAt: Long? = null
    var screenOnAt: Long? = null

    fun closeApp(at: Long) {
      val pkg = currentApp ?: return
      val end = pausedAt?.coerceAtMost(at) ?: at
      if (end - appStart >= minSessionMs) apps += Session(pkg, appStart, end)
      currentApp = null
      pausedAt = null
    }

    for (e in events.sortedBy { it.timestamp }) {
      when (e.type) {
        ACTIVITY_RESUMED -> {
          val pkg = e.packageName ?: continue
          val paused = pausedAt
          if (currentApp == pkg && (paused == null || e.timestamp - paused <= mergeGapMs)) {
            pausedAt = null // same app, keep the session going
          } else {
            closeApp(e.timestamp)
            if (pkg !in excludedPackages) {
              currentApp = pkg
              appStart = e.timestamp
            }
          }
        }
        ACTIVITY_PAUSED, ACTIVITY_STOPPED -> {
          if (e.packageName == currentApp && pausedAt == null) pausedAt = e.timestamp
        }
        SCREEN_INTERACTIVE -> {
          if (screenOnAt == null) screenOnAt = e.timestamp
        }
        SCREEN_NON_INTERACTIVE, DEVICE_SHUTDOWN -> {
          closeApp(e.timestamp)
          screenOnAt?.let { on ->
            if (e.timestamp - on >= minSessionMs) screen += Session(SCREEN_SESSION, on, e.timestamp)
          }
          screenOnAt = null
        }
        KEYGUARD_HIDDEN -> unlocks += e.timestamp
      }
    }

    // A pause that was never followed by a resume of the same app means the app really closed.
    pausedAt?.let { if (windowEnd - it > mergeGapMs) closeApp(it) }

    val openSince = listOfNotNull(currentApp?.let { appStart }, screenOnAt).minOrNull()
    return UsageSessions(apps, screen, unlocks, openSince)
  }

  /**
   * Where the next read should start. Normally the end of this window, but if a session is still
   * running we re-read from its start so it is captured whole once it ends. Ignores a stale open
   * session (some ROMs drop screen-off events) so the cursor can't get stuck.
   */
  fun nextCursor(sessions: UsageSessions, windowEnd: Long, maxOpenMs: Long = 12 * 60 * 60_000L): Long {
    val open = sessions.openSince ?: return windowEnd
    return if (windowEnd - open > maxOpenMs) windowEnd else open
  }

  companion object {
    // android.app.usage.UsageEvents.Event constants
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26

    const val SCREEN_SESSION = "session"
  }
}
