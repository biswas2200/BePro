package app.bepro.summary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import app.bepro.aggregates.AggregateRepository;
import app.bepro.aggregates.AggregationService;
import app.bepro.aggregates.DailyAggregate;
import app.bepro.aggregates.Interval;
import app.bepro.config.BeproProperties;
import app.bepro.summary.WeekSummary.Activity;
import app.bepro.summary.WeekSummary.AppUsage;
import app.bepro.summary.WeekSummary.Day;
import app.bepro.summary.WeekSummary.Focus;
import app.bepro.summary.WeekSummary.Mood;
import app.bepro.summary.WeekSummary.Screen;
import app.bepro.summary.WeekSummary.Sleep;
import app.bepro.summary.WeekSummary.Steps;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import static app.bepro.events.EventRepository.utc;

@Service
public class WeekSummaryService {

	private static final Set<String> STOPWORDS = Set.of("the", "a", "an", "and", "or", "but", "to", "of", "in", "on",
			"at", "for", "with", "was", "is", "it", "i", "my", "me", "so", "too", "very", "just", "felt", "feel",
			"feeling", "today", "day", "bit", "had", "have", "not", "no", "be", "been", "this", "that", "after",
			"before", "really", "got", "get", "am", "are", "were", "from", "all", "some", "more", "less");

	private final AggregateRepository aggregates;

	private final AggregationService aggregation;

	private final JdbcClient jdbc;

	private final ZoneId zone;

	public WeekSummaryService(AggregateRepository aggregates, AggregationService aggregation, JdbcClient jdbc,
			BeproProperties properties) {
		this.aggregates = aggregates;
		this.aggregation = aggregation;
		this.jdbc = jdbc;
		this.zone = properties.zone();
	}

	/** The 7 days ending on {@code to}, inclusive. */
	public WeekSummary build(String userId, LocalDate to) {
		LocalDate from = to.minusDays(6);
		Instant start = from.atStartOfDay(zone).toInstant();
		Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();

		Map<LocalDate, DailyAggregate> byDate = aggregates.range(userId, from, to)
			.stream()
			.collect(Collectors.toMap(DailyAggregate::date, Function.identity()));
		List<DailyAggregate> week = new ArrayList<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			week.add(byDate.getOrDefault(d, DailyAggregate.empty(d)));
		}
		int daysWithData = (int) week.stream().filter(DailyAggregate::hasAnyData).count();
		int completeDays = (int) week.stream().filter(DailyAggregate::isComplete).count();
		List<DailyAggregate> usageDays = week.stream().filter(d -> d.usageMinutes() > 0 || d.screenMinutes() > 0).toList();

		return new WeekSummary(from, to, zone.getId(), daysWithData, completeDays, sleep(week),
				topApps(userId, start, end, Math.max(usageDays.size(), 1)), screen(userId, usageDays, start, end),
				focus(userId, start, end), steps(week), mood(userId, week, start, end),
				new Activity(average(usageDays.stream().map(d -> (double) d.activeMinutes()).toList())),
				week.stream().map(this::day).toList());
	}

	private Sleep sleep(List<DailyAggregate> week) {
		List<DailyAggregate> nights = week.stream().filter(d -> d.sleepHours() != null).toList();
		return new Sleep(average(nights.stream().map(d -> d.sleepHours().doubleValue()).toList()),
				circularMeanTime(nights.stream().map(DailyAggregate::sleepStart).filter(Objects::nonNull).toList()),
				circularMeanTime(nights.stream().map(DailyAggregate::sleepEnd).filter(Objects::nonNull).toList()),
				(int) nights.stream().filter(d -> d.sleepHours().doubleValue() < 6).count(), nights.size());
	}

	private List<AppUsage> topApps(String userId, Instant start, Instant end, int days) {
		return aggregation.appMinutes(userId, start, end)
			.stream()
			.limit(5)
			.map(a -> new AppUsage(a.packageName(), a.label(), round1(a.minutes() / days)))
			.toList();
	}

	private Screen screen(String userId, List<DailyAggregate> usageDays, Instant start, Instant end) {
		List<Interval> sessions = jdbc.sql("""
				SELECT started_at, ended_at FROM events
				WHERE user_id = :u AND category = 'screen' AND sub_category = 'session'
				  AND started_at < :end AND ended_at > :start
				""").param("u", userId).param("start", utc(start)).param("end", utc(end))
			.query((rs, i) -> new Interval(rs.getObject("started_at", OffsetDateTime.class).toInstant(),
					rs.getObject("ended_at", OffsetDateTime.class).toInstant()))
			.list();
		double[] byHour = new double[24];
		for (Interval s : sessions) {
			Instant cursor = s.start().isBefore(start) ? start : s.start();
			Instant stop = s.end().isAfter(end) ? end : s.end();
			while (cursor.isBefore(stop)) {
				Instant hourEnd = cursor.atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant();
				Instant segmentEnd = hourEnd.isBefore(stop) ? hourEnd : stop;
				byHour[cursor.atZone(zone).getHour()] += new Interval(cursor, segmentEnd).minutes();
				cursor = segmentEnd;
			}
		}
		Integer peakHour = null;
		for (int h = 0; h < 24; h++) {
			if (byHour[h] > 0 && (peakHour == null || byHour[h] > byHour[peakHour])) {
				peakHour = h;
			}
		}
		return new Screen(average(usageDays.stream().map(d -> (double) d.screenMinutes()).toList()), peakHour,
				average(usageDays.stream().map(d -> (double) d.unlocks()).toList()));
	}

	private Focus focus(String userId, Instant start, Instant end) {
		record Session(double minutes, Double planned) {
		}
		List<Session> sessions = jdbc.sql("""
				SELECT value, (metadata ->> 'plannedMinutes')::numeric AS planned FROM events
				WHERE user_id = :u AND category = 'focus' AND started_at >= :start AND started_at < :end
				""").param("u", userId).param("start", utc(start)).param("end", utc(end))
			.query((rs, i) -> new Session(rs.getDouble("value"),
					rs.getBigDecimal("planned") == null ? null : rs.getBigDecimal("planned").doubleValue()))
			.list();
		if (sessions.isEmpty()) {
			return new Focus(0, null, null);
		}
		// Calendar blocks have no plan to miss; timer sessions complete when they reach the planned length.
		long completed = sessions.stream().filter(s -> s.planned() == null || s.minutes() >= s.planned() - 0.5).count();
		return new Focus(sessions.size(), average(sessions.stream().map(Session::minutes).toList()),
				round2((double) completed / sessions.size()));
	}

	private Steps steps(List<DailyAggregate> week) {
		List<Integer> tracked = week.stream().map(DailyAggregate::steps).filter(Objects::nonNull).toList();
		return new Steps(average(tracked.stream().map(Integer::doubleValue).toList()),
				(int) tracked.stream().filter(s -> s < 5000).count(), tracked.size());
	}

	private Mood mood(String userId, List<DailyAggregate> week, Instant start, Instant end) {
		List<DailyAggregate> rated = week.stream().filter(d -> d.moodScore() != null).toList();
		DailyAggregate lowest = rated.stream().min(Comparator.comparing(DailyAggregate::moodScore)).orElse(null);
		List<String> notes = jdbc.sql("""
				SELECT metadata ->> 'note' FROM events
				WHERE user_id = :u AND category = 'mood' AND started_at >= :start AND started_at < :end
				  AND metadata ->> 'note' IS NOT NULL
				""").param("u", userId).param("start", utc(start)).param("end", utc(end)).query(String.class).list();
		return new Mood(average(rated.stream().map(d -> d.moodScore().doubleValue()).toList()),
				lowest == null ? null : lowest.date(), lowest == null ? null : lowest.moodScore().doubleValue(),
				keywords(notes, 6), rated.size());
	}

	private Day day(DailyAggregate d) {
		return new Day(d.date(), d.date().getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
				d.sleepHours() == null ? null : d.sleepHours().doubleValue(), d.screenMinutes(), d.steps(),
				d.topAppLabel(), d.topAppMinutes(), d.focusMinutes(),
				d.moodScore() == null ? null : d.moodScore().doubleValue());
	}

	static List<String> keywords(List<String> notes, int limit) {
		Map<String, Integer> counts = new HashMap<>();
		for (String note : notes) {
			if (note == null) {
				continue;
			}
			for (String word : note.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
				if (word.length() >= 3 && !STOPWORDS.contains(word)) {
					counts.merge(word, 1, Integer::sum);
				}
			}
		}
		return counts.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.limit(limit)
			.map(Map.Entry::getKey)
			.collect(Collectors.toCollection(ArrayList::new));
	}

	/** Average clock time, treating 23:30 and 00:30 as one hour apart rather than 23. */
	String circularMeanTime(List<OffsetDateTime> times) {
		if (times.isEmpty()) {
			return null;
		}
		double sin = 0;
		double cos = 0;
		for (OffsetDateTime t : times) {
			LocalTime local = t.atZoneSameInstant(zone).toLocalTime();
			double angle = 2 * Math.PI * (local.toSecondOfDay() / 86_400.0);
			sin += Math.sin(angle);
			cos += Math.cos(angle);
		}
		double angle = Math.atan2(sin, cos);
		if (angle < 0) {
			angle += 2 * Math.PI;
		}
		int minutes = (int) Math.round(angle / (2 * Math.PI) * 1440) % 1440;
		return "%02d:%02d".formatted(minutes / 60, minutes % 60);
	}

	private static Double average(List<Double> values) {
		return values.isEmpty() ? null : round1(values.stream().mapToDouble(Double::doubleValue).average().orElse(0));
	}

	private static double round1(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static double round2(double v) {
		return Math.round(v * 100) / 100.0;
	}

}
