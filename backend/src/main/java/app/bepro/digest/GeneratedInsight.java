package app.bepro.digest;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One insight card as produced by the model (snake_case to match the output schema). */
public record GeneratedInsight(
		String pattern,
		@JsonProperty("data_evidence") @JsonAlias("dataEvidence") String dataEvidence,
		String action,
		String category,
		String confidence) {

	public static final Set<String> CATEGORIES = Set.of("sleep", "usage", "screen", "focus", "mood", "steps");

	public static final Set<String> CONFIDENCE = Set.of("high", "medium");

	public boolean isValid() {
		return notBlank(pattern) && notBlank(dataEvidence) && notBlank(action) && CATEGORIES.contains(category)
				&& CONFIDENCE.contains(confidence);
	}

	public GeneratedInsight trimmed() {
		return new GeneratedInsight(cap(pattern, 300), cap(dataEvidence, 500), cap(action, 300), category, confidence);
	}

	public record Batch(List<GeneratedInsight> insights) {
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	private static String cap(String s, int max) {
		String t = s.strip();
		return t.length() <= max ? t : t.substring(0, max - 1) + "…";
	}

}
