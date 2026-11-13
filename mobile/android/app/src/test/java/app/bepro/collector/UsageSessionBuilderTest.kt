package app.bepro.collector

import app.bepro.collector.UsageSessionBuilder.Companion.ACTIVITY_PAUSED
import app.bepro.collector.UsageSessionBuilder.Companion.ACTIVITY_RESUMED
import app.bepro.collector.UsageSessionBuilder.Companion.KEYGUARD_HIDDEN
import app.bepro.collector.UsageSessionBuilder.Companion.SCREEN_INTERACTIVE
import app.bepro.collector.UsageSessionBuilder.Companion.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageSessionBuilderTest {

  private val builder = UsageSessionBuilder(excludedPackages = setOf("com.miui.home"))

  private fun ev(type: Int, pkg: String?, sec: Long) = RawUsageEvent(type, pkg, sec * 1000)

  @Test
  fun appSessionsSplitOnAppSwitch() {
    val result = builder.build(
      listOf(
        ev(SCREEN_INTERACTIVE, null, 0),
        ev(KEYGUARD_HIDDEN, null, 2),
        ev(ACTIVITY_RESUMED, "com.instagram.android", 10),
        ev(ACTIVITY_PAUSED, "com.instagram.android", 130),
        ev(ACTIVITY_RESUMED, "com.android.chrome", 130),
        ev(ACTIVITY_PAUSED, "com.android.chrome", 190),
        ev(ACTIVITY_RESUMED, "com.miui.home", 190),
        ev(SCREEN_NON_INTERACTIVE, null, 200),
      ),
      windowEnd = 300_000,
    )

    assertEquals(
      listOf(
        Session("com.instagram.android", 10_000, 130_000),
        Session("com.android.chrome", 130_000, 190_000),
      ),
      result.apps,
    )
    assertEquals(listOf(Session("session", 0, 200_000)), result.screen)
    assertEquals(listOf(2_000L), result.unlocks)
    assertNull(result.openSince)
  }

  @Test
  fun activityChangeInsideTheSameAppKeepsOneSession() {
    val result = builder.build(
      listOf(
        ev(ACTIVITY_RESUMED, "com.whatsapp", 0),
        ev(ACTIVITY_PAUSED, "com.whatsapp", 60),
        ev(ACTIVITY_RESUMED, "com.whatsapp", 60), // new activity, same app
        ev(ACTIVITY_PAUSED, "com.whatsapp", 120),
        ev(ACTIVITY_RESUMED, "com.miui.home", 121),
      ),
      windowEnd = 200_000,
    )

    assertEquals(listOf(Session("com.whatsapp", 0, 120_000)), result.apps)
  }

  @Test
  fun screenOffEndsTheForegroundApp() {
    val result = builder.build(
      listOf(
        ev(SCREEN_INTERACTIVE, null, 0),
        ev(ACTIVITY_RESUMED, "com.google.android.youtube", 5),
        ev(SCREEN_NON_INTERACTIVE, null, 600),
      ),
      windowEnd = 700_000,
    )

    assertEquals(listOf(Session("com.google.android.youtube", 5_000, 600_000)), result.apps)
  }

  @Test
  fun runningSessionsMoveTheCursorBack() {
    val result = builder.build(
      listOf(
        ev(SCREEN_INTERACTIVE, null, 100),
        ev(ACTIVITY_RESUMED, "com.instagram.android", 110),
      ),
      windowEnd = 400_000,
    )

    assertEquals(emptyList<Session>(), result.apps)
    assertEquals(100_000L, result.openSince)
    assertEquals(100_000L, builder.nextCursor(result, 400_000))
  }

  @Test
  fun staleOpenSessionDoesNotPinTheCursor() {
    val result = builder.build(listOf(ev(SCREEN_INTERACTIVE, null, 0)), windowEnd = 13 * 3600_000L)

    assertEquals(13 * 3600_000L, builder.nextCursor(result, 13 * 3600_000L))
  }

  @Test
  fun pauseWithoutResumeClosesAtThePause() {
    val result = builder.build(
      listOf(
        ev(ACTIVITY_RESUMED, "com.spotify.music", 0),
        ev(ACTIVITY_PAUSED, "com.spotify.music", 30),
      ),
      windowEnd = 100_000,
    )

    assertEquals(listOf(Session("com.spotify.music", 0, 30_000)), result.apps)
    assertNull(result.openSince)
  }

  @Test
  fun orphanPauseAtWindowStartIsIgnored() {
    val result = builder.build(
      listOf(
        ev(ACTIVITY_PAUSED, "com.instagram.android", 5),
        ev(ACTIVITY_RESUMED, "com.android.chrome", 6),
        ev(ACTIVITY_PAUSED, "com.android.chrome", 66),
        ev(ACTIVITY_RESUMED, "com.miui.home", 66),
      ),
      windowEnd = 100_000,
    )

    assertEquals(listOf(Session("com.android.chrome", 6_000, 66_000)), result.apps)
  }
}
