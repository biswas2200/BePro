package app.bepro.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import app.bepro.config.BeproProperties;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

	private final BeproProperties properties;

	private final JwtEncoder jwtEncoder;

	private final JdbcClient jdbc;

	private final Clock clock;

	public AuthController(BeproProperties properties, JwtEncoder jwtEncoder, JdbcClient jdbc, Clock clock) {
		this.properties = properties;
		this.jwtEncoder = jwtEncoder;
		this.jdbc = jdbc;
		this.clock = clock;
	}

	public record TokenRequest(@NotBlank @Size(max = 128) String deviceId, @NotBlank @Size(max = 256) String secret) {
	}

	public record TokenResponse(String accessToken, Instant expiresAt, String userId) {
	}

	@PostMapping("/token")
	public TokenResponse token(@Valid @RequestBody TokenRequest request) {
		byte[] expected = properties.auth().deviceSecret().getBytes(StandardCharsets.UTF_8);
		byte[] actual = request.secret().getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(expected, actual)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid device secret");
		}
		String userId = properties.userId();
		jdbc.sql("""
				INSERT INTO devices (device_id, user_id) VALUES (:deviceId, :userId)
				ON CONFLICT (device_id) DO UPDATE SET last_seen_at = now(), user_id = EXCLUDED.user_id
				""").param("deviceId", request.deviceId()).param("userId", userId).update();

		Instant now = clock.instant();
		Instant expiresAt = now.plus(properties.auth().tokenTtl());
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("bepro")
			.subject(userId)
			.issuedAt(now)
			.expiresAt(expiresAt)
			.claim("device", request.deviceId())
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new TokenResponse(token, expiresAt, userId);
	}

}
