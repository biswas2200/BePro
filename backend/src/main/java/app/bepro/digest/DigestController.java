package app.bepro.digest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import app.bepro.config.BeproProperties;
import app.bepro.digest.DigestService.DigestResult;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DigestController {

	private final DigestService digests;

	private final BeproProperties properties;

	public DigestController(DigestService digests, BeproProperties properties) {
		this.digests = digests;
		this.properties = properties;
	}

	/** "Generate now" from the app. */
	@PostMapping("/v1/digest/run")
	public DigestResult runForMe(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean force) {
		return digests.run(jwt.getSubject(), force);
	}

	/** Called by Cloud Scheduler every Sunday; authenticated with a shared header, not a JWT. */
	@PostMapping("/v1/internal/digest/run")
	public DigestResult runScheduled(@RequestHeader(name = "X-Bepro-Internal-Token", required = false) String token) {
		String expected = properties.digest().internalToken();
		if (expected == null || expected.isBlank() || token == null
				|| !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
		}
		return digests.run(properties.userId(), false);
	}

}
