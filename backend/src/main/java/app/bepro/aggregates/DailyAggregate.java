package app.bepro.aggregates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record DailyAggregate(
		LocalDate date,
		BigDecimal sleepHours,
		OffsetDateTime sleepStart,
		OffsetDateTime sleepEnd,
		String sleepSource,
		int screenMinutes,
		int unlocks,
		int usageMinutes,
		Integer steps,
		String topApp,
		String topAppLabel,
		Integer topAppMinutes,
		int focusSessions,
		int focusMinutes,
		int activeMinutes,
		BigDecimal moodScore,
		OffsetDateTime updatedAt) {

	public static DailyAggregate empty(LocalDate date) {
		return new DailyAggregate(date, null, null, null, null, 0, 0, 0, null, null, null, null, 0, 0, 0, null, null);
	}

	/** A day the AI can reason about: we know how the night went and how the phone was used. */
	public boolean isComplete() {
		return sleepHours != null && (usageMinutes > 0 || screenMinutes > 0);
	}

	public boolean hasAnyData() {
		return sleepHours != null || screenMinutes > 0 || usageMinutes > 0 || steps != null || focusSessions > 0
				|| moodScore != null || activeMinutes > 0;
	}

}
