package app.bepro.events;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import tools.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

	private static final String UPSERT = """
			INSERT INTO events (user_id, category, sub_category, value, unit, started_at, ended_at, source, metadata)
			VALUES (:userId, :category, :subCategory, :value, :unit, :startedAt, :endedAt, :source, CAST(:metadata AS jsonb))
			ON CONFLICT (user_id, source, category, sub_category, started_at) DO UPDATE SET
			    value = EXCLUDED.value,
			    unit = EXCLUDED.unit,
			    ended_at = EXCLUDED.ended_at,
			    metadata = EXCLUDED.metadata,
			    synced_at = now()
			""";

	private final NamedParameterJdbcTemplate namedJdbc;

	private final JdbcClient jdbc;

	private final ObjectMapper json;

	public EventRepository(NamedParameterJdbcTemplate namedJdbc, JdbcClient jdbc, ObjectMapper json) {
		this.namedJdbc = namedJdbc;
		this.jdbc = jdbc;
		this.json = json;
	}

	public void upsert(String userId, List<IncomingEvent> events) {
		SqlParameterSource[] batch = events.stream()
			.map(e -> new MapSqlParameterSource()
				.addValue("userId", userId)
				.addValue("category", e.category())
				.addValue("subCategory", e.subCategoryOrEmpty())
				.addValue("value", e.value())
				.addValue("unit", e.unit())
				.addValue("startedAt", utc(e.startedAt()))
				.addValue("endedAt", utc(e.endedAt()))
				.addValue("source", e.source())
				.addValue("metadata", json.writeValueAsString(e.metadata() == null ? Map.of() : e.metadata())))
			.toArray(SqlParameterSource[]::new);
		namedJdbc.batchUpdate(UPSERT, batch);
	}

	public record StoredEvent(UUID id, String category, String subCategory, BigDecimal value, String unit,
			OffsetDateTime startedAt, OffsetDateTime endedAt, String source, String metadata) {
	}

	public List<StoredEvent> find(String userId, String category, Instant from, Instant to, int limit, int offset) {
		return jdbc.sql("""
				SELECT id, category, sub_category, value, unit, started_at, ended_at, source, metadata::text AS metadata
				FROM events
				WHERE user_id = :userId AND (CAST(:category AS VARCHAR) IS NULL OR category = :category)
				  AND started_at >= :from AND started_at < :to
				ORDER BY started_at DESC
				LIMIT :limit OFFSET :offset
				""")
			.param("userId", userId)
			.param("category", category)
			.param("from", utc(from))
			.param("to", utc(to))
			.param("limit", limit)
			.param("offset", offset)
			.query(EventRepository::mapStored)
			.list();
	}

	/** Streams rows in chronological order without loading them all into memory. */
	public void stream(String userId, Instant from, Instant to, Consumer<StoredEvent> consumer) {
		namedJdbc.query("""
				SELECT id, category, sub_category, value, unit, started_at, ended_at, source, metadata::text AS metadata
				FROM events WHERE user_id = :userId AND started_at >= :from AND started_at < :to
				ORDER BY started_at
				""", new MapSqlParameterSource().addValue("userId", userId)
			.addValue("from", utc(from))
			.addValue("to", utc(to)), rs -> {
				consumer.accept(mapStored(rs, 0));
			});
	}

	public OffsetDateTime lastSyncedAt(String userId) {
		return jdbc.sql("SELECT max(synced_at) FROM events WHERE user_id = :userId")
			.param("userId", userId)
			.query(OffsetDateTime.class)
			.optional()
			.orElse(null);
	}

	private static StoredEvent mapStored(ResultSet rs, int row) throws SQLException {
		return new StoredEvent(rs.getObject("id", UUID.class), rs.getString("category"), rs.getString("sub_category"),
				rs.getBigDecimal("value"), rs.getString("unit"), rs.getObject("started_at", OffsetDateTime.class),
				rs.getObject("ended_at", OffsetDateTime.class), rs.getString("source"), rs.getString("metadata"));
	}

	public static OffsetDateTime utc(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

}
