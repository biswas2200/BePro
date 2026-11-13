package app.bepro.digest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.bepro.summary.WeekSummary;
import app.bepro.summary.WeekSummary.AppUsage;

/**
 * Offline stand-in for the model: a handful of fixed heuristics over the same week summary.
 * Lets the whole pipeline run locally without GCP credentials.
 */
public class RuleBasedInsightGenerator implements InsightGenerator {

	private record Candidate(double score, GeneratedInsight insight) {
	}

	@Override
	public List<GeneratedInsight> generate(WeekSummary s) {
		List<Candidate> candidates = new ArrayList<>();

		if (s.sleep().nightsTracked() > 0 && s.sleep().nightsUnder6h() >= 2) {
			candidates.add(new Candidate(s.sleep().nightsUnder6h() * 2.0, new GeneratedInsight(
					"You slept under 6 hours on " + s.sleep().nightsUnder6h() + " of " + s.sleep().nightsTracked()
							+ " tracked nights.",
					"Average sleep " + s.sleep().avgHours() + " h, usual bedtime " + s.sleep().avgSleepTime()
							+ ", wake-up " + s.sleep().avgWakeTime() + ".",
					"Set a fixed lights-out 30 minutes earlier than " + s.sleep().avgSleepTime()
							+ " for the next 5 weeknights and leave the phone charging outside the bed.",
					"sleep", s.sleep().nightsUnder6h() >= 4 ? "high" : "medium")));
		}

		if (!s.topApps().isEmpty()) {
			AppUsage top = s.topApps().stream().max(Comparator.comparingDouble(AppUsage::dailyAvgMinutes)).orElseThrow();
			if (top.dailyAvgMinutes() >= 60) {
				int cap = (int) Math.max(20, Math.round(top.dailyAvgMinutes() * 0.6 / 5) * 5);
				candidates.add(new Candidate(top.dailyAvgMinutes() / 30.0, new GeneratedInsight(
						top.label() + " takes the largest share of your phone time.",
						top.label() + " averaged " + Math.round(top.dailyAvgMinutes()) + " min/day this week.",
						"Set an app timer of " + cap + " minutes/day for " + top.label() + " in Digital Wellbeing for 7 days.",
						"usage", top.dailyAvgMinutes() >= 90 ? "high" : "medium")));
			}
		}

		if (s.screen().avgUnlocks() != null && s.screen().avgUnlocks() >= 80) {
			candidates.add(new Candidate(s.screen().avgUnlocks() / 40.0, new GeneratedInsight(
					"You pick up your phone very often, which fragments attention.",
					"About " + Math.round(s.screen().avgUnlocks()) + " unlocks/day; peak screen hour is "
							+ s.screen().peakHour() + ":00.",
					"Turn on Do Not Disturb from " + s.screen().peakHour() + ":00 to " + (s.screen().peakHour() + 1) % 24
							+ ":00 daily and keep the phone face down during that hour.",
					"screen", "medium")));
		}

		if (s.steps().daysTracked() >= 3 && s.steps().daysUnder5000() >= 3) {
			candidates.add(new Candidate(s.steps().daysUnder5000(), new GeneratedInsight(
					"Low movement on most days.",
					s.steps().daysUnder5000() + " of " + s.steps().daysTracked() + " days were under 5,000 steps (avg "
							+ Math.round(s.steps().avgDaily()) + ").",
					"Take a 15-minute walk right after lunch on at least 5 days this week.", "steps",
					s.steps().daysUnder5000() >= 5 ? "high" : "medium")));
		}

		if (s.focus().count() == 0 && s.screen().avgDailyMinutes() != null && s.screen().avgDailyMinutes() > 180) {
			candidates.add(new Candidate(1.5, new GeneratedInsight(
					"No focus sessions were recorded while screen time stayed high.",
					"0 focus sessions; " + Math.round(s.screen().avgDailyMinutes()) + " min/day of screen time.",
					"Start one 25-minute focus session in the app every weekday morning before opening any social app.",
					"focus", "medium")));
		}

		List<GeneratedInsight> result = candidates.stream()
			.sorted(Comparator.comparingDouble(Candidate::score).reversed())
			.limit(2)
			.map(Candidate::insight)
			.toList();
		if (result.isEmpty()) {
			return List.of(new GeneratedInsight("No strong negative pattern stood out this week.",
					"Sleep avg " + s.sleep().avgHours() + " h, screen " + s.screen().avgDailyMinutes() + " min/day.",
					"Keep the current routine and log your mood daily so next week's digest has more to work with.",
					"mood", "medium"));
		}
		return result;
	}

}
