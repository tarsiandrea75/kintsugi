package dev.kintsugi.agent.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.kintsugi.agent.AgentDatabase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code POST /incidents} and {@code GET /incidents/{id}} over HTTP (agent spec, "Open an incident", "Read an incident"). */
/**
 * The test server listens on a random port, so the allow-list accepts {@code localhost} on any port; {@code 127.0.0.1}
 * stays outside it, which the Host test uses.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "kintsugi.agent.route=local", "kintsugi.agent.allowed-hosts=localhost:*" })
class IncidentApiTest {

	private static final String VALID = "{\"symptom\": \"Checkout orders stay pending for a long time\", \"sinceMinutes\": 15}";

	private static final JsonMapper JSON = JsonMapper.shared();

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	/** A mock, so an opened incident stays RUNNING: these tests are about the API, not the loop. */
	@MockitoBean
	IncidentRunner runner;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@BeforeEach
	void empty() {
		AgentDatabase.empty();
	}

	@Test
	void aValidIncidentIsAcceptedStoredRunningAndHandedToTheRunner() throws Exception {
		HttpResponse<String> response = post(VALID);

		assertThat(response.statusCode()).isEqualTo(202);
		JsonNode body = JSON.readTree(response.body());
		UUID id = UUID.fromString(body.get("id").asString());
		assertThat(body.get("status").asString()).isEqualTo("RUNNING");
		assertThat(response.headers().firstValue("Location")).hasValue("/incidents/" + id);
		var row = AgentDatabase.admin()
			.sql("SELECT status, signal->>'symptom' AS symptom, (signal->>'sinceMinutes')::int AS since,"
					+ " started_at IS NOT NULL AS started, ended_at IS NULL AS open FROM incidents WHERE id = ?")
			.param(id)
			.query()
			.singleRow();
		assertThat(row).containsEntry("status", "RUNNING")
			.containsEntry("symptom", "Checkout orders stay pending for a long time")
			.containsEntry("since", 15)
			.containsEntry("started", true)
			.containsEntry("open", true);
		verify(runner).start(id);
	}

	@Test
	void theSymptomIsStoredTrimmed() throws Exception {
		HttpResponse<String> response = post("{\"symptom\": \"   Orders are slow today   \", \"sinceMinutes\": 5}");

		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(AgentDatabase.admin().sql("SELECT signal->>'symptom' FROM incidents").query(String.class).single())
			.isEqualTo("Orders are slow today");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"symptom\": \"123456789\", \"sinceMinutes\": 15}", // 9 characters
			"{\"symptom\": \"   12345678   \", \"sinceMinutes\": 15}", // 8 after trimming
			"{\"symptom\": \"" + "x501" + "\", \"sinceMinutes\": 15}", //
			"{\"symptom\": \"Orders are\\u0007 slow today\", \"sinceMinutes\": 15}", // a control character
			"{\"symptom\": \"Orders are\\nslow today\", \"sinceMinutes\": 15}", //
			"{\"symptom\": 1234567890, \"sinceMinutes\": 15}", //
			"{\"symptom\": null, \"sinceMinutes\": 15}", //
			"{\"sinceMinutes\": 15}" })
	void anInvalidSymptomIsRejectedWithItsRange(String body) throws Exception {
		HttpResponse<String> response = post(body.replace("x501", "x".repeat(501)));

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.get("field").asString()).isEqualTo("symptom");
		assertThat(error.get("error").asString()).contains("10 to 500");
		assertNoIncident();
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "361", "-5", "15.5", "15.0", "\"15\"", "null", "99999999999", "MISSING" })
	void anInvalidWindowIsRejectedWithItsRange(String sinceMinutes) throws Exception {
		String body = sinceMinutes.equals("MISSING") ? "{\"symptom\": \"Checkout orders stay pending\"}"
				: "{\"symptom\": \"Checkout orders stay pending\", \"sinceMinutes\": " + sinceMinutes + "}";

		HttpResponse<String> response = post(body);

		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.get("field").asString()).isEqualTo("sinceMinutes");
		assertThat(error.get("error").asString()).contains("1 to 360");
		assertNoIncident();
	}

	@ParameterizedTest
	@ValueSource(strings = { "not json", "[]", "\"text\"", "" })
	void aBodyThatIsNotAnObjectIsRejected(String body) throws Exception {
		HttpResponse<String> response = post(body);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(JSON.readTree(response.body()).get("error").asString()).contains("symptom").contains("sinceMinutes");
		assertNoIncident();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"symptom\": \"1234567890\", \"sinceMinutes\": 1}",
			"{\"symptom\": \"x500\", \"sinceMinutes\": 360}" })
	void theBoundsAreAccepted(String body) throws Exception {
		assertThat(post(body.replace("x500", "x".repeat(500))).statusCode()).isEqualTo(202);
	}

	@Test
	void aSecondIncidentWhileOneIsRunningIsAConflict() throws Exception {
		UUID running = UUID.fromString(JSON.readTree(post(VALID).body()).get("id").asString());

		HttpResponse<String> response = post(VALID);

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(JSON.readTree(response.body()).get("id").asString()).isEqualTo(running.toString());
		assertThat(AgentDatabase.admin().sql("SELECT count(*) FROM incidents").query(Integer.class).single())
			.isEqualTo(1);
	}

	@Test
	void anInvalidBodyIsABadRequestEvenWhileOneIsRunning() throws Exception {
		post(VALID);

		assertThat(post("{\"symptom\": \"short\", \"sinceMinutes\": 15}").statusCode()).isEqualTo(400);
	}

	@Test
	void anEndedIncidentDoesNotBlockANewOne() throws Exception {
		post(VALID);
		AgentDatabase.admin()
			.sql("UPDATE incidents SET status = 'FAILED', failure_reason = 'x', ended_at = now()")
			.update();

		assertThat(post(VALID).statusCode()).isEqualTo(202);
	}

	@Test
	void aRunnerThatFailsToStartFailsTheIncidentAndFreesTheSlot() throws Exception {
		doThrow(new IllegalStateException("no executor")).when(runner).start(any());

		HttpResponse<String> response = post(VALID);

		assertThat(response.statusCode()).isEqualTo(500);
		JsonNode error = JSON.readTree(response.body());
		assertThat(error.get("error").asString()).isEqualTo(IncidentController.RUNNER_FAILED);
		UUID id = UUID.fromString(error.get("id").asString());
		var row = AgentDatabase.admin()
			.sql("SELECT status, failure_reason, ended_at IS NOT NULL AS ended FROM incidents WHERE id = ?")
			.param(id)
			.query()
			.singleRow();
		assertThat(row).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", IncidentController.RUNNER_FAILED)
			.containsEntry("ended", true);

		reset(runner);
		assertThat(post(VALID).statusCode()).isEqualTo(202);
	}

	@Test
	void ofTwoParallelRequestsExactlyOneWins() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 10; round++) {
				AgentDatabase.empty();
				CyclicBarrier start = new CyclicBarrier(2);
				List<CompletableFuture<Integer>> calls = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					calls.add(CompletableFuture.supplyAsync(() -> {
						try {
							// A timeout, so a request that never reaches the barrier fails the test instead of hanging.
							start.await(10, TimeUnit.SECONDS);
							return post(VALID).statusCode();
						}
						catch (Exception e) {
							throw new IllegalStateException(e);
						}
					}, pool));
				}
				List<Integer> statuses = calls.stream()
					.map(call -> call.orTimeout(30, TimeUnit.SECONDS).join())
					.sorted()
					.toList();

				assertThat(statuses).as("round " + round).containsExactly(202, 409);
				assertThat(AgentDatabase.admin()
					.sql("SELECT count(*) FROM incidents WHERE status = 'RUNNING'")
					.query(Integer.class)
					.single()).as("round " + round).isEqualTo(1);
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "6f1c1d9e-55b0-4a52-9d47-3b8a1c2d4e5f", "abc", "1" })
	void anUnknownOrMalformedIdIsNotFound(String id) throws Exception {
		HttpResponse<String> response = get("/incidents/" + id);

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(JSON.readTree(response.body()).get("error").asString()).isEqualTo("incident not found");
	}

	@Test
	void aRunningIncidentIsReadWithoutEndFields() throws Exception {
		UUID id = UUID.fromString(JSON.readTree(post(VALID).body()).get("id").asString());

		HttpResponse<String> response = get("/incidents/" + id);

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode incident = JSON.readTree(response.body());
		assertThat(incident.get("id").asString()).isEqualTo(id.toString());
		assertThat(incident.get("symptom").asString()).isEqualTo("Checkout orders stay pending for a long time");
		assertThat(incident.get("sinceMinutes").asInt()).isEqualTo(15);
		assertThat(incident.get("status").asString()).isEqualTo("RUNNING");
		assertThat(incident.get("startedAt").asString()).isNotBlank();
		assertThat(incident.has("endedAt")).isFalse();
		assertThat(incident.has("failureReason")).isFalse();
		assertThat(incident.has("report")).isFalse();
		assertThat(incident.get("steps").isArray()).isTrue();
		assertThat(incident.get("steps").size()).isZero();
	}

	@Test
	void stepsCarrySummariesByDefaultAndFullOutputsOnRequest() throws Exception {
		UUID id = completedIncidentWithSteps();

		JsonNode brief = JSON.readTree(get("/incidents/" + id).body());
		JsonNode full = JSON.readTree(get("/incidents/" + id + "?full=true").body());

		assertThat(brief.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(brief.get("report").get("rootCauseService").asString()).isEqualTo("payment-service");
		assertThat(brief.get("endedAt").asString()).isNotBlank();
		JsonNode briefSteps = brief.get("steps");
		assertThat(briefSteps.size()).isEqualTo(2);
		JsonNode toolStep = briefSteps.get(1);
		assertThat(briefSteps.get(0).get("stepNo").asInt()).isEqualTo(1);
		assertThat(briefSteps.get(0).get("kind").asString()).isEqualTo("PLAN");
		assertThat(toolStep.get("stepNo").asInt()).isEqualTo(2);
		assertThat(toolStep.get("kind").asString()).isEqualTo("TOOL_CALL");
		assertThat(toolStep.get("phase").asString()).isEqualTo("TRIAGE");
		assertThat(toolStep.get("model").asString()).isEqualTo("granite4.1:8b");
		assertThat(toolStep.get("tokens").asInt()).isEqualTo(321);
		assertThat(toolStep.get("toolName").asString()).isEqualTo("count_logs");
		assertThat(toolStep.get("toolArgs").get("service").asString()).isEqualTo("payment-service");
		assertThat(toolStep.get("toolOutcome").asString()).isEqualTo("OK");
		assertThat(toolStep.get("summary").asString()).isEqualTo("toolCallId=2 tool=count_logs outcome=OK");
		assertThat(toolStep.get("latencyMs").asInt()).isEqualTo(42);
		assertThat(toolStep.get("createdAt").asString()).isNotBlank();
		assertThat(toolStep.has("toolOutput")).isFalse();
		assertThat(briefSteps.get(0).has("modelOutput")).isFalse();

		assertThat(full.get("steps").get(1).get("toolOutput").asString()).isEqualTo("{\"count\":7}");
		assertThat(full.get("steps").get(0).get("modelOutput").asString()).isEqualTo("{\"hypotheses\":[]}");
		assertThat(full.get("steps").get(1).get("summary").asString()).isEqualTo(toolStep.get("summary").asString());
	}

	@Test
	void aFailedIncidentShowsItsReason() throws Exception {
		UUID id = UUID.randomUUID();
		AgentDatabase.admin()
			.sql("INSERT INTO incidents (id, signal, status, failure_reason, started_at, ended_at) VALUES (?,"
					+ " '{\"symptom\":\"Checkout orders stay pending\",\"sinceMinutes\":15}', 'FAILED',"
					+ " 'plan invalid twice', now(), now())")
			.param(id)
			.update();

		JsonNode incident = JSON.readTree(get("/incidents/" + id).body());

		assertThat(incident.get("status").asString()).isEqualTo("FAILED");
		assertThat(incident.get("failureReason").asString()).isEqualTo("plan invalid twice");
		assertThat(incident.has("report")).isFalse();
	}

	/** A browser sends an Origin: the request is refused before the controller (agent spec, "Browser requests"). */
	@Test
	void aRequestWithAnOriginIsRejectedWith403AndOpensNoIncident() throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/incidents"))
			.header("Content-Type", "text/plain")
			.header("Origin", "https://evil.example")
			.POST(HttpRequest.BodyPublishers.ofString(VALID))
			.build(), HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(403);
		assertNoIncident();
		verify(runner, never()).start(any());
	}

	/** DNS rebinding sends the attacker's hostname as Host; here 127.0.0.1 plays it, being outside the list. */
	@Test
	void aHostOutsideTheAllowListIsRejectedWith421() throws Exception {
		String outside = "http://127.0.0.1:" + port;
		HttpResponse<String> post = http.send(HttpRequest.newBuilder(URI.create(outside + "/incidents"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(VALID))
			.build(), HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> read = http.send(
				HttpRequest.newBuilder(URI.create(outside + "/incidents/" + UUID.randomUUID())).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(post.statusCode()).isEqualTo(421);
		assertThat(read.statusCode()).isEqualTo(421);
		assertNoIncident();
	}

	private static UUID completedIncidentWithSteps() {
		UUID id = UUID.randomUUID();
		var admin = AgentDatabase.admin();
		admin.sql("INSERT INTO incidents (id, signal, status, report, started_at, ended_at) VALUES (?,"
				+ " '{\"symptom\":\"Checkout orders stay pending\",\"sinceMinutes\":15}', 'COMPLETED',"
				+ " '{\"rootCauseService\":\"payment-service\",\"rootCauseCategory\":\"DEPENDENCY_LATENCY\"}', now(),"
				+ " now())")
			.param(id)
			.update();
		// Inserted out of order, to prove the steps come back in step order.
		admin.sql("INSERT INTO incident_steps (incident_id, step_no, kind, phase, model, tokens, tool_name, tool_args,"
				+ " tool_output, tool_outcome, summary, latency_ms, created_at) VALUES (?, 2, 'TOOL_CALL', 'TRIAGE',"
				+ " 'granite4.1:8b', 321, 'count_logs', '{\"service\":\"payment-service\"}', '{\"count\":7}', 'OK',"
				+ " 'toolCallId=2 tool=count_logs outcome=OK', 42, now())")
			.param(id)
			.update();
		admin.sql("INSERT INTO incident_steps (incident_id, step_no, kind, phase, model, tokens, model_output,"
				+ " latency_ms, created_at) VALUES (?, 1, 'PLAN', 'TRIAGE', 'granite4.1:8b', 100,"
				+ " '{\"hypotheses\":[]}', 900, now())")
			.param(id)
			.update();
		return id;
	}

	private static void assertNoIncident() {
		assertThat(AgentDatabase.admin().sql("SELECT count(*) FROM incidents").query(Integer.class).single()).isZero();
	}

	private HttpResponse<String> post(String body) throws Exception {
		return http.send(HttpRequest.newBuilder(uri("/incidents"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

}
