package app.bepro.collector

import android.content.Context
import java.util.UUID

/** Plain (non-secret) state: server address, collection cursors, sync bookkeeping. */
class Settings(context: Context) {

  private val prefs = context.getSharedPreferences("bepro_settings", Context.MODE_PRIVATE)

  val deviceId: String
    get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
      prefs.edit().putString(KEY_DEVICE_ID, it).apply()
    }

  var serverUrl: String?
    get() = prefs.getString("server_url", null)
    set(value) = prefs.edit().putString("server_url", value).apply()

  var userId: String?
    get() = prefs.getString("user_id", null)
    set(value) = prefs.edit().putString("user_id", value).apply()

  var tokenExpiresAt: Long
    get() = prefs.getLong("token_expires_at", 0)
    set(value) = prefs.edit().putLong("token_expires_at", value).apply()

  /** Usage events are read from here forward on the next run. */
  var usageCursor: Long
    get() = prefs.getLong("usage_cursor", 0)
    set(value) = prefs.edit().putLong("usage_cursor", value).apply()

  var calendarCursor: Long
    get() = prefs.getLong("calendar_cursor", 0)
    set(value) = prefs.edit().putLong("calendar_cursor", value).apply()

  var healthConnectCursor: Long
    get() = prefs.getLong("hc_cursor", 0)
    set(value) = prefs.edit().putLong("hc_cursor", value).apply()

  /** Last step-counter reading: counts since boot, so we keep the boot time to detect reboots. */
  var lastStepCount: Float
    get() = prefs.getFloat("step_count", -1f)
    set(value) = prefs.edit().putFloat("step_count", value).apply()

  var lastStepReadAt: Long
    get() = prefs.getLong("step_read_at", 0)
    set(value) = prefs.edit().putLong("step_read_at", value).apply()

  var lastStepBootAt: Long
    get() = prefs.getLong("step_boot_at", 0)
    set(value) = prefs.edit().putLong("step_boot_at", value).apply()

  var lastCollectAt: Long
    get() = prefs.getLong("last_collect_at", 0)
    set(value) = prefs.edit().putLong("last_collect_at", value).apply()

  var lastSyncAt: Long
    get() = prefs.getLong("last_sync_at", 0)
    set(value) = prefs.edit().putLong("last_sync_at", value).apply()

  var lastError: String?
    get() = prefs.getString("last_error", null)
    set(value) = prefs.edit().putString("last_error", value).apply()

  /** generatedAt (ISO) of the newest insight we already notified about. */
  var lastNotifiedInsightAt: String?
    get() = prefs.getString("last_notified_insight_at", null)
    set(value) = prefs.edit().putString("last_notified_insight_at", value).apply()

  var focusStartedAt: Long
    get() = prefs.getLong("focus_started_at", 0)
    set(value) = prefs.edit().putLong("focus_started_at", value).apply()

  var focusPlannedMinutes: Int
    get() = prefs.getInt("focus_planned", 0)
    set(value) = prefs.edit().putInt("focus_planned", value).apply()

  /** Ongoing activity-recognition state (ENTER seen, EXIT pending). */
  var activityType: String?
    get() = prefs.getString("activity_type", null)
    set(value) = prefs.edit().putString("activity_type", value).apply()

  var activityStartedAt: Long
    get() = prefs.getLong("activity_started_at", 0)
    set(value) = prefs.edit().putLong("activity_started_at", value).apply()

  fun clearPairing() {
    prefs.edit()
      .remove("server_url")
      .remove("user_id")
      .remove("token_expires_at")
      .remove("last_error")
      .apply()
  }

  private companion object {
    const val KEY_DEVICE_ID = "device_id"
  }
}
