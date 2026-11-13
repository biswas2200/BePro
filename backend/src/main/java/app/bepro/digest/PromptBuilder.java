package app.bepro.digest;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

import app.bepro.summary.WeekSummary;

import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

	static final String SYSTEM = """
			You are a personal productivity analyst. You receive a structured summary of one person's last 7 days,
			collected passively from their Android phone. Identify the top 1-2 patterns that are reducing their
			focus or wellbeing. For each, give:
			  (1) pattern: the pattern in one sentence,
			  (2) data_evidence: the supporting numbers from the data, in one or two plain sentences with units
			      and weekdays (e.g. Instagram 135 min on Monday, then 4.5 h of sleep). No quotation marks around
			      numbers and no JSON field names,
			  (3) action: one specific, measurable action to take this week.
			Rules:
			- Use only the data provided. Never invent numbers. If a field is null it was not tracked.
			- Prefer the single change with the biggest likely effect. Never return more than 2 insights.
			- Actions must be concrete (a time, an app, a number), e.g. "Put the phone on the charger outside the
			  bedroom at 23:00 on weeknights", not "sleep more".
			- confidence is "high" only when the pattern holds on at least 4 of the 7 days.
			- Times are local to the given timezone. Sleep is listed under the date the person woke up.
			- Respond in JSON only, matching the response schema.
			""";

	private final ObjectMapper json;

	public PromptBuilder(ObjectMapper json) {
		this.json = json;
	}

	public String system() {
		return SYSTEM;
	}

	public String user(WeekSummary summary) {
		return "Structured summary of the user's last 7 days (" + summary.from() + " to " + summary.to()
				+ ", timezone " + summary.timezone() + "):\n" + json.writeValueAsString(summary);
	}

	/** JSON Schema for the model's response (passed as responseJsonSchema). */
	public Map<String, Object> responseSchema() {
		Map<String, Object> insight = Map.of("type", "object", "properties",
				Map.of("pattern", Map.of("type", "string"), "data_evidence", Map.of("type", "string"), "action",
						Map.of("type", "string"), "category",
						Map.of("type", "string", "enum", List.copyOf(GeneratedInsight.CATEGORIES)), "confidence",
						Map.of("type", "string", "enum", List.of("high", "medium"))),
				"required", List.of("pattern", "data_evidence", "action", "category", "confidence"));
		return Map.of("type", "object", "properties",
				Map.of("insights", Map.of("type", "array", "items", insight, "minItems", 1, "maxItems", 2)),
				"required", List.of("insights"));
	}

}
