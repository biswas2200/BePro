package app.bepro.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("bepro")
public record BeproProperties(
		@DefaultValue("me") String userId,
		@DefaultValue("Asia/Kolkata") ZoneId zone,
		@DefaultValue Auth auth,
		@DefaultValue Digest digest,
		@DefaultValue Ai ai) {

	public record Auth(String deviceSecret, String jwtSecret, @DefaultValue("30d") Duration tokenTtl) {
	}

	public record Digest(
			@DefaultValue("false") boolean schedulerEnabled,
			@DefaultValue("0 0 8 * * SUN") String cron,
			String internalToken,
			@DefaultValue("5") int minCompleteDays) {
	}

	public record Ai(@DefaultValue("rules") String provider, @DefaultValue Vertex vertex) {
	}

	public record Vertex(
			String project,
			@DefaultValue("global") String location,
			@DefaultValue("gemini-3.5-flash") String model,
			@DefaultValue("0.2") float temperature) {
	}

}
