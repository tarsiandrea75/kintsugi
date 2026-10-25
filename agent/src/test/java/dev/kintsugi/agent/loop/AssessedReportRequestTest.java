package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.dossier.CallGraph;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.lab.LabTools;

/**
 * The assessed report's schema as the local route sends it to Ollama (agent spec, "The report", the assessed report;
 * odd/tasks/service-assessment.md T2). The agent runs as the web profile wires it, its settings read from their
 * properties, with the auto-configured Ollama client, against a stub of Ollama's {@code /api/chat} that keeps the body
 * of every request but the warm-up. Ollama constrains the answer by a grammar built from the request's {@code format},
 * which writes the properties in the order they come: so the order on the wire is the order the model writes, the
 * assessments first and the services leaf first. Spring AI turns the schema into a map and serializes it again, so the
 * test reads it where Ollama does, in the HTTP body. The local answers of the M0 replay (2026-10-25) followed the plain
 * schema's order, which is not the alphabetical one, every time.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "kintsugi.agent.route=local", "kintsugi.agent.mode=decision",
				"kintsugi.agent.decision.view=sheets", "kintsugi.agent.decision.report=assessed" })
class AssessedReportRequestTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final List<String> FIELDS = List.of("assessments", "summary", "rootCauseService",
			"rootCauseCategory", "confidence");

	private static final RecordingOllama OLLAMA = RecordingOllama.start();

	@TestBean
	LabTools labTools;

	@Autowired
	AgentLoop loop;

	static LabTools labTools() {
		return new FakeLabTools()
			.answer("traffic_counts", AgentLoopDossierModeTest.dossierFixture("traffic_counts-incident"))
			.answer("event_counts", AgentLoopDossierModeTest.dossierFixture("event_counts-order-incident"));
	}

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
		registry.add("spring.ai.ollama.base-url", OLLAMA::url);
	}

	@BeforeEach
	void empty() {
		AgentDatabase.empty();
	}

	@AfterAll
	static void stop() {
		OLLAMA.close();
	}

	@Test
	void theReportRequestsCarryTheAssessmentsFirstAndTheServicesLeafFirst() {
		UUID id = UUID.randomUUID();
		AgentDatabase.admin()
			.sql("INSERT INTO incidents (id, signal, status, started_at) VALUES (?, CAST(? AS jsonb), 'RUNNING', now())")
			.params(id, "{\"symptom\":\"Checkout orders stay pending for a long time\",\"sinceMinutes\":15}")
			.update();

		// The stub answers no report, so the run ends after its retry: the test reads the requests, not the run.
		assertThat(this.loop.run(id)).isEqualTo(IncidentStatus.INVALID_REPORT);

		assertThat(OLLAMA.reportRequests()).hasSize(2).allSatisfy(body -> {
			JsonNode format = JSON.readTree(body).get("format");
			assertThat(format.get("properties").propertyNames()).containsExactlyElementsOf(FIELDS);
			assertThat(format.get("required")).extracting(JsonNode::asString).containsExactlyElementsOf(FIELDS);
			JsonNode assessments = format.at("/properties/assessments");
			assertThat(assessments.get("properties").propertyNames()).containsExactlyElementsOf(CallGraph.SERVICES);
			assertThat(assessments.get("required")).extracting(JsonNode::asString)
				.containsExactlyElementsOf(CallGraph.SERVICES);
			for (String service : CallGraph.SERVICES) {
				assertThat(assessments.at("/properties/" + service + "/properties").propertyNames()).as(service)
					.containsExactly("role", "line", "why");
			}
			// The schema the loop built, every property in its place, once read back from the wire.
			assertThat(JSON.writeValueAsString(format)).isEqualTo(Schemas.ASSESSED_REPORT);
		});
	}

	/**
	 * A stub of Ollama's {@code /api/chat}: it answers the warm-up {@code OK}, and every other request a text that is
	 * no report, keeping its body.
	 */
	private static final class RecordingOllama implements AutoCloseable {

		private static final String ANSWER = "{\"model\":\"granite4.1:8b\",\"created_at\":\"2026-10-25T00:00:00Z\","
				+ "\"message\":{\"role\":\"assistant\",\"content\":\"%s\"},\"done\":true,\"done_reason\":\"stop\","
				+ "\"total_duration\":1,\"load_duration\":1,\"prompt_eval_count\":1,\"prompt_eval_duration\":1,"
				+ "\"eval_count\":1,\"eval_duration\":1}";

		private final HttpServer server;

		private final List<String> reports = new CopyOnWriteArrayList<>();

		private RecordingOllama(HttpServer server) {
			this.server = server;
			server.createContext("/api/chat", this::answer);
			server.start();
		}

		static RecordingOllama start() {
			try {
				return new RecordingOllama(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
			}
			catch (IOException failed) {
				throw new UncheckedIOException(failed);
			}
		}

		String url() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		/** The bodies of the requests that were not the warm-up, in order. */
		List<String> reportRequests() {
			return this.reports;
		}

		private void answer(HttpExchange exchange) throws IOException {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			boolean warmUp = body.contains(Prompts.WARM_UP);
			if (!warmUp) {
				this.reports.add(body);
			}
			byte[] answer = ANSWER.formatted(warmUp ? "OK" : "no report").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, answer.length);
			exchange.getResponseBody().write(answer);
			exchange.close();
		}

		@Override
		public void close() {
			this.server.stop(0);
		}

	}

}
