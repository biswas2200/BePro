package app.bepro.collector

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings as AndroidSettings
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

object Permissions {

  fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Suppress("DEPRECATION")
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
      appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    // Some MIUI builds report MODE_DEFAULT after the toggle; fall back to the permission check.
    return if (mode == AppOpsManager.MODE_DEFAULT) {
      context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
    } else {
      mode == AppOpsManager.MODE_ALLOWED
    }
  }

  fun hasCalendar(context: Context) =
    context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

  /** ACTIVITY_RECOGNITION became a runtime permission in Android 10; on Android 9 it's granted at install. */
  private fun hasActivityPermission(context: Context) =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
      context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

  fun hasPlayServices(context: Context) =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

  fun canRecognizeActivity(context: Context) = hasActivityPermission(context) && hasPlayServices(context)

  fun hasStepSensor(context: Context): Boolean {
    val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    return sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
  }

  fun canCountSteps(context: Context) = hasActivityPermission(context) && hasStepSensor(context)

  fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return power.isIgnoringBatteryOptimizations(context.packageName)
  }

  fun openUsageAccessSettings(context: Context) {
    start(context, Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)) ||
      start(context, appDetails(context))
  }

  @Suppress("BatteryLife") // personal sideloaded app; background collection is its whole purpose
  fun requestIgnoreBatteryOptimizations(context: Context) {
    val request = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
      .setData(Uri.parse("package:${context.packageName}"))
    start(context, request) || start(context, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
  }

  fun appDetails(context: Context) =
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${context.packageName}"))

  fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
  } catch (e: ActivityNotFoundException) {
    false
  } catch (e: SecurityException) {
    false
  }
}

/** Xiaomi-specific screens. MIUI ignores the stock battery settings for background work. */
object Miui {

  val isMiui: Boolean by lazy {
    try {
      val props = Class.forName("android.os.SystemProperties")
      val value = props.getMethod("get", String::class.java).invoke(null, "ro.miui.ui.version.name") as String?
      !value.isNullOrBlank()
    } catch (e: Exception) {
      Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    }
  }

  fun openAutostart(context: Context): Boolean {
    if (!isMiui) return false
    return listOf(
      ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
      ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings"),
    ).any { Permissions.start(context, Intent().setComponent(it)) }
  }

  /** MIUI's per-app "Battery saver" screen, where "No restrictions" must be chosen. */
  fun openBatterySaver(context: Context): Boolean {
    if (isMiui) {
      val label = context.applicationInfo.loadLabel(context.packageManager).toString()
      val intent = Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
        .putExtra("package_name", context.packageName)
        .putExtra("package_label", label)
      if (Permissions.start(context, intent)) return true
      val component = Intent().setComponent(
        ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
      ).putExtra("package_name", context.packageName).putExtra("package_label", label)
      if (Permissions.start(context, component)) return true
    }
    return Permissions.start(context, Permissions.appDetails(context))
  }
}
