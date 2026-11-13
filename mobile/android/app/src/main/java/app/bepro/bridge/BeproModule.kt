package app.bepro.bridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import app.bepro.collector.ApiClient
import app.bepro.collector.BackgroundWork
import app.bepro.collector.CollectionRunner
import app.bepro.collector.Event
import app.bepro.collector.EventBuffer
import app.bepro.collector.HealthConnectCollector
import app.bepro.collector.Miui
import app.bepro.collector.Permissions
import app.bepro.collector.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

class BeproModule(private val reactContext: ReactApplicationContext) : NativeBeproSpec(reactContext) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val settings = Settings(reactContext)
  private val api = ApiClient(reactContext)
  private val buffer = EventBuffer.get(reactContext)

  override fun getName() = NAME

  override fun invalidate() {
    scope.cancel()
    super.invalidate()
  }

  /** Runs [block] off the JS thread and settles the promise with its result or error. */
  private fun async(promise: Promise, block: suspend () -> Any?) {
    scope.launch {
      try {
        promise.resolve(block())
      } catch (e: Exception) {
        promise.reject("E_BEPRO", e.message ?: e.javaClass.simpleName, e)
      }
    }
  }

  // ---- Pairing ----

  override fun pair(serverUrl: String, secret: String, promise: Promise) = async(promise) {
    api.pair(serverUrl, secret)
    BackgroundWork.schedule(reactContext)
    BackgroundWork.syncSoon(reactContext)
    config()
  }

  override fun getConfig(promise: Promise) = async(promise) { config() }

  override fun getAccessToken(forceRefresh: Boolean, promise: Promise) = async(promise) { api.accessToken(forceRefresh) }

  override fun unpair(promise: Promise) = async(promise) {
    BackgroundWork.cancel(reactContext)
    api.unpair()
    buffer.clear()
    null
  }

  private fun config(): WritableMap = Arguments.createMap().apply {
    putString("serverUrl", settings.serverUrl)
    putString("deviceId", settings.deviceId)
    putBoolean("paired", api.isPaired())
    putString("userId", settings.userId)
  }

  // ---- Permissions and settings screens ----

  override fun getPermissionStatus(promise: Promise) = async(promise) {
    Arguments.createMap().apply {
      putBoolean("usageAccess", Permissions.hasUsageAccess(reactContext))
      putBoolean("calendar", Permissions.hasCalendar(reactContext))
      putBoolean("activityRecognition", Permissions.canRecognizeActivity(reactContext))
      putString("healthConnect", HealthConnectCollector.status(reactContext))
      putBoolean("healthConnectGranted", HealthConnectCollector.isGranted(reactContext))
      putBoolean("stepSensor", Permissions.hasStepSensor(reactContext))
      putBoolean("ignoringBatteryOptimizations", Permissions.isIgnoringBatteryOptimizations(reactContext))
      putBoolean("isMiui", Miui.isMiui)
      putInt("androidVersion", Build.VERSION.SDK_INT)
    }
  }

  override fun openUsageAccessSettings() = Permissions.openUsageAccessSettings(reactContext)

  override fun openAutostartSettings(promise: Promise) = promise.resolve(Miui.openAutostart(reactContext))

  override fun openBatterySettings(promise: Promise) = promise.resolve(Miui.openBatterySaver(reactContext))

  override fun requestIgnoreBatteryOptimizations() = Permissions.requestIgnoreBatteryOptimizations(reactContext)

  private var healthLauncher: ActivityResultLauncher<Set<String>>? = null

  override fun requestHealthConnect(promise: Promise) {
    val activity = reactContext.currentActivity as? ComponentActivity
    if (activity == null || HealthConnectCollector.client(reactContext) == null) {
      promise.resolve(false)
      return
    }
    UiThreadUtil.runOnUiThread {
      healthLauncher?.unregister()
      healthLauncher = activity.activityResultRegistry.register(
        "bepro-health-connect",
        PermissionController.createRequestPermissionResultContract(),
      ) { granted ->
        healthLauncher?.unregister()
        healthLauncher = null
        promise.resolve(granted.containsAll(HealthConnectCollector.PERMISSIONS))
      }.also { it.launch(HealthConnectCollector.PERMISSIONS) }
    }
  }

  override fun openHealthConnectInstall() {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
    Permissions.start(reactContext, market) || Permissions.start(
      reactContext,
      Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")),
    )
  }

  override fun refreshBackgroundWork() {
    if (api.isPaired()) {
      BackgroundWork.schedule(reactContext)
      BackgroundWork.syncSoon(reactContext)
    }
  }

  // ---- Collection ----

  override fun syncNow(promise: Promise) = async(promise) {
    val result = CollectionRunner.run(reactContext)
    Arguments.createMap().apply {
      putInt("collected", result.collected)
      putInt("uploaded", result.uploaded)
      putInt("pendingEvents", result.pending)
      putString("error", result.error)
    }
  }

  override fun getSyncState(promise: Promise) = async(promise) {
    Arguments.createMap().apply {
      putDouble("lastCollectAt", settings.lastCollectAt.toDouble())
      putDouble("lastSyncAt", settings.lastSyncAt.toDouble())
      putInt("pendingEvents", buffer.pendingCount())
      putString("lastError", settings.lastError)
    }
  }

  override fun logMood(score: Double, note: String, promise: Promise) = async(promise) {
    require(score in 1.0..5.0) { "Mood must be 1-5" }
    val metadata = if (note.isBlank()) emptyMap() else mapOf("note" to note.trim().take(500))
    buffer.add(listOf(Event("mood", "", score, "score", System.currentTimeMillis(), null, "manual", metadata)))
    BackgroundWork.syncSoon(reactContext)
    null
  }

  override fun logSleep(startMs: Double, endMs: Double, promise: Promise) = async(promise) {
    val start = startMs.toLong()
    val end = endMs.toLong()
    require(end > start && end - start <= 16 * 3600_000L) { "Sleep must be between 0 and 16 hours" }
    val hours = (end - start) / 3_600_000.0
    buffer.add(listOf(Event("sleep", "manual", hours, "hours", start, end, "manual")))
    BackgroundWork.syncSoon(reactContext)
    null
  }

  override fun startFocus(plannedMinutes: Double, promise: Promise) = async(promise) {
    if (settings.focusStartedAt == 0L) {
      settings.focusStartedAt = System.currentTimeMillis()
      settings.focusPlannedMinutes = plannedMinutes.toInt().coerceIn(1, 240)
    }
    activeFocus()
  }

  override fun stopFocus(promise: Promise) = async(promise) {
    val start = settings.focusStartedAt
    if (start == 0L) return@async 0.0
    val end = System.currentTimeMillis()
    val minutes = (end - start) / 60_000.0
    settings.focusStartedAt = 0
    if (minutes >= 1) {
      buffer.add(
        listOf(
          Event(
            "focus", "timer", minutes, "minutes", start, end, "manual",
            mapOf("plannedMinutes" to settings.focusPlannedMinutes),
          ),
        ),
      )
      BackgroundWork.syncSoon(reactContext)
    }
    Math.round(minutes).toDouble()
  }

  override fun cancelFocus(promise: Promise) = async(promise) {
    settings.focusStartedAt = 0
    null
  }

  override fun getActiveFocus(promise: Promise) = async(promise) { activeFocus() }

  private fun activeFocus(): WritableMap? {
    if (settings.focusStartedAt == 0L) return null
    return Arguments.createMap().apply {
      putDouble("startedAt", settings.focusStartedAt.toDouble())
      putInt("plannedMinutes", settings.focusPlannedMinutes)
    }
  }

  override fun exportCsv(promise: Promise) = async(promise) {
    val dir = File(reactContext.cacheDir, "exports").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "bepro-events-${LocalDate.now()}.csv")
    api.downloadExport(file)
    val uri = FileProvider.getUriForFile(reactContext, "${reactContext.packageName}.exports", file)
    val share = Intent(Intent.ACTION_SEND)
      .setType("text/csv")
      .putExtra(Intent.EXTRA_STREAM, uri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(share, "Export Be PRO data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    reactContext.startActivity(chooser)
    null
  }

  companion object {
    const val NAME = "Bepro"
  }
}
