package app.bepro.config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(BeproProperties.class)
public class SecurityConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	SecretKey jwtKey(BeproProperties properties) {
		String secret = properties.auth().jwtSecret();
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("bepro.auth.jwt-secret (BEPRO_JWT_SECRET) must be at least 32 bytes");
		}
		if (properties.auth().deviceSecret() == null || properties.auth().deviceSecret().isBlank()) {
			throw new IllegalStateException("bepro.auth.device-secret (BEPRO_DEVICE_SECRET) must be set");
		}
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey) {
		return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// Internal endpoints check their own shared-secret header.
				.requestMatchers("/v1/auth/token", "/v1/health", "/v1/internal/**", "/actuator/health/**", "/error")
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
		return http.build();
	}

}
