package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.ScriptedChatModel.call;
import static dev.kintsugi.agent.loop.ScriptedChatModel.evidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.report;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.incident.IncidentRunner;
import dev.kintsugi.agent.lab.LabTools;

/**
 * The web profile runs the loop behind the incident API: {@code POST /incidents}, then {@code GET} until the run
 * ends, with a scripted model and a fake lab (agent spec, "Open an incident", "Read an incident").
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AgentLoopApiTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final ScriptedChatModel MODEL = new ScriptedChatModel();

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@TestBean
	ChatModel chatModel;

	@TestBean
	LabTools labTools;

	@Autowired
	IncidentRunner runner;

	static ChatModel chatModel() {
		return MODEL;
	}

	static LabTools labTools() {
		return new FakeLabTools();
	}

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@BeforeEach
	void empty() {
		AgentDatabase.empty();
	}

	@Test
	void anIncidentPostedToTheApiRunsToCompletion() throws Exception {
		MODEL.plan()
			.tools(call("status_summary", "{\"table\":\"orders\"}"))
			.text("done")
			.quietPhasesAfterTriage()
			.text(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "status_summary", "PAYMENT_PENDING")));
		assertThat(this.runner).isInstanceOf(AgentLoop.class);

		HttpResponse<String> opened = send(HttpRequest.newBuilder(uri("/incidents"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers
				.ofString("{\"symptom\": \"Checkout orders stay pending for a long time\", \"sinceMinutes\": 15}"))
			.build());
		assertThat(opened.statusCode()).isEqualTo(202);
		String id = JSON.readTree(opened.body()).get("id").asString();

		JsonNode incident = pollUntilEnded(id, Duration.ofSeconds(20));

		assertThat(incident.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(incident.has("failureReason")).isFalse();
		assertThat(incident.get("report").get("incidentId").asString()).isEqualTo(id);
		assertThat(incident.get("report").get("stepsUsed").asInt()).isEqualTo(9);
		JsonNode steps = incident.get("steps");
		assertThat(steps).hasSize(9);
		assertThat(steps.get(2).get("kind").asString()).isEqualTo("TOOL_CALL");
		assertThat(steps.get(2).get("summary").asString()).startsWith("toolCallId=3 tool=status_summary");
		assertThat(steps.get(2).has("toolOutput")).isFalse();
		assertThat(MODEL.left()).isZero();

		JsonNode full = JSON.readTree(send(HttpRequest.newBuilder(uri("/incidents/" + id + "?full=true")).build()).body());
		assertThat(full.get("steps").get(2).get("toolOutput").asString()).contains("PAYMENT_PENDING");
	}

	private JsonNode pollUntilEnded(String id, Duration within) throws Exception {
		Instant deadline = Instant.now().plus(within);
		while (true) {
			JsonNode incident = JSON.readTree(send(HttpRequest.newBuilder(uri("/incidents/" + id)).build()).body());
			if (!incident.get("status").asString().equals("RUNNING") || Instant.now().isAfter(deadline)) {
				return incident;
			}
			Thread.sleep(50);
		}
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return this.http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + this.port + path);
	}

}
