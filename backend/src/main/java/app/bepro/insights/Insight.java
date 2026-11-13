package app.bepro.insights;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Insight(
		UUID id,
		String kind,
		OffsetDateTime generatedAt,
		LocalDate weekStart,
		String pattern,
		String dataEvidence,
		String action,
		String category,
		String confidence,
		OffsetDateTime dismissedAt,
		OffsetDateTime actionedAt) {

	public static final String WEEKLY = "weekly";

	public static final String ALERT = "alert";

	/** Informational, e.g. "not enough data for this week's digest". */
	public static final String NOTICE = "notice";

	@JsonProperty("status")
	public String status() {
		if (dismissedAt != null) {
			return "dismissed";
		}
		return actionedAt != null ? "actioned" : "active";
	}

}
