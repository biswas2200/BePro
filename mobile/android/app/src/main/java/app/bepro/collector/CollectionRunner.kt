package app.bepro.collector

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RunResult(val collected: Int, val uploaded: Int, val pending: Int, val error: String?)

/**
 * One pass of collect → buffer → upload → check for new insights. Shared by the periodic worker and
 * the app's "Sync now" button; the mutex keeps them from overlapping.
 */
object CollectionRunner {

  private const val TAG = "BeproRunner"
  private const val BATCH_SIZE = 500
  private const val MAX_BATCHES_PER_RUN = 20
  private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

  private val mutex = Mutex()

  suspend fun run(context: Context, upload: Boolean = true): RunResult = mutex.withLock {
    val app = context.applicationContext
    val settings = Settings(app)
    val buffer = EventBuffer.get(app)
    val api = ApiClient(app)
    val now = System.currentTimeMillis()
    var collected = 0
    val errors = ArrayList<String>()

    val collectors = listOf(
      UsageCollector(app, settings),
      StepCollector(app, settings),
      CalendarCollector(app, settings),
      HealthConnectCollector(app, settings),
    )
    for (collector in collectors) {
      try {
        val result = collector.collect(now)
        buffer.add(result.events)
        result.commit()
        collected += result.events.size
      } catch (e: Exception) {
        Log.w(TAG, "${collector.name} collector failed", e)
        errors += "${collector.name}: ${e.message}"
      }
    }
    try {
      ActivityTransitions.register(app)
    } catch (e: Exception) {
      Log.w(TAG, "activity registration failed", e)
    }
    settings.lastCollectAt = now

    var uploaded = 0
    if (upload && api.isPaired()) {
      try {
        uploaded = uploadPending(api, buffer)
        settings.lastSyncAt = System.currentTimeMillis()
        notifyNewInsights(app, api, settings)
      } catch (e: Exception) {
        Log.w(TAG, "upload failed", e)
        errors += "upload: ${e.message}"
      }
    }
    buffer.prune(now - WEEK_MS)

    val error = errors.joinToString("; ").ifBlank { null }
    settings.lastError = error
    RunResult(collected, uploaded, buffer.pendingCount(), error)
  }

  private fun uploadPending(api: ApiClient, buffer: EventBuffer): Int {
    var uploaded = 0
    repeat(MAX_BATCHES_PER_RUN) {
      val batch = buffer.pending(BATCH_SIZE)
      if (batch.isEmpty()) return uploaded
      uploaded += uploadSplittingBadRows(api, buffer, batch)
    }
    return uploaded
  }

  /**
   * The server rejects a whole batch if one row is invalid. Halve the batch until the bad row is
   * isolated, then drop it, so one malformed reading can't block syncing forever.
   */
  private fun uploadSplittingBadRows(api: ApiClient, buffer: EventBuffer, batch: List<BufferedEvent>): Int {
    try {
      api.uploadEvents(batch.map { it.event })
      buffer.markSynced(batch.map { it.rowId })
      return batch.size
    } catch (e: ApiException) {
      if (e.code != 400) throw e
      if (batch.size == 1) {
        Log.w(TAG, "Dropping event rejected by server: ${batch[0].event} (${e.message})")
        buffer.markSynced(listOf(batch[0].rowId))
        return 0
      }
      val half = batch.size / 2
      return uploadSplittingBadRows(api, buffer, batch.subList(0, half)) +
        uploadSplittingBadRows(api, buffer, batch.subList(half, batch.size))
    }
  }

  private fun notifyNewInsights(context: Context, api: ApiClient, settings: Settings) {
    val since = settings.lastNotifiedInsightAt
    val fresh = api.insightsSince(since)
    if (fresh.isEmpty()) return
    if (since != null) {
      fresh.take(2).forEachIndexed { i, insight ->
        val title = when (insight.kind) {
          "alert" -> "Be PRO alert"
          "notice" -> "Be PRO"
          else -> "Your weekly insight"
        }
        Notifier.showInsight(context, 1000 + i, title, "${insight.pattern} ${insight.action}")
      }
    }
    // First sync after pairing only records the baseline, so old insights don't all pop up at once.
    settings.lastNotifiedInsightAt = fresh.first().generatedAt
  }
}
