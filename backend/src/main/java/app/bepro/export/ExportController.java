package app.bepro.export;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletResponse;

import app.bepro.events.EventRepository;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExportController {

	private final EventRepository events;

	private final Clock clock;

	public ExportController(EventRepository events, Clock clock) {
		this.events = events;
		this.clock = clock;
	}

	/** Raw events as CSV, streamed. Defaults to the last 90 days. */
	@GetMapping("/v1/export/events.csv")
	public void exportCsv(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to, HttpServletResponse response) throws IOException {
		Instant end = to == null ? clock.instant() : to;
		Instant start = from == null ? end.minus(Duration.ofDays(90)) : from;
		response.setContentType("text/csv;charset=UTF-8");
		response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bepro-events.csv\"");
		PrintWriter out = response.getWriter();
		out.println("id,category,sub_category,value,unit,started_at,ended_at,source,metadata");
		events.stream(jwt.getSubject(), start, end, e -> {
			out.print(e.id());
			out.print(',');
			out.print(csv(e.category()));
			out.print(',');
			out.print(csv(e.subCategory()));
			out.print(',');
			out.print(e.value().stripTrailingZeros().toPlainString());
			out.print(',');
			out.print(csv(e.unit()));
			out.print(',');
			out.print(e.startedAt().toInstant());
			out.print(',');
			out.print(e.endedAt() == null ? "" : e.endedAt().toInstant().toString());
			out.print(',');
			out.print(csv(e.source()));
			out.print(',');
			out.println(csv(e.metadata()));
			if (out.checkError()) {
				throw new UncheckedIOException(new IOException("Client disconnected"));
			}
		});
		out.flush();
	}

	static String csv(String value) {
		if (value == null) {
			return "";
		}
		if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
			return '"' + value.replace("\"", "\"\"") + '"';
		}
		return value;
	}

}
