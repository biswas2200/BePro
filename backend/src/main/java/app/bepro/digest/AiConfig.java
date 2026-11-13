package app.bepro.digest;

import tools.jackson.databind.ObjectMapper;

import app.bepro.config.BeproProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class AiConfig {

	@Bean
	InsightGenerator insightGenerator(BeproProperties properties, PromptBuilder prompts, ObjectMapper json) {
		return switch (properties.ai().provider()) {
			case "vertex" -> new VertexInsightGenerator(properties.ai().vertex(), prompts, json);
			case "rules" -> new RuleBasedInsightGenerator();
			default -> throw new IllegalStateException(
					"bepro.ai.provider must be 'vertex' or 'rules', was: " + properties.ai().provider());
		};
	}

}
