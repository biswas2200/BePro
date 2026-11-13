package app.bepro.summary;

import java.time.LocalDate;
import java.util.List;

/**
 * Seven days of aggregates in the shape the AI digest (and the app's weekly view) consumes.
 * Times of day are local to the configured zone, formatted HH:mm.
 */
public record WeekSummary(
		LocalDate from,
		LocalDate to,
		String timezone,
		int daysWithData,
		int completeDays,
		Sleep sleep,
		List<AppUsage> topApps,
		Screen screen,
		Focus focus,
		Steps steps,
		Mood mood,
		Activity activity,
		List<Day> days) {

	public record Sleep(Double avgHours, String avgSleepTime, String avgWakeTime, int nightsUnder6h, int nightsTracked) {
	}

	public record AppUsage(String packageName, String label, double dailyAvgMinutes) {
	}

	public record Screen(Double avgDailyMinutes, Integer peakHour, Double avgUnlocks) {
	}

	public record Focus(int count, Double avgDurationMinutes, Double completionRate) {
	}

	public record Steps(Double avgDaily, int daysUnder5000, int daysTracked) {
	}

	public record Mood(Double avgScore, LocalDate lowestDay, Double lowestScore, List<String> notesKeywords,
			int entries) {
	}

	public record Activity(Double avgActiveMinutes) {
	}

	/** Compact per-day row so the model can spot weekday patterns. */
	public record Day(LocalDate date, String weekday, Double sleepHours, int screenMinutes, Integer steps,
			String topApp, Integer topAppMinutes, int focusMinutes, Double mood) {
	}

}
