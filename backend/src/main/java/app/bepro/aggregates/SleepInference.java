package app.bepro.aggregates;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Estimates a night's sleep from screen-on sessions when no wearable or manual entry exists.
 * <p>
 * The longest screen-off gap that starts in the evening/night and ends in the morning of the
 * wake-up date is taken as sleep. Brief phone checks in the middle of the night (a few minutes,
 * surrounded by long gaps) are bridged so they don't split the night in two.
 */
public final class SleepInference {

	static final LocalTime WINDOW_START = LocalTime.of(18, 0);

	static final LocalTime WINDOW_END = LocalTime.of(14, 0);

	static final LocalTime LATEST_BEDTIME = LocalTime.of(6, 0);

	static final LocalTime EARLIEST_WAKE = LocalTime.of(3, 0);

	static final Duration MIN_SLEEP = Duration.ofHours(3);

	static final Duration MAX_SLEEP = Duration.ofHours(14);

	static final Duration MAX_INTERRUPTION = Duration.ofMinutes(5);

	static final Duration MIN_GAP_AROUND_INTERRUPTION = Duration.ofMinutes(45);

	static final LocalTime DEEP_NIGHT_END = LocalTime.of(5, 30);

	public record Night(Instant start, Instant end, double hours) {
	}

	private SleepInference() {
	}

	/** Window of screen sessions needed to infer sleep for {@code wakeDate}. */
	public static Interval window(LocalDate wakeDate, ZoneId zone) {
		return new Interval(wakeDate.minusDays(1).atTime(WINDOW_START).atZone(zone).toInstant(),
				wakeDate.atTime(WINDOW_END).atZone(zone).toInstant());
	}

	public static Optional<Night> infer(LocalDate wakeDate, ZoneId zone, List<Interval> screenSessions) {
		Interval window = window(wakeDate, zone);
		List<Interval> sessions = Interval.merge(screenSessions.stream()
			.filter(s -> s.end().isAfter(window.start()) && s.start().isBefore(window.end()))
			.toList());
		if (sessions.size() < 2) {
			return Optional.empty();
		}

		Instant latestBedtime = wakeDate.atTime(LATEST_BEDTIME).atZone(zone).toInstant();
		Instant earliestWake = wakeDate.atTime(EARLIEST_WAKE).atZone(zone).toInstant();

		int best = -1;
		Duration bestGap = Duration.ZERO;
		for (int i = 0; i < sessions.size() - 1; i++) {
			Instant off = sessions.get(i).end();
			Instant on = sessions.get(i + 1).start();
			Duration gap = Duration.between(off, on);
			if (off.isAfter(latestBedtime) || on.isBefore(earliestWake)) {
				continue;
			}
			if (gap.compareTo(bestGap) > 0) {
				best = i;
				bestGap = gap;
			}
		}
		if (best < 0) {
			return Optional.empty();
		}

		// Extend across short interruptions on either side of the main gap.
		Interval deepNight = new Interval(wakeDate.atStartOfDay(zone).toInstant(),
				wakeDate.atTime(DEEP_NIGHT_END).atZone(zone).toInstant());
		int first = best; // session that ends right before sleep
		int last = best + 1; // session that starts at wake-up
		Duration awake = Duration.ZERO;
		while (last < sessions.size() - 1 && isInterruption(sessions, last, deepNight)) {
			awake = awake.plus(sessions.get(last).duration());
			last++;
		}
		while (first > 0 && isInterruption(sessions, first, deepNight)) {
			awake = awake.plus(sessions.get(first).duration());
			first--;
		}

		Instant start = sessions.get(first).end();
		Instant end = sessions.get(last).start();
		Duration slept = Duration.between(start, end).minus(awake);
		if (slept.compareTo(MIN_SLEEP) < 0 || slept.compareTo(MAX_SLEEP) > 0) {
			return Optional.empty();
		}
		return Optional.of(new Night(start, end, Math.round(slept.toMinutes() / 6.0) / 10.0));
	}

	/**
	 * A short session in the dead of night with long screen-off gaps on both sides, e.g. checking
	 * the time at 3am. Quick checks at 23:30 or 07:00 are not bridged: those are usually real
	 * bedtime/wake-up moments.
	 */
	private static boolean isInterruption(List<Interval> sessions, int i, Interval deepNight) {
		if (i <= 0 || i >= sessions.size() - 1) {
			return false;
		}
		Interval s = sessions.get(i);
		Duration before = Duration.between(sessions.get(i - 1).end(), s.start());
		Duration after = Duration.between(s.end(), sessions.get(i + 1).start());
		boolean inDeepNight = !s.start().isBefore(deepNight.start()) && s.start().isBefore(deepNight.end());
		return inDeepNight && s.duration().compareTo(MAX_INTERRUPTION) <= 0
				&& before.compareTo(MIN_GAP_AROUND_INTERRUPTION) >= 0 && after.compareTo(MIN_GAP_AROUND_INTERRUPTION) >= 0;
	}

}
