package app.bepro.aggregates;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import app.bepro.aggregates.SleepInference.Night;

import static org.assertj.core.api.Assertions.assertThat;

class SleepInferenceTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

	private static final LocalDate WAKE = LocalDate.of(2026, 10, 5);

	@Test
	void longestNightGapIsSleep() {
		List<Interval> sessions = sessions("-1 21:00-21:20", "-1 23:10-23:30", "07:00-07:15", "09:00-09:30");

		Night night = SleepInference.infer(WAKE, ZONE, sessions).orElseThrow();

		assertThat(night.hours()).isEqualTo(7.5);
		assertThat(night.start()).isEqualTo(at(-1, "23:30"));
		assertThat(night.end()).isEqualTo(at(0, "07:00"));
	}

	@Test
	void shortCheckInTheMiddleOfTheNightIsBridged() {
		List<Interval> sessions = sessions("-1 22:30-23:00", "03:00-03:02", "07:00-07:20");

		Night night = SleepInference.infer(WAKE, ZONE, sessions).orElseThrow();

		assertThat(night.start()).isEqualTo(at(-1, "23:00"));
		assertThat(night.end()).isEqualTo(at(0, "07:00"));
		assertThat(night.hours()).isEqualTo(8.0); // 8h minus the 2-minute check
	}

	@Test
	void quickMorningCheckIsTreatedAsWakeUp() {
		List<Interval> sessions = sessions("-1 22:30-23:00", "07:00-07:02", "08:00-08:30");

		Night night = SleepInference.infer(WAKE, ZONE, sessions).orElseThrow();

		assertThat(night.end()).isEqualTo(at(0, "07:00"));
		assertThat(night.hours()).isEqualTo(8.0);
	}

	@Test
	void noMorningUseMeansUnknown() {
		assertThat(SleepInference.infer(WAKE, ZONE, sessions("-1 20:00-20:30", "-1 23:00-23:30"))).isEmpty();
	}

	@Test
	void gapStartingAfterSixAmIsNotSleep() {
		Optional<Night> night = SleepInference.infer(WAKE, ZONE,
				sessions("-1 22:00-23:59", "00:00-06:30", "12:30-13:00"));

		assertThat(night).isEmpty();
	}

	@Test
	void tooShortGapIsIgnored() {
		assertThat(SleepInference.infer(WAKE, ZONE, sessions("-1 23:00-02:00", "04:00-04:30"))).isEmpty();
	}

	@Test
	void overlappingSessionsAreMerged() {
		List<Interval> sessions = sessions("-1 22:00-23:00", "-1 22:30-23:30", "06:30-07:00");

		assertThat(SleepInference.infer(WAKE, ZONE, sessions).orElseThrow().hours()).isEqualTo(7.0);
	}

	/** "-1 HH:mm-HH:mm" is the evening before the wake date; an end earlier than the start rolls over. */
	private static List<Interval> sessions(String... specs) {
		List<Interval> result = new ArrayList<>();
		for (String spec : specs) {
			int dayOffset = spec.startsWith("-1 ") ? -1 : 0;
			String[] times = spec.replace("-1 ", "").split("-");
			LocalTime start = LocalTime.parse(times[0]);
			LocalTime end = LocalTime.parse(times[1]);
			int endOffset = end.isBefore(start) ? dayOffset + 1 : dayOffset;
			result.add(new Interval(at(dayOffset, times[0]), at(endOffset, times[1])));
		}
		return result;
	}

	private static java.time.Instant at(int dayOffset, String time) {
		return WAKE.plusDays(dayOffset).atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
	}

}
