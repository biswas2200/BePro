package app.bepro.collector

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Passive motion detection via Play Services. Transitions arrive as broadcasts; an ENTER opens a
 * span and the matching EXIT records it as an "activity" event with its duration in minutes.
 */
object ActivityTransitions {

  private const val TAG = "BeproActivity"

  private val TYPES = mapOf(
    DetectedActivity.WALKING to "walking",
    DetectedActivity.RUNNING to "running",
    DetectedActivity.ON_BICYCLE to "on_bicycle",
    DetectedActivity.IN_VEHICLE to "in_vehicle",
  )

  /** Safe to call on every run: re-registering the same PendingIntent replaces the old request. */
  @SuppressLint("MissingPermission") // checked by Permissions.canRecognizeActivity
  fun register(context: Context) {
    if (!Permissions.canRecognizeActivity(context)) return
    val transitions = TYPES.keys.flatMap { type ->
      listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
        ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build()
      }
    }
    ActivityRecognition.getClient(context)
      .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pendingIntent(context))
      .addOnFailureListener { Log.w(TAG, "Activity transition registration failed", it) }
  }

  private fun pendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, ActivityTransitionReceiver::class.java)
    // Play Services fills in the result extras, so the PendingIntent must be mutable on Android 12+.
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
      (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    return PendingIntent.getBroadcast(context, 7, intent, flags)
  }

  internal fun handle(context: Context, result: ActivityTransitionResult) {
    val settings = Settings(context)
    val buffer = EventBuffer.get(context)
    val nowWall = System.currentTimeMillis()
    val nowElapsed = SystemClock.elapsedRealtimeNanos()
    for (e in result.transitionEvents) {
      val type = TYPES[e.activityType] ?: continue
      val at = nowWall - (nowElapsed - e.elapsedRealTimeNanos) / 1_000_000
      val open = settings.activityType
      if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
        if (open != null && open != type) close(settings, buffer, at)
        if (settings.activityType == null) {
          settings.activityType = type
          settings.activityStartedAt = at
        }
      } else if (open == type) {
        close(settings, buffer, at)
      }
    }
  }

  private fun close(settings: Settings, buffer: EventBuffer, at: Long) {
    val type = settings.activityType ?: return
    val start = settings.activityStartedAt
    val minutes = (at - start) / 60_000.0
    // Ignore blips and spans that lost their EXIT for hours (the process was killed).
    if (minutes >= 1 && minutes <= 6 * 60) {
      buffer.add(listOf(Event("activity", type, minutes, "minutes", start, at, "activity")))
    }
    settings.activityType = null
    settings.activityStartedAt = 0
  }
}

class ActivityTransitionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!ActivityTransitionResult.hasResult(intent)) return
    val result = ActivityTransitionResult.extractResult(intent) ?: return
    val pending = goAsync()
    Thread {
      try {
        ActivityTransitions.handle(context.applicationContext, result)
      } finally {
        pending.finish()
      }
    }.start()
  }
}
