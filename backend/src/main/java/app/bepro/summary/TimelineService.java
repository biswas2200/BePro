package app.bepro.summary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.bepro.aggregates.Interval;
import app.bepro.config.BeproProperties;
import app.bepro.events.Sources;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import static app.bepro.events.EventRepository.utc;

/** Hour-by-hour breakdown of one day for the timeline heatmap. */
@Service
public class TimelineService {

	private final JdbcClient jdbc;

	private final ZoneId zone;

	public TimelineService(JdbcClient jdbc, BeproProperties properties) {
		this.jdbc = jdbc;
		this.zone = properties.zone();
	}

	public record HourBucket(int hour, int screenMinutes, int unlocks, int steps, int activeMinutes, String topApp,
			int topAppMinutes) {
	}

	public record Timeline(LocalDate date, List<HourBucket> hours) {
	}

	private record Row(String category, String subCategory, double value, Instant start, Instant end, String source,
			String label) {
	}

	public Timeline build(String userId, LocalDate date) {
		Instant dayStart = date.atStartOfDay(zone).toInstant();
		Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
		List<Row> rows = jdbc.sql("""
				SELECT category, sub_category, value, started_at, COALESCE(ended_at, started_at) AS ended_at, source,
				       metadata ->> 'label' AS label
				FROM events
				WHERE user_id = :u AND category IN ('screen', 'usage', 'steps', 'activity')
				  AND started_at < :end AND COALESCE(ended_at, started_at) >= :start
				""").param("u", userId).param("start", utc(dayStart)).param("end", utc(dayEnd))
			.query((rs, i) -> new Row(rs.getString("category"), rs.getString("sub_category"), rs.getDouble("value"),
					rs.getObject("started_at", OffsetDateTime.class).toInstant(),
					rs.getObject("ended_at", OffsetDateTime.class).toInstant(), rs.getString("source"),
					rs.getString("label")))
			.list();
		boolean hasHealthConnectSteps = rows.stream()
			.anyMatch(r -> r.category().equals("steps") && Sources.HEALTH_CONNECT.equals(r.source()));

		double[] screen = new double[24];
		int[] unlocks = new int[24];
		double[] steps = new double[24];
		double[] active = new double[24];
		List<Map<String, Double>> apps = new ArrayList<>();
		Map<String, String> labels = new HashMap<>();
		for (int h = 0; h < 24; h++) {
			apps.add(new HashMap<>());
		}

		for (Row r : rows) {
			switch (r.category()) {
				case "screen" -> {
					if ("unlock".equals(r.subCategory())) {
						if (!r.start().isBefore(dayStart)) {
							unlocks[hourOf(r.start())]++;
						}
					}
					else {
						spread(r, dayStart, dayEnd, (h, minutes, fraction) -> screen[h] += minutes);
					}
				}
				case "usage" -> {
					labels.putIfAbsent(r.subCategory(), r.label() == null ? r.subCategory() : r.label());
					spread(r, dayStart, dayEnd, (h, minutes, fraction) -> apps.get(h).merge(r.subCategory(), minutes, Double::sum));
				}
				case "steps" -> {
					boolean preferred = hasHealthConnectSteps == Sources.HEALTH_CONNECT.equals(r.source());
					if (preferred) {
						if (r.end().equals(r.start())) {
							steps[hourOf(r.start())] += r.value();
						}
						else {
							spread(r, dayStart, dayEnd, (h, minutes, fraction) -> steps[h] += r.value() * fraction);
						}
					}
				}
				case "activity" -> {
					if (List.of("walking", "running", "on_bicycle").contains(r.subCategory())) {
						spread(r, dayStart, dayEnd, (h, minutes, fraction) -> active[h] += minutes);
					}
				}
				default -> {
				}
			}
		}

		List<HourBucket> hours = new ArrayList<>(24);
		for (int h = 0; h < 24; h++) {
			Map.Entry<String, Double> top = apps.get(h)
				.entrySet()
				.stream()
				.max(Map.Entry.comparingByValue())
				.orElse(null);
			hours.add(new HourBucket(h, (int) Math.round(Math.min(screen[h], 60)), unlocks[h],
					(int) Math.round(steps[h]), (int) Math.round(Math.min(active[h], 60)),
					top == null ? null : labels.get(top.getKey()), top == null ? 0 : (int) Math.round(top.getValue())));
		}
		return new Timeline(date, hours);
	}

	private interface HourConsumer {

		void accept(int hour, double minutes, double fractionOfEvent);

	}

	/** Splits an interval event across the local hours it covers (only the part inside the day). */
	private void spread(Row r, Instant dayStart, Instant dayEnd, HourConsumer consumer) {
		double total = new Interval(r.start(), r.end()).minutes();
		Instant cursor = r.start().isBefore(dayStart) ? dayStart : r.start();
		Instant stop = r.end().isAfter(dayEnd) ? dayEnd : r.end();
		while (cursor.isBefore(stop)) {
			Instant hourEnd = cursor.atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant();
			Instant segmentEnd = hourEnd.isBefore(stop) ? hourEnd : stop;
			double minutes = new Interval(cursor, segmentEnd).minutes();
			consumer.accept(hourOf(cursor), minutes, total > 0 ? minutes / total : 0);
			cursor = segmentEnd;
		}
	}

	private int hourOf(Instant instant) {
		return instant.atZone(zone).getHour();
	}

}
