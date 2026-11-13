package app.bepro.aggregates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import app.bepro.config.BeproProperties;
import app.bepro.events.Categories;
import app.bepro.events.Sources;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import static app.bepro.events.EventRepository.utc;

/**
 * Rolls raw events up into one {@link DailyAggregate} per local calendar day.
 * Recomputed for the affected days after every upload, so the dashboard is always current.
 */
@Service
public class AggregationService {

	private final JdbcClient jdbc;

	private final AggregateRepository aggregates;

	private final ZoneId zone;

	public AggregationService(JdbcClient jdbc, AggregateRepository aggregates, BeproProperties properties) {
		this.jdbc = jdbc;
		this.aggregates = aggregates;
		this.zone = properties.zone();
	}

	public void recompute(String userId, Collection<LocalDate> dates) {
		for (LocalDate date : dates) {
			aggregates.upsert(userId, compute(userId, date));
		}
	}

	public DailyAggregate compute(String userId, LocalDate date) {
		Instant start = date.atStartOfDay(zone).toInstant();
		Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();

		double screenMinutes = overlapMinutes(userId, Categories.SCREEN, Categories.SCREEN_SESSION, start, end);
		int unlocks = jdbc.sql("""
				SELECT count(*) FROM events WHERE user_id = :u AND category = 'screen' AND sub_category = 'unlock'
				  AND started_at >= :start AND started_at < :end
				""").params(params(userId, start, end)).query(Integer.class).single();

		List<AppMinutes> apps = appMinutes(userId, start, end);
		double usageMinutes = apps.stream().mapToDouble(AppMinutes::minutes).sum();
		AppMinutes top = apps.isEmpty() ? null : apps.getFirst();

		Integer steps = steps(userId, start, end);

		record Focus(int count, double minutes) {
		}
		Focus focus = jdbc.sql("""
				SELECT count(*) AS count, COALESCE(sum(value), 0) AS minutes FROM events
				WHERE user_id = :u AND category = 'focus' AND started_at >= :start AND started_at < :end
				""").params(params(userId, start, end))
			.query((rs, i) -> new Focus(rs.getInt("count"), rs.getDouble("minutes")))
			.single();

		BigDecimal mood = jdbc.sql("""
				SELECT avg(value) FROM events
				WHERE user_id = :u AND category = 'mood' AND started_at >= :start AND started_at < :end
				""").params(params(userId, start, end)).query(BigDecimal.class).optional().orElse(null);

		double activeMinutes = jdbc.sql("""
				SELECT COALESCE(sum(value), 0) FROM events
				WHERE user_id = :u AND category = 'activity' AND sub_category IN ('walking', 'running', 'on_bicycle')
				  AND started_at >= :start AND started_at < :end
				""").params(params(userId, start, end)).query(Double.class).single();

		Optional<Sleep> sleep = sleep(userId, date, start);

		return new DailyAggregate(date, sleep.map(s -> scale(s.hours(), 2)).orElse(null),
				sleep.map(s -> utc(s.start())).orElse(null), sleep.map(s -> utc(s.end())).orElse(null),
				sleep.map(Sleep::source).orElse(null), round(screenMinutes), unlocks, round(usageMinutes), steps,
				top == null ? null : top.packageName(), top == null ? null : top.label(),
				top == null ? null : round(top.minutes()), focus.count(), round(focus.minutes()), round(activeMinutes),
				mood == null ? null : mood.setScale(2, RoundingMode.HALF_UP), null);
	}

	public record AppMinutes(String packageName, String label, double minutes) {
	}

	/** Foreground minutes per app inside [start, end), most used first. */
	public List<AppMinutes> appMinutes(String userId, Instant start, Instant end) {
		return jdbc.sql("""
				SELECT sub_category AS package_name, max(metadata ->> 'label') AS label,
				       sum(extract(epoch FROM (least(ended_at, :end) - greatest(started_at, :start)))) / 60.0 AS minutes
				FROM events
				WHERE user_id = :u AND category = 'usage' AND started_at < :end AND ended_at > :start
				GROUP BY sub_category
				ORDER BY minutes DESC
				""").params(params(userId, start, end))
			.query((rs, i) -> new AppMinutes(rs.getString("package_name"),
					rs.getString("label") == null ? rs.getString("package_name") : rs.getString("label"),
					rs.getDouble("minutes")))
			.list();
	}

	private double overlapMinutes(String userId, String category, String subCategory, Instant start, Instant end) {
		return jdbc.sql("""
				SELECT COALESCE(sum(extract(epoch FROM (least(ended_at, :end) - greatest(started_at, :start)))), 0) / 60.0
				FROM events
				WHERE user_id = :u AND category = :category AND sub_category = :sub
				  AND started_at < :end AND ended_at > :start
				""").params(params(userId, start, end)).param("category", category).param("sub", subCategory)
			.query(Double.class)
			.single();
	}

	/** Health Connect steps win when present (they're deduplicated across apps); else the phone sensor. */
	private Integer steps(String userId, Instant start, Instant end) {
		Map<String, Double> bySource = jdbc.sql("""
				SELECT source, sum(value) AS total FROM events
				WHERE user_id = :u AND category = 'steps' AND started_at >= :start AND started_at < :end
				GROUP BY source
				""").params(params(userId, start, end))
			.query((rs, i) -> Map.entry(rs.getString("source"), rs.getDouble("total")))
			.list()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		if (bySource.containsKey(Sources.HEALTH_CONNECT)) {
			return round(bySource.get(Sources.HEALTH_CONNECT));
		}
		if (bySource.containsKey(Sources.SENSOR)) {
			return round(bySource.get(Sources.SENSOR));
		}
		return null;
	}

	private record Sleep(Instant start, Instant end, double hours, String source) {
	}

	/**
	 * Sleep is filed under the date you woke up. Priority: manual entry, then Health Connect,
	 * then inference from screen-off gaps.
	 */
	private Optional<Sleep> sleep(String userId, LocalDate wakeDate, Instant dayStart) {
		Instant wakeWindowEnd = dayStart.plus(Duration.ofHours(18));
		for (String source : List.of(Sources.MANUAL, Sources.HEALTH_CONNECT)) {
			Optional<Sleep> recorded = jdbc.sql("""
					SELECT started_at, ended_at FROM events
					WHERE user_id = :u AND category = 'sleep' AND source = :source
					  AND ended_at >= :start AND ended_at < :end
					ORDER BY (ended_at - started_at) DESC, synced_at DESC
					LIMIT 1
					""").params(params(userId, dayStart, wakeWindowEnd)).param("source", source)
				.query((rs, i) -> {
					Instant s = rs.getObject("started_at", OffsetDateTime.class).toInstant();
					Instant e = rs.getObject("ended_at", OffsetDateTime.class).toInstant();
					return new Sleep(s, e, Duration.between(s, e).toMinutes() / 60.0, source);
				})
				.optional();
			if (recorded.isPresent()) {
				return recorded;
			}
		}

		Interval window = SleepInference.window(wakeDate, zone);
		List<Interval> sessions = jdbc.sql("""
				SELECT started_at, ended_at FROM events
				WHERE user_id = :u AND category = 'screen' AND sub_category = 'session'
				  AND started_at < :end AND ended_at > :start
				ORDER BY started_at
				""").params(params(userId, window.start(), window.end()))
			.query((rs, i) -> new Interval(rs.getObject("started_at", OffsetDateTime.class).toInstant(),
					rs.getObject("ended_at", OffsetDateTime.class).toInstant()))
			.list();
		return SleepInference.infer(wakeDate, zone, sessions)
			.map(n -> new Sleep(n.start(), n.end(), n.hours(), "inferred"));
	}

	private static Map<String, Object> params(String userId, Instant start, Instant end) {
		return Map.of("u", userId, "start", utc(start), "end", utc(end));
	}

	private static int round(double value) {
		return (int) Math.round(value);
	}

	private static BigDecimal scale(double value, int places) {
		return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP);
	}

}
