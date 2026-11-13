package app.bepro.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import app.bepro.aggregates.AggregateRepository;
import app.bepro.aggregates.DailyAggregate;
import app.bepro.config.BeproProperties;
import app.bepro.digest.GeneratedInsight;
import app.bepro.insights.Insight;
import app.bepro.insights.InsightRepository;

import org.springframework.stereotype.Component;

/** Cheap threshold checks run after every upload; each alert fires at most once per streak. */
@Component
public class AnomalyDetector {

	static final double SHORT_SLEEP_HOURS = 5.0;

	static final int SHORT_SLEEP_STREAK = 3;

	static final int SCREEN_SPIKE_MIN_MINUTES = 240;

	static final double SCREEN_SPIKE_FACTOR = 1.5;

	private final AggregateRepository aggregates;

	private final InsightRepository insights;

	private final BeproProperties properties;

	private final Clock clock;

	public AnomalyDetector(AggregateRepository aggregates, InsightRepository insights, BeproProperties properties,
			Clock clock) {
		this.aggregates = aggregates;
		this.insights = insights;
		this.properties = properties;
		this.clock = clock;
	}

	public void check(String userId, LocalDate date) {
		checkShortSleep(userId, date);
		checkScreenSpike(userId, date);
	}

	private void checkShortSleep(String userId, LocalDate date) {
		List<DailyAggregate> nights = aggregates.range(userId, date.minusDays(SHORT_SLEEP_STREAK - 1), date);
		boolean streak = nights.size() == SHORT_SLEEP_STREAK && nights.stream()
			.allMatch(n -> n.sleepHours() != null && n.sleepHours().doubleValue() < SHORT_SLEEP_HOURS);
		if (!streak || insights.recentAlertExists(userId, "sleep-under-5h:", clock.instant().minus(Duration.ofDays(3)))) {
			return;
		}
		String hours = nights.stream().map(n -> n.sleepHours().toPlainString() + " h").reduce((a, b) -> a + ", " + b).orElse("");
		insights.insertKeyed(userId, Insight.ALERT, "sleep-under-5h:" + nights.getFirst().date(),
				new GeneratedInsight("You've slept under 5 hours three nights in a row.",
						"Last three nights: " + hours + ".",
						"Tonight, skip screens after 22:30 and aim to be in bed by 23:00. Protect the next two nights too.",
						"sleep", "high"));
	}

	private void checkScreenSpike(String userId, LocalDate date) {
		LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
		if (date.isAfter(today) || date.isBefore(today.minusDays(1))) {
			return;
		}
		DailyAggregate day = aggregates.find(userId, date).orElse(null);
		if (day == null || day.screenMinutes() < SCREEN_SPIKE_MIN_MINUTES) {
			return;
		}
		List<DailyAggregate> baseline = aggregates.range(userId, date.minusDays(14), date.minusDays(1))
			.stream()
			.filter(d -> d.screenMinutes() > 0)
			.toList();
		if (baseline.size() < 7) {
			return;
		}
		double avg = baseline.stream().mapToInt(DailyAggregate::screenMinutes).average().orElse(0);
		if (day.screenMinutes() < avg * SCREEN_SPIKE_FACTOR) {
			return;
		}
		insights.insertKeyed(userId, Insight.ALERT, "screen-spike:" + date,
				new GeneratedInsight("Screen time today is well above your normal.",
						day.screenMinutes() + " min so far vs a 2-week average of " + Math.round(avg) + " min.",
						"Put the phone in another room for the next hour" + (day.topAppLabel() == null ? "."
								: " and close " + day.topAppLabel() + " (top app today, " + day.topAppMinutes() + " min)."),
						"screen", "medium"));
	}

}
