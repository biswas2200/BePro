package app.bepro.collector

/**
 * Output of one collector run. [commit] advances the collector's cursor and is only called after
 * [events] are safely in the local buffer, so a crash in between just re-reads the same window.
 */
class CollectResult(val events: List<Event>, val commit: () -> Unit = {}) {
  companion object {
    val EMPTY = CollectResult(emptyList())
  }
}

interface Collector {
  val name: String

  suspend fun collect(now: Long): CollectResult
}
