package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.ScriptedChatModel.unknownReport;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.lab.LabTools;

/**
 * {@code kintsugi.agent.mode=dossier} behind the incident API: the incident and its report state the mode, and the
 * {@code DOSSIER} step shows the rendered dossier, with its calls only with {@code ?full=true} (agent spec, "Dossier
 * mode", "Read an incident").
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "kintsugi.agent.mode=dossier", "kintsugi.agent.route=local",
				"kintsugi.agent.allowed-hosts=127.0.0.1:*" })
class AgentLoopDossierApiTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final ScriptedChatModel MODEL = new ScriptedChatModel();

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@TestBean
	ChatModel chatModel;

	@TestBean
	LabTools labTools;

	static ChatModel chatModel() {
		return MODEL;
	}

	static LabTools labTools() {
		return new FakeLabTools().answer("traffic_counts", AgentLoopDossierModeTest.dossierFixture("traffic_counts-incident"))
			.answer("event_counts", AgentLoopDossierModeTest.dossierFixture("event_counts-order-incident"));
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
	void theIncidentAndItsReportStateTheModeAndTheDossierStepIsReadable() throws Exception {
		MODEL.quiet().quiet().text(unknownReport());

		HttpResponse<String> opened = send(HttpRequest.newBuilder(uri("/incidents"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers
				.ofString("{\"symptom\": \"Checkout orders stay pending for a long time\", \"sinceMinutes\": 15}"))
			.build());
		assertThat(opened.statusCode()).isEqualTo(202);
		String id = JSON.readTree(opened.body()).get("id").asString();

		JsonNode incident = pollUntilEnded(id, Duration.ofSeconds(20));

		assertThat(incident.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(incident.get("mode").asString()).isEqualTo("dossier");
		assertThat(incident.get("report").get("mode").asString()).isEqualTo("dossier");
		// The incident nests the stored report, so it carries the report's level (verification-levels T4): an
		// abstention, its L2 and L3 checks not applicable, is at L1.
		JsonNode verification = incident.get("report").get("verification");
		assertThat(verification.get("level").asString()).isEqualTo("L1");
		assertThat(verification.get("checks")).extracting(check -> check.get("name").asString())
			.containsExactly("schema", "citations", "owners-on-path", "cause-fact-changed");
		assertThat(verification.get("checks")).extracting(check -> check.get("pass").isNull())
			.containsExactly(false, false, true, true);
		JsonNode dossier = incident.get("steps").get(0);
		assertThat(dossier.get("kind").asString()).isEqualTo("DOSSIER");
		assertThat(dossier.get("phase").asString()).isEqualTo("DOSSIER");
		assertThat(dossier.get("summary").asString()).startsWith(Prompts.DOSSIER_INTRO).contains("# Evidence dossier");
		assertThat(dossier.has("toolOutput")).isFalse();
		assertThat(incident.get("steps").get(1).get("phase").asString()).isEqualTo("INVESTIGATE");

		JsonNode full = JSON.readTree(send(HttpRequest.newBuilder(uri("/incidents/" + id + "?full=true")).build()).body());
		assertThat(JSON.readTree(full.get("steps").get(0).get("toolOutput").asString())).hasSize(45);
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
