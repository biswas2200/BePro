package app.bepro.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import app.bepro.TestcontainersConfiguration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = { "bepro.auth.device-secret=test-device-secret",
		"bepro.auth.jwt-secret=test-jwt-secret-that-is-at-least-32-bytes-long",
		"bepro.digest.internal-token=internal-test-token", "bepro.ai.provider=rules", "bepro.zone=Asia/Kolkata" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiIntegrationTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	@Autowired
	JdbcClient jdbc;

	private String token;

	private LocalDate yesterday;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("TRUNCATE events, daily_aggregates, insights, devices").update();
		token = login("test-device-secret");
		yesterday = LocalDate.now(ZONE).minusDays(1);
	}

	@Test
	void rejectsWrongSecretAndMissingToken() throws Exception {
		mvc.perform(post("/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
			.content("{\"deviceId\":\"redmi\",\"secret\":\"nope\"}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/v1/summary/today")).andExpect(status().isUnauthorized());
		mvc.perform(post("/v1/internal/digest/run")).andExpect(status().isUnauthorized());
		mvc.perform(get("/v1/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
	}

	@Test
	void rejectsInvalidEvents() throws Exception {
		Map<String, Object> bad = event("usage", "com.x", 1, "minutes", at(yesterday, "10:00"), at(yesterday, "09:00"),
				"usage_stats", Map.of());
		mvc.perform(authed(post("/v1/events/batch")).content(batch(List.of(bad)))).andExpect(status().isBadRequest());

		Map<String, Object> unknownCategory = event("weather", "", 1, "x", at(yesterday, "10:00"), null, "manual", Map.of());
		mvc.perform(authed(post("/v1/events/batch")).content(batch(List.of(unknownCategory))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void fullWeekFlow() throws Exception {
		List<Map<String, Object>> events = week(yesterday);
		mvc.perform(authed(post("/v1/events/batch")).content(batch(events)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accepted").value(events.size()));
		// Re-uploading the same window must not create duplicates.
		mvc.perform(authed(post("/v1/events/batch")).content(batch(events))).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT count(*) FROM events").query(Integer.class).single()).isEqualTo(events.size());

		LocalDate from = yesterday.minusDays(6);
		mvc.perform(authed(get("/v1/summary/daily")).param("from", from.toString()).param("to", yesterday.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(7)))
			.andExpect(jsonPath("$[0].sleepHours").value(7.5))
			.andExpect(jsonPath("$[0].sleepSource").value("inferred"))
			.andExpect(jsonPath("$[0].topAppLabel").value("Instagram"))
			.andExpect(jsonPath("$[0].topAppMinutes").value(90))
			.andExpect(jsonPath("$[0].usageMinutes").value(130))
			.andExpect(jsonPath("$[0].steps").value(3000))
			.andExpect(jsonPath("$[0].unlocks").value(3))
			.andExpect(jsonPath("$[0].focusSessions").value(1))
			.andExpect(jsonPath("$[0].moodScore").value(3.0))
			// The last three nights were short.
			.andExpect(jsonPath("$[6].sleepHours").value(4.0));

		mvc.perform(authed(get("/v1/summary/week")).param("end", yesterday.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.completeDays").value(7))
			.andExpect(jsonPath("$.topApps[0].label").value("Instagram"))
			.andExpect(jsonPath("$.topApps[0].dailyAvgMinutes").value(90.0))
			.andExpect(jsonPath("$.sleep.nightsUnder6h").value(3))
			.andExpect(jsonPath("$.steps.daysUnder5000").value(7))
			.andExpect(jsonPath("$.focus.completionRate").value(1.0))
			.andExpect(jsonPath("$.mood.notesKeywords[0]").exists());

		mvc.perform(authed(get("/v1/timeline")).param("date", yesterday.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hours", hasSize(24)))
			.andExpect(jsonPath("$.hours[10].topApp").value("Instagram"))
			.andExpect(jsonPath("$.hours[10].topAppMinutes").value(60))
			.andExpect(jsonPath("$.hours[12].steps").value(1500));

		// Three short nights in a row raise an alert straight away.
		String latest = mvc.perform(authed(get("/v1/insights/latest")))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(JsonPath.<List<String>>read(latest, "$[*].kind")).contains("alert");

		mvc.perform(authed(post("/v1/digest/run")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("generated"))
			.andExpect(jsonPath("$.insights[0].pattern").exists());
		mvc.perform(authed(post("/v1/digest/run"))).andExpect(jsonPath("$.status").value("skipped"));
		mvc.perform(post("/v1/internal/digest/run").header("X-Bepro-Internal-Token", "internal-test-token"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("skipped"));

		latest = mvc.perform(authed(get("/v1/insights/latest"))).andReturn().getResponse().getContentAsString();
		List<String> weeklyIds = JsonPath.read(latest, "$[?(@.kind == 'weekly')].id");
		assertThat(weeklyIds).isNotEmpty().hasSizeLessThanOrEqualTo(2);
		mvc.perform(authed(patch("/v1/insights/" + weeklyIds.getFirst())).content("{\"status\":\"dismissed\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("dismissed"));

		mvc.perform(authed(get("/v1/export/events.csv")))
			.andExpect(status().isOk())
			.andExpect(content().string(startsWith("id,category,sub_category,value,unit,started_at")));

		mvc.perform(authed(get("/v1/events")).param("category", "mood")
			.param("from", yesterday.minusDays(7).atStartOfDay(ZONE).toInstant().toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(7)));
	}

	@Test
	void digestNeedsEnoughData() throws Exception {
		List<Map<String, Object>> oneDay = day(yesterday, "23:30", "07:00");
		mvc.perform(authed(post("/v1/events/batch")).content(batch(oneDay))).andExpect(status().isOk());
		mvc.perform(authed(post("/v1/digest/run")))
			.andExpect(jsonPath("$.status").value("skipped"))
			.andExpect(jsonPath("$.reason", startsWith("Only 1 complete days")));
		mvc.perform(authed(get("/v1/insights/latest")))
			.andExpect(jsonPath("$[0].kind").value("notice"));
	}

	/** Seven days; the first four nights are 7.5 h and the last three are 4 h. */
	private List<Map<String, Object>> week(LocalDate end) {
		List<Map<String, Object>> events = new ArrayList<>();
		for (int i = 6; i >= 0; i--) {
			boolean shortNight = i < 3;
			events.addAll(day(end.minusDays(i), shortNight ? "01:30" : "23:30", shortNight ? "05:30" : "07:00"));
		}
		return events;
	}

	private List<Map<String, Object>> day(LocalDate date, String bedtime, String wake) {
		LocalDate prev = date.minusDays(1);
		LocalTime bed = LocalTime.parse(bedtime);
		ZonedDateTime offAt = bed.isAfter(LocalTime.NOON) ? at(prev, bedtime) : at(date, bedtime);
		List<Map<String, Object>> e = new ArrayList<>();
		// Evening use ending at bedtime, then the first use at wake-up.
		e.add(event("screen", "session", 30, "minutes", offAt.minusMinutes(30), offAt, "usage_stats", Map.of()));
		e.add(event("screen", "session", 15, "minutes", at(date, wake), at(date, wake).plusMinutes(15), "usage_stats", Map.of()));
		e.add(event("screen", "session", 10, "minutes", at(date, "08:30"), at(date, "08:40"), "usage_stats", Map.of()));
		e.add(event("screen", "session", 90, "minutes", at(date, "10:00"), at(date, "11:30"), "usage_stats", Map.of()));
		e.add(event("screen", "session", 40, "minutes", at(date, "14:00"), at(date, "14:40"), "usage_stats", Map.of()));
		for (String t : List.of("10:00", "14:00", "18:00")) {
			e.add(event("screen", "unlock", 1, "count", at(date, t), null, "usage_stats", Map.of()));
		}
		e.add(event("usage", "com.instagram.android", 90, "minutes", at(date, "10:00"), at(date, "11:30"), "usage_stats",
				Map.of("label", "Instagram")));
		e.add(event("usage", "com.android.chrome", 40, "minutes", at(date, "14:00"), at(date, "14:40"), "usage_stats",
				Map.of("label", "Chrome")));
		e.add(event("steps", "", 1500, "steps", at(date, "12:00"), at(date, "12:30"), "sensor", Map.of()));
		e.add(event("steps", "", 1500, "steps", at(date, "17:00"), at(date, "17:30"), "sensor", Map.of()));
		e.add(event("mood", "", 3, "score", at(date, "21:00"), null, "manual", Map.of("note", "tired after late scrolling")));
		e.add(event("focus", "timer", 25, "minutes", at(date, "15:00"), at(date, "15:25"), "manual",
				Map.of("plannedMinutes", 25)));
		return e;
	}

	private static Map<String, Object> event(String category, String sub, double value, String unit,
			ZonedDateTime start, ZonedDateTime end, String source, Map<String, Object> metadata) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("category", category);
		m.put("subCategory", sub);
		m.put("value", value);
		m.put("unit", unit);
		m.put("startedAt", start.toInstant().toString());
		if (end != null) {
			m.put("endedAt", end.toInstant().toString());
		}
		m.put("source", source);
		m.put("metadata", metadata);
		return m;
	}

	private static ZonedDateTime at(LocalDate date, String time) {
		return date.atTime(LocalTime.parse(time)).atZone(ZONE);
	}

	private String batch(List<Map<String, Object>> events) {
		return json.writeValueAsString(Map.of("events", events));
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request) {
		return request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
	}

	private String login(String secret) throws Exception {
		String body = mvc.perform(post("/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
			.content("{\"deviceId\":\"redmi-y2\",\"secret\":\"" + secret + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

}
