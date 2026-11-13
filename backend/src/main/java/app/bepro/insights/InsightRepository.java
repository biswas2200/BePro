package app.bepro.insights;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import app.bepro.digest.GeneratedInsight;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static app.bepro.events.EventRepository.utc;

@Repository
public class InsightRepository {

	private final JdbcClient jdbc;

	public InsightRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public boolean weeklyExists(String userId, LocalDate weekStart) {
		return jdbc.sql("SELECT count(*) FROM insights WHERE user_id = :u AND kind = 'weekly' AND week_start = :w")
			.param("u", userId)
			.param("w", weekStart)
			.query(Integer.class)
			.single() > 0;
	}

	/** Stores one digest run; all rows share the same generated_at so they read back as a batch. */
	public void insertWeekly(String userId, LocalDate weekStart, Instant generatedAt, List<GeneratedInsight> batch) {
		for (GeneratedInsight g : batch) {
			jdbc.sql("""
					INSERT INTO insights (user_id, kind, generated_at, week_start, pattern, data_evidence, action, category, confidence)
					VALUES (:u, 'weekly', :at, :w, :pattern, :evidence, :action, :category, :confidence)
					""")
				.param("u", userId)
				.param("at", utc(generatedAt))
				.param("w", weekStart)
				.param("pattern", g.pattern())
				.param("evidence", g.dataEvidence())
				.param("action", g.action())
				.param("category", g.category())
				.param("confidence", g.confidence())
				.update();
		}
	}

	/** Inserts an alert/notice once per key. Returns false if it already existed. */
	public boolean insertKeyed(String userId, String kind, String key, GeneratedInsight g) {
		return jdbc.sql("""
				INSERT INTO insights (user_id, kind, alert_key, pattern, data_evidence, action, category, confidence)
				VALUES (:u, :kind, :key, :pattern, :evidence, :action, :category, :confidence)
				ON CONFLICT (user_id, alert_key) WHERE alert_key IS NOT NULL DO NOTHING
				""")
			.param("u", userId)
			.param("kind", kind)
			.param("key", key)
			.param("pattern", g.pattern())
			.param("evidence", g.dataEvidence())
			.param("action", g.action())
			.param("category", g.category())
			.param("confidence", g.confidence())
			.update() > 0;
	}

	public boolean recentAlertExists(String userId, String keyPrefix, Instant since) {
		return jdbc.sql("""
				SELECT count(*) FROM insights WHERE user_id = :u AND alert_key LIKE :prefix AND generated_at >= :since
				""").param("u", userId).param("prefix", keyPrefix + "%").param("since", utc(since))
			.query(Integer.class)
			.single() > 0;
	}

	/** The newest weekly batch plus any alerts/notices from the last 7 days that weren't dismissed. */
	public List<Insight> latest(String userId) {
		return jdbc.sql("""
				SELECT * FROM insights
				WHERE user_id = :u AND (
				    (kind = 'weekly' AND generated_at = (SELECT max(generated_at) FROM insights WHERE user_id = :u AND kind = 'weekly'))
				    OR (kind <> 'weekly' AND dismissed_at IS NULL AND generated_at > now() - interval '7 days'))
				ORDER BY CASE kind WHEN 'alert' THEN 0 WHEN 'weekly' THEN 1 ELSE 2 END, generated_at DESC
				""").param("u", userId).query(InsightRepository::map).list();
	}

	public List<Insight> list(String userId, Instant since, int limit) {
		return jdbc.sql("""
				SELECT * FROM insights WHERE user_id = :u AND generated_at > :since
				ORDER BY generated_at DESC LIMIT :limit
				""").param("u", userId).param("since", utc(since)).param("limit", limit)
			.query(InsightRepository::map)
			.list();
	}

	public Optional<Insight> updateStatus(String userId, UUID id, String status) {
		String column = switch (status) {
			case "dismissed" -> "dismissed_at";
			case "actioned" -> "actioned_at";
			default -> throw new IllegalArgumentException(status);
		};
		return jdbc.sql("UPDATE insights SET " + column + " = now() WHERE user_id = :u AND id = :id RETURNING *")
			.param("u", userId)
			.param("id", id)
			.query(InsightRepository::map)
			.optional();
	}

	private static Insight map(ResultSet rs, int row) throws SQLException {
		return new Insight(rs.getObject("id", UUID.class), rs.getString("kind"),
				rs.getObject("generated_at", OffsetDateTime.class), rs.getObject("week_start", LocalDate.class),
				rs.getString("pattern"), rs.getString("data_evidence"), rs.getString("action"),
				rs.getString("category"), rs.getString("confidence"),
				rs.getObject("dismissed_at", OffsetDateTime.class), rs.getObject("actioned_at", OffsetDateTime.class));
	}

}
