package app.bepro.aggregates;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AggregateRepository {

	private final JdbcClient jdbc;

	public AggregateRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void upsert(String userId, DailyAggregate a) {
		jdbc.sql("""
				INSERT INTO daily_aggregates (user_id, date, sleep_hours, sleep_start, sleep_end, sleep_source,
				    screen_minutes, unlocks, usage_minutes, steps, top_app, top_app_label, top_app_minutes,
				    focus_sessions, focus_minutes, active_minutes, mood_score, updated_at)
				VALUES (:userId, :date, :sleepHours, :sleepStart, :sleepEnd, :sleepSource,
				    :screenMinutes, :unlocks, :usageMinutes, :steps, :topApp, :topAppLabel, :topAppMinutes,
				    :focusSessions, :focusMinutes, :activeMinutes, :moodScore, now())
				ON CONFLICT (user_id, date) DO UPDATE SET
				    sleep_hours = EXCLUDED.sleep_hours, sleep_start = EXCLUDED.sleep_start,
				    sleep_end = EXCLUDED.sleep_end, sleep_source = EXCLUDED.sleep_source,
				    screen_minutes = EXCLUDED.screen_minutes, unlocks = EXCLUDED.unlocks,
				    usage_minutes = EXCLUDED.usage_minutes, steps = EXCLUDED.steps,
				    top_app = EXCLUDED.top_app, top_app_label = EXCLUDED.top_app_label,
				    top_app_minutes = EXCLUDED.top_app_minutes, focus_sessions = EXCLUDED.focus_sessions,
				    focus_minutes = EXCLUDED.focus_minutes, active_minutes = EXCLUDED.active_minutes,
				    mood_score = EXCLUDED.mood_score, updated_at = now()
				""")
			.param("userId", userId)
			.param("date", a.date())
			.param("sleepHours", a.sleepHours())
			.param("sleepStart", a.sleepStart())
			.param("sleepEnd", a.sleepEnd())
			.param("sleepSource", a.sleepSource())
			.param("screenMinutes", a.screenMinutes())
			.param("unlocks", a.unlocks())
			.param("usageMinutes", a.usageMinutes())
			.param("steps", a.steps())
			.param("topApp", a.topApp())
			.param("topAppLabel", a.topAppLabel())
			.param("topAppMinutes", a.topAppMinutes())
			.param("focusSessions", a.focusSessions())
			.param("focusMinutes", a.focusMinutes())
			.param("activeMinutes", a.activeMinutes())
			.param("moodScore", a.moodScore())
			.update();
	}

	public Optional<DailyAggregate> find(String userId, LocalDate date) {
		return jdbc.sql("SELECT * FROM daily_aggregates WHERE user_id = :userId AND date = :date")
			.param("userId", userId)
			.param("date", date)
			.query(AggregateRepository::map)
			.optional();
	}

	/** Inclusive range, oldest first. Days without a row are not filled in. */
	public List<DailyAggregate> range(String userId, LocalDate from, LocalDate to) {
		return jdbc.sql("""
				SELECT * FROM daily_aggregates WHERE user_id = :userId AND date BETWEEN :from AND :to ORDER BY date
				""").param("userId", userId).param("from", from).param("to", to).query(AggregateRepository::map).list();
	}

	private static DailyAggregate map(ResultSet rs, int row) throws SQLException {
		return new DailyAggregate(rs.getObject("date", LocalDate.class), rs.getBigDecimal("sleep_hours"),
				rs.getObject("sleep_start", OffsetDateTime.class), rs.getObject("sleep_end", OffsetDateTime.class),
				rs.getString("sleep_source"), rs.getInt("screen_minutes"), rs.getInt("unlocks"),
				rs.getInt("usage_minutes"), (Integer) rs.getObject("steps"), rs.getString("top_app"),
				rs.getString("top_app_label"), (Integer) rs.getObject("top_app_minutes"), rs.getInt("focus_sessions"),
				rs.getInt("focus_minutes"), rs.getInt("active_minutes"), rs.getBigDecimal("mood_score"),
				rs.getObject("updated_at", OffsetDateTime.class));
	}

}
