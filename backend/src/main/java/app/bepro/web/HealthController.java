package app.bepro.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

	/** Liveness probe and the app's "can I reach the server?" check during setup. */
	@GetMapping("/v1/health")
	public Map<String, String> health() {
		return Map.of("status", "ok");
	}

}
