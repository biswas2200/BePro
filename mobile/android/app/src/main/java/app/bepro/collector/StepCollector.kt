package app.bepro.collector

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Reads the hardware step counter (steps since boot) and records the difference since the last
 * reading. Reboots reset the counter to 0, so we keep the boot time of the previous reading.
 */
class StepCollector(private val context: Context, private val settings: Settings) : Collector {

  override val name = "steps"

  override suspend fun collect(now: Long): CollectResult {
    if (!Permissions.canCountSteps(context)) return CollectResult.EMPTY
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return CollectResult.EMPTY
    val count = readOnce(sensorManager, sensor) ?: return CollectResult.EMPTY

    val bootAt = now - SystemClock.elapsedRealtime()
    val lastCount = settings.lastStepCount
    val lastAt = settings.lastStepReadAt
    val rebooted = abs(bootAt - settings.lastStepBootAt) > 60_000 || count < lastCount

    val events = if (lastCount < 0) {
      emptyList() // first reading is only a baseline
    } else {
      val delta = if (rebooted) count else count - lastCount
      val start = if (rebooted) maxOf(lastAt, bootAt) else lastAt
      splitAtMidnight(delta.toDouble(), start, now)
    }
    return CollectResult(events) {
      settings.lastStepCount = count
      settings.lastStepReadAt = now
      settings.lastStepBootAt = bootAt
    }
  }

  /** Spreads steps across local days when the interval crosses midnight (e.g. a skipped night run). */
  private fun splitAtMidnight(steps: Double, start: Long, end: Long): List<Event> {
    if (steps <= 0 || end <= start) return emptyList()
    val zone = ZoneId.systemDefault()
    val result = ArrayList<Event>()
    var cursor = start
    while (cursor < end) {
      val nextMidnight = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate().plusDays(1)
        .atStartOfDay(zone).toInstant().toEpochMilli()
      val segmentEnd = minOf(nextMidnight, end)
      val share = steps * (segmentEnd - cursor) / (end - start)
      if (share >= 0.5) {
        result += Event("steps", "", Math.round(share).toDouble(), "steps", cursor, segmentEnd, "sensor")
      }
      cursor = segmentEnd
    }
    return result
  }

  private suspend fun readOnce(manager: SensorManager, sensor: Sensor): Float? =
    withTimeoutOrNull(5_000) {
      suspendCancellableCoroutine { cont ->
        val listener = object : SensorEventListener {
          override fun onSensorChanged(event: SensorEvent) {
            manager.unregisterListener(this)
            if (cont.isActive) cont.resume(event.values[0])
          }

          override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        cont.invokeOnCancellation { manager.unregisterListener(listener) }
      }
    }
}
