package app.bepro.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

import app.bepro.aggregates.AggregationService;
import app.bepro.alerts.AnomalyDetector;
import app.bepro.config.BeproProperties;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EventIngestService {

	private static final Duration MAX_AGE = Duration.ofDays(400);

	private static final Duration MAX_SPAN = Duration.ofDays(2);

	private final EventRepository events;

	private final AggregationService aggregation;

	private final AnomalyDetector anomalies;

	private final BeproProperties properties;

	private final Clock clock;

	public EventIngestService(EventRepository events, AggregationService aggregation, AnomalyDetector anomalies,
			BeproProperties properties, Clock clock) {
		this.events = events;
		this.aggregation = aggregation;
		this.anomalies = anomalies;
		this.properties = properties;
		this.clock = clock;
	}

	public record IngestResult(int accepted, SortedSet<LocalDate> affectedDates) {
	}

	@Transactional
	public IngestResult ingest(String userId, List<IncomingEvent> batch) {
		Instant now = clock.instant();
		for (IncomingEvent e : batch) {
			if (e.endedAt() != null && e.endedAt().isBefore(e.startedAt())) {
				throw badRequest("endedAt is before startedAt for " + e.category() + "/" + e.subCategory());
			}
			if (e.startedAt().isAfter(now.plus(Duration.ofHours(1))) || e.startedAt().isBefore(now.minus(MAX_AGE))) {
				throw badRequest("startedAt out of range: " + e.startedAt());
			}
			if (Duration.between(e.startedAt(), e.endOrStart()).compareTo(MAX_SPAN) > 0) {
				throw badRequest("event spans more than 2 days: " + e.category() + "/" + e.subCategory());
			}
		}
		if (batch.isEmpty()) {
			return new IngestResult(0, new TreeSet<>());
		}
		events.upsert(userId, batch);

		SortedSet<LocalDate> dates = affectedDates(batch, properties.zone(), LocalDate.now(clock.withZone(properties.zone())));
		aggregation.recompute(userId, dates);
		dates.forEach(date -> anomalies.check(userId, date));
		return new IngestResult(batch.size(), dates);
	}

	static SortedSet<LocalDate> affectedDates(List<IncomingEvent> batch, ZoneId zone, LocalDate today) {
		SortedSet<LocalDate> dates = new TreeSet<>();
		for (IncomingEvent e : batch) {
			LocalDate first = e.startedAt().atZone(zone).toLocalDate();
			LocalDate last = e.endOrStart().atZone(zone).toLocalDate();
			// Screen activity in the evening decides the next morning's inferred sleep,
			// and manual sleep entries are filed under the wake-up date.
			if (Categories.SCREEN.equals(e.category()) || Categories.SLEEP.equals(e.category())) {
				last = last.plusDays(1);
			}
			for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
				if (!d.isAfter(today)) {
					dates.add(d);
				}
			}
		}
		if (dates.isEmpty()) {
			dates.add(today);
		}
		return dates;
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

}
