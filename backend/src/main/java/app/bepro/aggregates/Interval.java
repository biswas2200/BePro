package app.bepro.aggregates;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record Interval(Instant start, Instant end) {

	public Interval {
		if (end.isBefore(start)) {
			throw new IllegalArgumentException("end before start");
		}
	}

	public Duration duration() {
		return Duration.between(start, end);
	}

	public double minutes() {
		return duration().toMillis() / 60_000.0;
	}

	/** Overlap with [from, to) in minutes. */
	public double overlapMinutes(Instant from, Instant to) {
		Instant s = start.isAfter(from) ? start : from;
		Instant e = end.isBefore(to) ? end : to;
		return e.isAfter(s) ? Duration.between(s, e).toMillis() / 60_000.0 : 0;
	}

	/** Sorts and merges overlapping or touching intervals. */
	public static List<Interval> merge(List<Interval> intervals) {
		List<Interval> sorted = new ArrayList<>(intervals);
		sorted.sort(Comparator.comparing(Interval::start));
		List<Interval> merged = new ArrayList<>();
		for (Interval i : sorted) {
			if (!merged.isEmpty() && !i.start().isAfter(merged.getLast().end())) {
				Interval last = merged.removeLast();
				merged.add(new Interval(last.start(), i.end().isAfter(last.end()) ? i.end() : last.end()));
			}
			else {
				merged.add(i);
			}
		}
		return merged;
	}

}
