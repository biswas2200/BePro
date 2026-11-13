package app.bepro.digest;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.bepro.aggregates.AggregationService;
import app.bepro.config.BeproProperties;
import app.bepro.insights.Insight;
import app.bepro.insights.InsightRepository;
import app.bepro.summary.WeekSummary;
import app.bepro.summary.WeekSummaryService;

import org.springframework.stereotype.Service;

/** Builds last week's summary, asks the model for insights, and stores them. */
@Service
public class DigestService {

	private static final Logger log = LoggerFactory.getLogger(DigestService.class);

	private final AggregationService aggregation;

	private final WeekSummaryService summaries;

	private final InsightGenerator generator;

	private final InsightRepository insights;

	private final BeproProperties properties;

	private final Clock clock;

	public DigestService(AggregationService aggregation, WeekSummaryService summaries, InsightGenerator generator,
			InsightRepository insights, BeproProperties properties, Clock clock) {
		this.aggregation = aggregation;
		this.summaries = summaries;
		this.generator = generator;
		this.insights = insights;
		this.properties = properties;
		this.clock = clock;
	}

	public record DigestResult(String status, String reason, LocalDate weekStart, List<GeneratedInsight> insights) {

		static DigestResult skipped(String reason, LocalDate weekStart) {
			return new DigestResult("skipped", reason, weekStart, List.of());
		}

	}

	/** Runs the digest for the 7 days ending yesterday. {@code force} regenerates an existing week. */
	public DigestResult run(String userId, boolean force) {
		LocalDate end = LocalDate.now(clock.withZone(properties.zone())).minusDays(1);
		LocalDate start = end.minusDays(6);
		if (!force && insights.weeklyExists(userId, start)) {
			return DigestResult.skipped("Digest for this week already exists", start);
		}

		List<LocalDate> days = new ArrayList<>();
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			days.add(d);
		}
		aggregation.recompute(userId, days);
		WeekSummary summary = summaries.build(userId, end);

		int minDays = properties.digest().minCompleteDays();
		if (summary.completeDays() < minDays) {
			insights.insertKeyed(userId, Insight.NOTICE, "digest-skipped:" + start, new GeneratedInsight(
					"Not enough data for this week's digest.",
					"Only " + summary.completeDays() + " of 7 days had both sleep and phone-usage data (need " + minDays + ").",
					"Open Be PRO → Settings → Setup checklist and make sure Usage access, Autostart and 'No restrictions' battery mode are on.",
					"usage", "high"));
			return DigestResult.skipped("Only " + summary.completeDays() + " complete days (need " + minDays + ")", start);
		}

		List<GeneratedInsight> generated;
		try {
			generated = generator.generate(summary).stream().filter(GeneratedInsight::isValid).limit(2).toList();
		}
		catch (RuntimeException ex) {
			// Last week's insights stay visible; the next scheduled run tries again.
			log.error("Insight generation failed for week {}", start, ex);
			return new DigestResult("failed", ex.getMessage(), start, List.of());
		}
		if (generated.isEmpty()) {
			return new DigestResult("failed", "Generator returned no valid insights", start, List.of());
		}
		insights.insertWeekly(userId, start, clock.instant(), generated);
		return new DigestResult("generated", null, start, generated);
	}

}
