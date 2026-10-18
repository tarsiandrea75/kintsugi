package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.ScriptedChatModel.call;
import static dev.kintsugi.agent.loop.ScriptedChatModel.dossierEvidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.evidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.report;
import static dev.kintsugi.agent.loop.ScriptedChatModel.unknownReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.lab.LabTools;
import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.lab.ToolResult;
import dev.kintsugi.agent.summary.ToolSummaries;

/**
 * Dossier mode (agent spec, "Dossier mode"; ADR-010, "Amended in T5a"): the evidence dossier is gathered by code and
 * shown as the first user message, TRIAGE is skipped, and the model has 6 tool calls, INVESTIGATE 4 and KNOWLEDGE 2.
 * Explorer mode, the default, is unchanged.
 */
class AgentLoopDossierModeTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final Pattern LEAK = Pattern.compile("\\b(F[1-8]\\b|fault|inject)", Pattern.CASE_INSENSITIVE);

	private static final String SYMPTOM = "Checkout orders stay pending for a long time";

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	private final IncidentRepository incidents = new IncidentRepository(AgentDatabase.asAgentApp(), JSON);

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final FakeLabTools lab = new FakeLabTools().answer("traffic_counts", dossierFixture("traffic_counts-incident"))
		.answer("event_counts", dossierFixture("event_counts-order-incident"));

	private UUID id;

	private AgentLoop loop;

	@BeforeAll
	static void migrate() {
		AgentDatabase.migrate();
	}

	@BeforeEach
	void openIncident() {
		AgentDatabase.empty();
		this.id = open();
	}

	@AfterEach
	void close() {
		if (this.loop != null) {
			this.loop.close();
		}
	}

	@Test
	void theDossierIsTheFirstUserMessageAndTriageIsSkipped() {
		this.model.planned(call("search_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}"))
			.quiet()
			.planned(call("search_runbooks", "{\"query\":\"orders pending\"}"))
			.quiet()
			.text(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "search_logs", "Initializing Servlet")));

		assertThat(run(AgentMode.DOSSIER)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "ACT:INVESTIGATE", "TOOL_CALL:INVESTIGATE",
				"ACT:INVESTIGATE", "ACT:KNOWLEDGE", "TOOL_CALL:KNOWLEDGE", "ACT:KNOWLEDGE", "REPORT:CONCLUDE");
		List<Prompt> prompts = this.model.prompts();
		assertThat(prompts).hasSize(5);
		Prompt first = prompts.get(0);
		assertThat(first.getInstructions()).extracting(Message::getMessageType)
			.containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.USER);
		String dossier = first.getInstructions().get(1).getText();
		assertThat(dossier).startsWith(Prompts.DOSSIER_INTRO).contains("# Evidence dossier")
			.contains("## Service status (incident window)");
		assertThat(toolNames(first)).containsExactlyElementsOf(Phase.INVESTIGATE.tools());
		// Every call, the report included, reads the same dossier right after the system message.
		for (Prompt prompt : prompts) {
			assertThat(prompt.getInstructions().get(1).getText()).isEqualTo(dossier);
		}
		assertThat(last(first)).contains("the phases run in order: INVESTIGATE, KNOWLEDGE, CONCLUDE")
			.contains("Tool calls left: 4 in this phase, 6 in the incident");
		// The dossier's own calls are code's: the model's two calls come after the 29 of the dossier.
		assertThat(this.lab.calls()).hasSize(31).endsWith("search_logs", "search_runbooks");
		assertThat(this.lab.calls().subList(0, 29)).doesNotContain("search_runbooks", "recent_events",
				"get_order_trace", "query_metric");
	}

	@Test
	void theSystemMessageIsTheSameInBothModes() {
		this.model.quiet().quiet().quiet().text(unknownReport());
		run(AgentMode.EXPLORER);
		String explorer = this.model.prompts().get(0).getInstructions().get(0).getText();
		int explorerPrompts = this.model.prompts().size();
		this.loop.close();
		this.loop = null;

		this.id = open();
		this.model.quiet().quiet().text(unknownReport());
		run(AgentMode.DOSSIER);
		// The dossier run's first prompt, proved to be one by its dossier message.
		var dossierPrompt = this.model.prompts().get(explorerPrompts);
		assertThat(dossierPrompt.getInstructions().get(1).getText()).startsWith(Prompts.DOSSIER_INTRO);
		String dossier = dossierPrompt.getInstructions().get(0).getText();

		assertThat(dossier).isEqualTo(explorer).isEqualTo(Prompts.system(SYMPTOM, 15));
	}

	@Test
	void theFirstPlanOfDossierModeLooksAlongTheCallPathAndTheNextIsAFreshOne() {
		this.model.quiet().quiet().text(unknownReport());

		run(AgentMode.DOSSIER);

		String investigate = last(this.model.prompts().get(0));
		assertThat(investigate).startsWith("Phase: INVESTIGATE")
			.contains("first write your plan")
			.contains("at least two different services along the call path")
			.doesNotContain("a fresh plan");
		String knowledge = last(this.model.prompts().get(1));
		assertThat(knowledge).startsWith("Phase: KNOWLEDGE").contains("a fresh plan").contains("toolCallId");
	}

	@Test
	void aReportMayCiteTheDossierAndIsCompleted() {
		// T5b: the dossier is cited as toolCallId "dossier", its quote checked against the rendered dossier.
		this.model.quiet()
			.quiet()
			.text(report("payment-service", "DEPENDENCY_REJECTING",
					dossierEvidence("CLIENT_ERROR | UNKNOWN | ConnectException"),
					dossierEvidence("payment-service answered 503 to every call")));

		assertThat(run(AgentMode.DOSSIER)).isEqualTo(IncidentStatus.COMPLETED);

		JsonNode evidence = storedReport().get("evidence");
		assertThat(evidence).hasSize(1);
		assertThat(evidence.get(0).get("toolCallId").asString()).isEqualTo("dossier");
		assertThat(evidence.get(0).get("tool").asString()).isEqualTo("dossier");
		assertThat((String) step(4).get("summary")).contains("evidence[1].quote is not in the dossier");
		Prompt reportPrompt = this.model.prompts().get(2);
		assertThat(last(reportPrompt)).contains("Citable toolCallIds: dossier.")
			.contains("To cite the dossier, write toolCallId \"dossier\" and tool \"dossier\"");
		assertThat(JSON.readTree(((StructuredOutputChatOptions) reportPrompt.getOptions()).getOutputSchema()))
			.isEqualTo(JSON.readTree(Schemas.report(AgentMode.DOSSIER)));
	}

	@Test
	void dossierModeAllowsFourInvestigateCallsAndTwoKnowledgeCalls() {
		this.model
			.planned(call("search_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}"),
					call("count_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}"),
					call("recent_events", "{\"source\":\"order\",\"sinceMinutes\":15}"),
					call("search_logs", "{\"service\":\"payment-service\",\"sinceMinutes\":15}"),
					call("count_logs", "{\"service\":\"payment-service\",\"sinceMinutes\":15}"))
			.planned(call("search_runbooks", "{\"query\":\"orders pending\"}"),
					call("search_runbooks", "{\"query\":\"payment refused\"}"))
			.text(unknownReport());

		assertThat(run(AgentMode.DOSSIER)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "ACT:INVESTIGATE", "TOOL_CALL:INVESTIGATE",
				"TOOL_CALL:INVESTIGATE", "TOOL_CALL:INVESTIGATE", "TOOL_CALL:INVESTIGATE", "TOOL_CALL:INVESTIGATE",
				"ACT:KNOWLEDGE", "TOOL_CALL:KNOWLEDGE", "TOOL_CALL:KNOWLEDGE", "BUDGET:KNOWLEDGE", "REPORT:CONCLUDE");
		assertThat(step(7)).containsEntry("tool_outcome", "REFUSED_BUDGET");
		assertThat(step(11).get("summary")).isEqualTo("the incident's tool budget of 6 calls is used up; the evidence "
				+ "phases end and the run moves to CONCLUDE");
		assertThat(last(this.model.prompts().get(1))).contains("Tool calls left: 2 in this phase, 2 in the incident");
		assertThat(this.lab.calls()).hasSize(29 + 6);
		// Only the model's steps count tokens: the dossier, the tool calls and the budget's step made no model call.
		assertThat(AgentDatabase.admin()
			.sql("SELECT kind FROM incident_steps WHERE incident_id = ? AND (tokens IS NOT NULL"
					+ " OR prompt_tokens IS NOT NULL OR completion_tokens IS NOT NULL) ORDER BY step_no")
			.param(this.id)
			.query(String.class)
			.list()).containsExactly("ACT", "ACT", "REPORT");
	}

	@Test
	void theDossierStepRecordsWhatTheModelSawAndTheCallsAndTheModeIsRecorded() {
		this.model.quiet().quiet().text(unknownReport());

		assertThat(run(AgentMode.DOSSIER)).isEqualTo(IncidentStatus.COMPLETED);

		Map<String, Object> dossier = step(1);
		assertThat(dossier).containsEntry("kind", "DOSSIER")
			.containsEntry("phase", "DOSSIER")
			.containsEntry("model", null)
			.containsEntry("tool_name", null)
			.containsEntry("tool_outcome", null);
		assertThat(dossier.get("summary")).isEqualTo(this.model.prompts().get(0).getInstructions().get(1).getText());
		JsonNode calls = JSON.readTree((String) dossier.get("tool_output"));
		assertThat(calls).hasSize(29);
		assertThat(calls.get(0).get("tool").asString()).isEqualTo("service_restarts");
		assertThat(calls.get(0).get("args").get("sinceMinutes").asInt()).isEqualTo(15);
		assertThat(calls.get(0).get("outcome").asString()).isEqualTo("OK");
		assertThat(calls.get(0).get("output").asString()).isEqualTo(this.lab.output("service_restarts"));
		assertThat(mode()).isEqualTo("dossier");
		JsonNode stored = storedReport();
		assertThat(stored.get("mode").asString()).isEqualTo("dossier");
		assertThat(stored.get("stepsUsed").asInt()).isEqualTo(4);
	}

	@Test
	void explorerModeRecordsItsModeAndShowsNoDossier() {
		this.model.quiet().quiet().quiet().text(unknownReport());

		assertThat(run(AgentMode.EXPLORER)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(kinds()).containsExactly("ACT:TRIAGE", "ACT:INVESTIGATE", "ACT:KNOWLEDGE", "REPORT:CONCLUDE");
		assertThat(this.model.everything()).doesNotContain(Prompts.DOSSIER_INTRO).doesNotContain("# Evidence dossier");
		assertThat(this.lab.calls()).isEmpty();
		assertThat(mode()).isEqualTo("explorer");
		assertThat(storedReport().get("mode").asString()).isEqualTo("explorer");
		// jsonb keeps the fields, not their order.
		List<String> fields = storedReport().propertyNames().stream().toList();
		assertThat(fields).containsExactlyInAnyOrder("incidentId", "mode", "route", "model", "rootCauseService", "rootCauseCategory", "summary",
				"confidence", "evidence", "stepsUsed");
	}

	@Test
	void theDefaultLoopIsExplorerMode() {
		this.model.quiet().quiet().quiet().text(unknownReport());
		this.loop = new AgentLoop(this.incidents, router(Duration.ofSeconds(5)), this.lab, settings(), Clock.systemUTC());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(mode()).isEqualTo("explorer");
		assertThat(kinds()).first().isEqualTo("ACT:TRIAGE");
	}

	@Test
	void dossierCallsThatFailAreFactsInTheDossierAndTheRunGoesOn() {
		FakeLabTools bare = new FakeLabTools();
		this.model.quiet().quiet().text(unknownReport());

		assertThat(run(AgentMode.DOSSIER, bare, Duration.ofSeconds(5))).isEqualTo(IncidentStatus.COMPLETED);

		String dossier = (String) step(1).get("summary");
		assertThat(dossier).contains("traffic_counts failed for the incident window: outcome=CLIENT_ERROR")
			.contains("event_counts failed");
		assertThat(this.model.prompts().get(0).getInstructions().get(1).getText()).isEqualTo(dossier);
	}

	@Test
	void aGatheringPastItsDeadlineIsRecordedAndTheRunGoesOn() {
		LabTools slow = new LabTools() {

			@Override
			public ToolResult call(String tool, String jsonArgs) {
				if (tool.equals("service_restarts")) {
					sleep(Duration.ofSeconds(3));
				}
				return AgentLoopDossierModeTest.this.lab.call(tool, jsonArgs);
			}

			@Override
			public List<ToolCallback> toolsFor(Phase phase) {
				return AgentLoopDossierModeTest.this.lab.toolsFor(phase);
			}

		};
		this.model.quiet().quiet().text(unknownReport());

		assertThat(run(AgentMode.DOSSIER, slow, Duration.ofMillis(500))).isEqualTo(IncidentStatus.COMPLETED);

		String seen = this.model.prompts().get(0).getInstructions().get(1).getText();
		assertThat(seen).isEqualTo(Prompts.dossierNotGathered("the gathering took longer than 500 ms"));
		assertThat(step(1)).containsEntry("kind", "DOSSIER").containsEntry("summary", seen).containsEntry("tool_output",
				null);
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "ACT:INVESTIGATE", "ACT:KNOWLEDGE", "REPORT:CONCLUDE");
	}

	@Test
	void theDossierModePromptsNameNoFault() {
		this.model.planned(call("search_logs", "{\"service\":\"order-service\",\"sinceMinutes\":15}"))
			.quiet()
			.quiet()
			.text(unknownReport());

		run(AgentMode.DOSSIER);

		for (String text : List.of(Prompts.DOSSIER_INTRO, Prompts.dossierNotGathered("x"), this.model.everything())) {
			assertThat(LEAK.matcher(text).find()).as(text).isFalse();
		}
	}

	@Test
	void theModeIsReadFromItsPropertyAndDefaultsToExplorer() throws IOException {
		assertThat(AgentMode.of("explorer")).isEqualTo(AgentMode.EXPLORER);
		assertThat(AgentMode.of("dossier")).isEqualTo(AgentMode.DOSSIER);
		assertThat(AgentMode.DOSSIER.value()).isEqualTo("dossier");
		assertThatIllegalArgumentException().isThrownBy(() -> AgentMode.of("triage"))
			.withMessage("kintsugi.agent.mode must be explorer or dossier, got \"triage\"");
		assertThat(AgentMode.EXPLORER.phases()).containsExactly(Phase.TRIAGE, Phase.INVESTIGATE, Phase.KNOWLEDGE);
		assertThat(AgentMode.EXPLORER.toolBudget()).isEqualTo(LoopSettings.TOOL_BUDGET).isEqualTo(8);
		assertThat(AgentMode.DOSSIER.phases()).containsExactly(Phase.INVESTIGATE, Phase.KNOWLEDGE);
		assertThat(AgentMode.DOSSIER.toolBudget()).isEqualTo(6);

		StandardEnvironment environment = new StandardEnvironment();
		new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);
		assertThat(Binder.get(environment).bind("kintsugi.agent.mode", String.class).get()).isEqualTo("explorer");
	}

	/**
	 * The largest prompt of a dossier-mode run, the report call, fits {@code num-ctx} 8192 by a conservative estimate
	 * of one token per 3 characters: the system message with the longest symptom, the F3 dossier with its introduction,
	 * the 6 tool summaries at their cap, the report task, and the report's answer cap. The ACT calls hold less: at most
	 * 5 earlier summaries, the tool definitions and a smaller cap.
	 */
	@Test
	void theLargestDossierModePromptFitsTheContext() {
		String system = Prompts.system("s".repeat(500), 360);
		String dossier = Prompts.dossier(golden("f3"));
		List<ToolStep> steps = List.of(new ToolStep(2, "search_logs", "OK", "{}", null),
				new ToolStep(3, "count_logs", "OK", "{}", null), new ToolStep(4, "recent_events", "OK", "{}", null),
				new ToolStep(5, "get_order_trace", "OK", "{}", null), new ToolStep(7, "search_runbooks", "OK", "{}", null),
				new ToolStep(8, "search_runbooks", "OK", "{}", null));
		String task = Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null, AgentMode.DOSSIER.phases(), true),
				List.of(), steps);
		int summaries = AgentMode.DOSSIER.toolBudget() * ToolSummaries.MAX_CHARS
				+ "Evidence so far, oldest first:\n\n".length();

		int estimate = (system.length() + dossier.length() + summaries + task.length()) / 3 + CAPS.report();

		assertThat(estimate).isLessThanOrEqualTo(8192);
	}

	private IncidentStatus run(AgentMode mode) {
		return run(mode, this.lab, Duration.ofSeconds(5));
	}

	private IncidentStatus run(AgentMode mode, LabTools labTools, Duration callTimeout) {
		if (this.loop == null) {
			this.loop = new AgentLoop(this.incidents, router(callTimeout), labTools, settings(), Clock.systemUTC(),
					mode);
		}
		return this.loop.run(this.id);
	}

	private ModelRouter router(Duration callTimeout) {
		CallTimeouts timeouts = new CallTimeouts(callTimeout, callTimeout);
		return task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS);
	}

	private static LoopSettings settings() {
		return new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5));
	}

	private static UUID open() {
		UUID id = UUID.randomUUID();
		AgentDatabase.admin()
			.sql("INSERT INTO incidents (id, signal, status, started_at) VALUES (?, CAST(? AS jsonb), 'RUNNING', ?)")
			.params(id, "{\"symptom\":\"" + SYMPTOM + "\",\"sinceMinutes\":15}", Timestamp.from(Instant.now()))
			.update();
		return id;
	}

	private List<String> kinds() {
		return AgentDatabase.admin()
			.sql("SELECT kind || ':' || phase FROM incident_steps WHERE incident_id = ? ORDER BY step_no")
			.param(this.id)
			.query(String.class)
			.list();
	}

	private Map<String, Object> step(int stepNo) {
		return AgentDatabase.admin()
			.sql("SELECT kind, phase, model, tool_name, tool_outcome, summary, tool_output FROM incident_steps "
					+ "WHERE incident_id = ? AND step_no = ?")
			.params(this.id, stepNo)
			.query()
			.singleRow();
	}

	private String mode() {
		return AgentDatabase.admin()
			.sql("SELECT mode FROM incidents WHERE id = ?")
			.param(this.id)
			.query(String.class)
			.single();
	}

	private JsonNode storedReport() {
		return JSON.readTree(AgentDatabase.admin()
			.sql("SELECT report::text FROM incidents WHERE id = ?")
			.param(this.id)
			.query(String.class)
			.single());
	}

	private static List<String> toolNames(Prompt prompt) {
		if (!(prompt.getOptions() instanceof ToolCallingChatOptions options) || options.getToolCallbacks() == null) {
			return List.of();
		}
		return options.getToolCallbacks().stream().map(tool -> tool.getToolDefinition().name()).toList();
	}

	private static String last(Prompt prompt) {
		return prompt.getInstructions().get(prompt.getInstructions().size() - 1).getText();
	}

	static String dossierFixture(String name) {
		return resource("/dossier/f3/" + name + ".json").strip();
	}

	private static String golden(String name) {
		return resource("/dossier/golden/" + name + ".md");
	}

	private static String resource(String path) {
		try (InputStream in = AgentLoopDossierModeTest.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalArgumentException("no resource " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void sleep(Duration delay) {
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		}
	}

}
