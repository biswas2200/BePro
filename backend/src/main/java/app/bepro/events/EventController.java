package app.bepro.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import app.bepro.events.EventIngestService.IngestResult;
import app.bepro.events.EventRepository.StoredEvent;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/events")
public class EventController {

	private final EventIngestService ingest;

	private final EventRepository events;

	private final Clock clock;

	public EventController(EventIngestService ingest, EventRepository events, Clock clock) {
		this.ingest = ingest;
		this.events = events;
		this.clock = clock;
	}

	public record EventBatchRequest(@NotNull @Size(max = 1000) List<@Valid IncomingEvent> events) {
	}

	@PostMapping("/batch")
	public IngestResult batch(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody EventBatchRequest request) {
		return ingest.ingest(jwt.getSubject(), request.events());
	}

	public record EventPage(List<StoredEvent> items, int page, int size) {
	}

	@GetMapping
	public EventPage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) String category,
			@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int size) {
		Instant end = to == null ? clock.instant() : to;
		Instant start = from == null ? end.minus(Duration.ofDays(7)) : from;
		return new EventPage(events.find(jwt.getSubject(), category, start, end, size, page * size), page, size);
	}

}
