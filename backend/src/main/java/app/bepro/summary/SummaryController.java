package app.bepro.summary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import app.bepro.aggregates.AggregateRepository;
import app.bepro.aggregates.DailyAggregate;
import app.bepro.config.BeproProperties;
import app.bepro.events.EventRepository;
import app.bepro.summary.TimelineService.Timeline;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1")
public class SummaryController {

	private static final int MAX_RANGE_DAYS = 92;

	private final AggregateRepository aggregates;

	private final WeekSummaryService weekSummaries;

	private final TimelineService timelines;

	private final EventRepository events;

	private final BeproProperties properties;

	private final Clock clock;

	public SummaryController(AggregateRepository aggregates, WeekSummaryService weekSummaries,
			TimelineService timelines, EventRepository events, BeproProperties properties, Clock clock) {
		this.aggregates = aggregates;
		this.weekSummaries = weekSummaries;
		this.timelines = timelines;
		this.events = events;
		this.properties = properties;
		this.clock = clock;
	}

	public record TodaySummary(DailyAggregate today, DailyAggregate yesterday, OffsetDateTime lastSyncAt,
			String timezone) {
	}

	@GetMapping("/summary/today")
	public TodaySummary today(@AuthenticationPrincipal Jwt jwt) {
		String userId = jwt.getSubject();
		LocalDate today = today();
		return new TodaySummary(aggregates.find(userId, today).orElse(DailyAggregate.empty(today)),
				aggregates.find(userId, today.minusDays(1)).orElse(DailyAggregate.empty(today.minusDays(1))),
				events.lastSyncedAt(userId), properties.zone().getId());
	}

	@GetMapping("/summary/week")
	public WeekSummary week(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
		return weekSummaries.build(jwt.getSubject(), end == null ? today() : end);
	}

	/** One row per day in [from, to], with empty rows for days without data so charts have no holes. */
	@GetMapping("/summary/daily")
	public List<DailyAggregate> daily(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		LocalDate end = to == null ? today() : to;
		LocalDate start = from == null ? end.minusDays(29) : from;
		if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Range must be 1-" + MAX_RANGE_DAYS + " days");
		}
		Map<LocalDate, DailyAggregate> byDate = aggregates.range(jwt.getSubject(), start, end)
			.stream()
			.collect(Collectors.toMap(DailyAggregate::date, Function.identity()));
		List<DailyAggregate> days = new ArrayList<>();
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			days.add(byDate.getOrDefault(d, DailyAggregate.empty(d)));
		}
		return days;
	}

	@GetMapping("/timeline")
	public Timeline timeline(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return timelines.build(jwt.getSubject(), date == null ? today() : date);
	}

	private LocalDate today() {
		return LocalDate.now(clock.withZone(properties.zone()));
	}

}
