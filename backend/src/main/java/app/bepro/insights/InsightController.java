package app.bepro.insights;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@Validated
@RestController
@RequestMapping("/v1/insights")
public class InsightController {

	private final InsightRepository insights;

	public InsightController(InsightRepository insights) {
		this.insights = insights;
	}

	@GetMapping("/latest")
	public List<Insight> latest(@AuthenticationPrincipal Jwt jwt) {
		return insights.latest(jwt.getSubject());
	}

	/** History, newest first. The phone polls with {@code since} to decide whether to notify. */
	@GetMapping
	public List<Insight> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Instant since,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		return insights.list(jwt.getSubject(), since == null ? Instant.EPOCH : since, limit);
	}

	public record StatusUpdate(@NotNull @Pattern(regexp = "dismissed|actioned") String status) {
	}

	@PatchMapping("/{id}")
	public Insight update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody StatusUpdate update) {
		return insights.updateStatus(jwt.getSubject(), id, update.status())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Insight not found"));
	}

}
