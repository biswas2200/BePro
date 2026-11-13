package app.bepro.digest;

import java.util.List;

import app.bepro.summary.WeekSummary;

public interface InsightGenerator {

	/** Returns 1-2 insights for the week. Throws if the provider fails or returns unusable output. */
	List<GeneratedInsight> generate(WeekSummary summary);

}
