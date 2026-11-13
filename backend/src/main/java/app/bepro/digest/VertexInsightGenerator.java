package app.bepro.digest;

import java.util.List;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import app.bepro.config.BeproProperties;
import app.bepro.summary.WeekSummary;

/**
 * Calls Gemini on Vertex AI with structured JSON output. Authenticates with Application Default
 * Credentials: the Cloud Run service account in production, {@code gcloud auth application-default login}
 * locally.
 */
public class VertexInsightGenerator implements InsightGenerator {

	private static final Logger log = LoggerFactory.getLogger(VertexInsightGenerator.class);

	private static final int MAX_ATTEMPTS = 3;

	private final Client client;

	private final BeproProperties.Vertex config;

	private final PromptBuilder prompts;

	private final ObjectMapper json;

	public VertexInsightGenerator(BeproProperties.Vertex config, PromptBuilder prompts, ObjectMapper json) {
		if (config.project() == null || config.project().isBlank()) {
			throw new IllegalStateException("bepro.ai.vertex.project (GOOGLE_CLOUD_PROJECT) must be set for provider=vertex");
		}
		this.client = Client.builder().vertexAI(true).project(config.project()).location(config.location()).build();
		this.config = config;
		this.prompts = prompts;
		this.json = json;
	}

	@Override
	public List<GeneratedInsight> generate(WeekSummary summary) {
		GenerateContentConfig request = GenerateContentConfig.builder()
			.systemInstruction(Content.fromParts(Part.fromText(prompts.system())))
			.responseMimeType("application/json")
			.responseJsonSchema(prompts.responseSchema())
			.temperature(config.temperature())
			.build();
		String userPrompt = prompts.user(summary);

		RuntimeException last = null;
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				GenerateContentResponse response = client.models.generateContent(config.model(), userPrompt, request);
				List<GeneratedInsight> insights = parse(response.text());
				if (!insights.isEmpty()) {
					return insights;
				}
				last = new IllegalStateException("Model returned no valid insights: " + response.text());
			}
			catch (RuntimeException ex) {
				last = ex;
			}
			log.warn("Vertex AI attempt {}/{} failed: {}", attempt, MAX_ATTEMPTS, last.getMessage());
			if (attempt < MAX_ATTEMPTS) {
				sleep((long) Math.pow(2, attempt) * 1000);
			}
		}
		throw last;
	}

	List<GeneratedInsight> parse(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		GeneratedInsight.Batch batch = json.readValue(text, GeneratedInsight.Batch.class);
		if (batch.insights() == null) {
			return List.of();
		}
		return batch.insights().stream().filter(GeneratedInsight::isValid).map(GeneratedInsight::trimmed).limit(2).toList();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
