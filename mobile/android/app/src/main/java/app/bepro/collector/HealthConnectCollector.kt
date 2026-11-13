package app.bepro.collector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Optional source. On Android 9 Health Connect is a separate Play Store app, and on a phone with no
 * wearable usually nothing writes to it — so this collector quietly does nothing in that case.
 */
class HealthConnectCollector(private val context: Context, private val settings: Settings) : Collector {

  override val name = "health_connect"

  override suspend fun collect(now: Long): CollectResult {
    val client = client(context) ?: return CollectResult.EMPTY
    if (!client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) return CollectResult.EMPTY

    val end = Instant.ofEpochMilli(now)
    val start = Instant.ofEpochMilli(maxOf(settings.healthConnectCursor, now - 7 * DAY_MS))
      .minus(Duration.ofDays(1))
      .truncatedTo(ChronoUnit.HOURS)
    val range = TimeRangeFilter.between(start, end)
    val events = ArrayList<Event>()

    client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records.forEach { r ->
      events += Event(
        category = "sleep",
        subCategory = "session",
        value = Duration.between(r.startTime, r.endTime).toMinutes() / 60.0,
        unit = "hours",
        startedAt = r.startTime.toEpochMilli(),
        endedAt = r.endTime.toEpochMilli(),
        source = "health_connect",
        metadata = mapOf("app" to r.metadata.dataOrigin.packageName),
      )
    }

    // Hourly totals: Health Connect de-duplicates steps written by several apps for us.
    client.aggregateGroupByDuration(
      AggregateGroupByDurationRequest(setOf(StepsRecord.COUNT_TOTAL), range, Duration.ofHours(1)),
    ).forEach { bucket ->
      val steps = bucket.result[StepsRecord.COUNT_TOTAL] ?: return@forEach
      if (steps > 0) {
        events += Event(
          "steps", "", steps.toDouble(), "steps",
          bucket.startTime.toEpochMilli(), bucket.endTime.toEpochMilli(), "health_connect",
        )
      }
    }
    return CollectResult(events) { settings.healthConnectCursor = now }
  }

  companion object {
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    val PERMISSIONS = setOf(
      HealthPermission.getReadPermission(SleepSessionRecord::class),
      HealthPermission.getReadPermission(StepsRecord::class),
    )

    /** "available" | "not_installed" | "unsupported" */
    fun status(context: Context): String = when (HealthConnectClient.getSdkStatus(context)) {
      HealthConnectClient.SDK_AVAILABLE -> "available"
      HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "not_installed"
      else -> "unsupported"
    }

    fun client(context: Context): HealthConnectClient? =
      if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
        HealthConnectClient.getOrCreate(context)
      } else {
        null
      }

    suspend fun isGranted(context: Context): Boolean =
      client(context)?.permissionController?.getGrantedPermissions()?.containsAll(PERMISSIONS) ?: false
  }
}
