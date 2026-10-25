package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.AgentLoopDossierModeTest.dossierFixture;
import static dev.kintsugi.agent.loop.ScriptedChatModel.dossierEvidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.evidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.report;
import static dev.kintsugi.agent.loop.ScriptedChatModel.unknownReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.DossierRenderer;
import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.dossier.ServiceSheets;
import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.incident.IncidentStatus;

/**
 * Decision mode (agent spec, "Decision mode"; odd/tasks/decision-instrument.md T1): code gathers the evidence dossier
 * before the warm-up, and the model writes the report from it alone, with no evidence phase. The report call reads what
 * a dossier-mode report call reads when no drill-down result exists, except the run's own phases and budget. The
 * dossier is written in the view of {@code kintsugi.agent.decision.view} (odd/tasks/service-assessment.md T1): the
 * tables by default, or the per-service sheets.
 */
class AgentLoopDecisionModeTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final String SYMPTOM = "Checkout orders stay pending for a long time";

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	private final IncidentRepository incidents = new IncidentRepository(AgentDatabase.asAgentApp(), JSON);

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final FakeLabTools lab = lab();

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
	void aDecisionRunGathersTheDossierThenMakesOneReportCallAndNoPhaseCall() {
		this.model.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(1)));

		assertThat(run(AgentMode.DECISION)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE");
		// One model call, the report, offered no tool; the warm-up is apart.
		assertThat(this.model.prompts()).hasSize(1);
		assertThat(toolNames(this.model.prompts().get(0))).isEmpty();
		assertThat(this.model.warmUps()).hasSize(1);
		// Only the dossier's 33 calls reach the lab: no drill-down call.
		assertThat(this.lab.calls()).hasSize(33).doesNotContain("recent_events", "get_order_trace", "search_runbooks");
		assertThat(mode()).isEqualTo("decision");
		JsonNode stored = storedReport();
		assertThat(stored.get("mode").asString()).isEqualTo("decision");
		assertThat(stored.get("stepsUsed").asInt()).isEqualTo(2);
		String dossier = (String) step(1).get("summary");
		String first = dossier.lines().filter(line -> line.startsWith("D1 ")).findFirst().orElseThrow();
		assertThat(stored.get("evidence")).hasSize(1);
		assertThat(stored.get("evidence").get(0).get("quote").asString()).isEqualTo(first.substring("D1 ".length()));
	}

	@Test
	void theDossierIsGatheredBeforeTheWarmUp() {
		AtomicInteger callsAtWarmUp = callsAtWarmUp();
		this.model.text(unknownReport());

		assertThat(run(AgentMode.DECISION)).isEqualTo(IncidentStatus.COMPLETED);

		// The 33 calls were all made when the warm-up came, so the windows end at the incident's opening.
		assertThat(callsAtWarmUp.get()).isEqualTo(33);
		assertThat(this.model.warmUps()).hasSize(1);
	}

	@Test
	void dossierModeStillGathersItsDossierAfterTheWarmUp() {
		AtomicInteger callsAtWarmUp = callsAtWarmUp();
		this.model.quiet().quiet().text(unknownReport());

		assertThat(run(AgentMode.DOSSIER)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(callsAtWarmUp.get()).isZero();
		assertThat(this.lab.calls()).hasSize(33);
	}

	@Test
	void theReportCallReadsWhatADossierModeReportReadsWithTheRunsOwnPhasesAndBudget() {
		// One fixed clock for both runs, so that their dossiers, windows and uptimes included, are the same text.
		Clock clock = Clock.fixed(Instant.now().plus(Duration.ofMinutes(1)), ZoneOffset.UTC);
		this.model.quiet().quiet().text(unknownReport());
		run(AgentMode.DOSSIER, clock);
		// The report call of a dossier run whose phases made no tool call: no drill-down result exists.
		Prompt dossierReport = this.model.prompts().getLast();
		int dossierCalls = this.model.prompts().size();
		this.loop.close();
		this.loop = null;
		this.id = open();
		this.model.text(unknownReport());

		assertThat(run(AgentMode.DECISION, clock)).isEqualTo(IncidentStatus.COMPLETED);

		// The decision run's one model call, its report, comes after the dossier run's calls.
		assertThat(this.model.prompts()).hasSize(dossierCalls + 1);
		Prompt decisionReport = this.model.prompts().getLast();
		assertThat(decisionReport.getInstructions()).extracting(Message::getMessageType)
			.containsExactly(MessageType.SYSTEM, MessageType.USER, MessageType.USER);
		// The system message and the dossier message, byte for byte, "before the first phase" included.
		assertThat(decisionReport.getInstructions().get(0).getText())
			.isEqualTo(dossierReport.getInstructions().get(0).getText())
			.isEqualTo(Prompts.system(SYMPTOM, 15));
		assertThat(decisionReport.getInstructions().get(1).getText())
			.isEqualTo(dossierReport.getInstructions().get(1).getText())
			.startsWith(Prompts.DOSSIER_INTRO);
		// The instruction states the run's own phases and budget, then the same report task.
		String decision = last(decisionReport);
		assertThat(decision).startsWith("Phase: CONCLUDE (the phases run in order: CONCLUDE)\nTools in this phase: none\n"
				+ "Tool calls left: 0 in this phase, 0 in the incident\n\n");
		String dossier = last(dossierReport);
		assertThat(dossier).startsWith("Phase: CONCLUDE (the phases run in order: INVESTIGATE, KNOWLEDGE, CONCLUDE)");
		assertThat(task(decision)).isEqualTo(task(dossier)).contains("Citable toolCallIds: dossier.");
		assertThat(schema(decisionReport)).isEqualTo(schema(dossierReport)).isEqualTo(Schemas.report(AgentMode.DOSSIER));
	}

	@Test
	void anInvalidReportIsRetriedOnceAndTheDossierIsTheOnlyCitableOutput() {
		// No tool call ran, so a step number cites nothing: the first report is rejected, and the retry cites the dossier.
		this.model.text(report("payment-service", "DEPENDENCY_REJECTING", evidence(3, "search_logs", 3)))
			.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(1)));

		assertThat(run(AgentMode.DECISION)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE", "REPORT:CONCLUDE");
		assertThat(step(2).get("summary"))
			.isEqualTo("invalid: evidence[0].toolCallId 3 is not a tool call of this incident");
		assertThat(last(this.model.prompts().get(0))).contains("Citable toolCallIds: dossier.")
			.doesNotContain("Your previous report was rejected");
		assertThat(last(this.model.prompts().get(1))).contains("Your previous report was rejected")
			.contains("- evidence[0].toolCallId 3 is not a tool call of this incident");
		assertThat(storedReport().get("evidence").get(0).get("toolCallId").asString()).isEqualTo("dossier");
	}

	@Test
	void aSecondInvalidReportEndsTheRunInvalidReport() {
		this.model.text("not a report").text("still not a report");

		assertThat(run(AgentMode.DECISION)).isEqualTo(IncidentStatus.INVALID_REPORT);

		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE", "REPORT:CONCLUDE");
		assertThat(this.model.calls()).isEqualTo(2);
		assertThat(failureReason()).isEqualTo(AgentLoop.REPORT_INVALID);
	}

	@Test
	void aFailedWarmUpEndsTheRunFailedAfterItsDossierStep() {
		this.model.warmUpFails(new IllegalStateException("Connection refused"));

		assertThat(run(AgentMode.DECISION)).isEqualTo(IncidentStatus.FAILED);

		assertThat(failureReason()).startsWith("the model warm-up failed:").contains("Connection refused");
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER");
		assertThat(this.model.calls()).isZero();
	}

	@Test
	void aGatheringThatThrowsLeavesTheReportNothingToCiteSoOnlyAnUnknownReportIsValid() {
		// The gatherer records a call that throws an exception as a failed call; an Error ends the gathering.
		this.lab.breaks(new NoClassDefFoundError("dev/kintsugi/Missing"));
		this.model.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(1))).text(unknownReport());

		assertThat(run(AgentMode.DECISION)).isEqualTo(IncidentStatus.COMPLETED);

		String seen = Prompts.dossierNotGathered("the gathering failed: NoClassDefFoundError: dev/kintsugi/Missing");
		assertThat(step(1)).containsEntry("kind", "DOSSIER").containsEntry("summary", seen).containsEntry("tool_output",
				null);
		// The run goes on to the warm-up and the report call, which reads that message and has nothing to cite.
		assertThat(this.model.warmUps()).hasSize(1);
		Prompt first = this.model.prompts().get(0);
		assertThat(first.getInstructions().get(1).getText()).isEqualTo(seen);
		assertThat(last(first)).contains("Citable toolCallIds: none.").doesNotContain("dossier");
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE", "REPORT:CONCLUDE");
		assertThat(step(2).get("summary"))
			.isEqualTo("invalid: evidence[0].toolCallId dossier: the dossier was not gathered");
		JsonNode stored = storedReport();
		assertThat(stored.get("rootCauseService").asString()).isEqualTo("UNKNOWN");
		assertThat(stored.get("rootCauseCategory").asString()).isEqualTo("UNKNOWN");
	}

	@Test
	void aGatheringPastTheActDeadlineIsRecordedAndTheRunGoesOnToTheReport() {
		this.lab.slow("service_restarts", Duration.ofSeconds(3));
		this.model.text(unknownReport());
		this.loop = loop(AgentMode.DECISION, Duration.ofMillis(500), Duration.ofMinutes(30), Clock.systemUTC());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		String seen = Prompts.dossierNotGathered("the gathering took longer than 500 ms");
		assertThat(step(1)).containsEntry("kind", "DOSSIER").containsEntry("summary", seen).containsEntry("tool_output",
				null);
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE");
		assertThat(this.model.warmUps()).hasSize(1);
		assertThat(this.model.prompts().get(0).getInstructions().get(1).getText()).isEqualTo(seen);
	}

	@Test
	void theGatheringDoesNotCountAgainstTheIncidentsTimeBudget() {
		// Like the warm-up: the incident's time budget starts when the warm-up ends, after the gathering, which only
		// the ACT deadline bounds. The gathering (3 s) outlasts the whole budget (2 s), so a budget that counted it
		// would end the run, and it stays far inside the ACT deadline (30 s): 27 s to spare on a loaded host. The
		// budget still leaves the report call and its writes ample real time.
		this.lab.slow("service_restarts", Duration.ofSeconds(3));
		this.model.text(unknownReport());
		this.loop = loop(AgentMode.DECISION, Duration.ofSeconds(30), Duration.ofSeconds(2), Clock.systemUTC());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat((String) step(1).get("summary")).startsWith(Prompts.DOSSIER_INTRO);
		assertThat(this.lab.calls()).hasSize(33);
	}

	@Test
	void theTimeBudgetStartsWhenTheWarmUpEndsNotWhenTheIncidentOpened() {
		// By the loop's clock the incident opened ten seconds before its run started, longer ago than its whole time
		// budget (2 s): the check before the gathering, before the warm-up, must not end the run.
		Clock later = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(10));
		this.model.text(unknownReport());
		this.loop = loop(AgentMode.DECISION, Duration.ofSeconds(5), Duration.ofSeconds(2), later);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE");
	}

	@Test
	void decisionModeIsSelectedByItsPropertyAndHasNoPhase() throws IOException {
		assertThat(AgentMode.of("decision")).isEqualTo(AgentMode.DECISION);
		assertThat(AgentMode.DECISION.value()).isEqualTo("decision");
		assertThat(AgentMode.DECISION.phases()).isEmpty();
		assertThat(AgentMode.DECISION.toolBudget()).isZero();
		assertThat(List.of(AgentMode.values())).filteredOn(AgentMode::gathersDossier)
			.containsExactly(AgentMode.DOSSIER, AgentMode.DECISION);

		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.addFirst(new MapPropertySource("environment", Map.<String, Object>of("KINTSUGI_AGENT_MODE", "decision")));
		new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);
		assertThat(AgentMode.of(Binder.get(environment).bind("kintsugi.agent.mode", String.class).get()))
			.isEqualTo(AgentMode.DECISION);
	}

	@Test
	void theSheetsViewGivesTheReportCallTheSheetsWhoseLinesAreCitedAsTheTablesAre() {
		Clock clock = fixedClock();
		ServiceSheets.Rendered sheets = ServiceSheets.render(new DossierGatherer(lab(), clock).gather(15));
		// The first line gateway-mock owns: its place in the call graph.
		int line = sheets.owners().indexOf("gateway-mock") + 1;
		this.model.text(report("gateway-mock", "DEPENDENCY_LATENCY", dossierEvidence(line)));
		this.loop = loop(AgentMode.DECISION, DossierView.SHEETS, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		// The same introduction, its citing sentence included, then the sheets in place of the tables.
		String seen = Prompts.dossier(sheets.text());
		assertThat(seen).startsWith(Prompts.DOSSIER_INTRO + "\n\n# Evidence dossier, by service\n");
		assertThat(step(1)).containsEntry("summary", seen);
		assertThat(this.model.prompts().get(0).getInstructions().get(1).getText()).isEqualTo(seen);
		assertThat(last(this.model.prompts().get(0))).contains("Citable toolCallIds: dossier.");
		JsonNode evidence = storedReport().get("evidence").get(0);
		assertThat(evidence.get("quote").asString()).isEqualTo("- calls: none; called by: payment-service.")
			.isEqualTo(DossierRenderer.line(sheets.text(), line).orElseThrow());
	}

	@Test
	void theTablesViewIsTheDefaultAndKeepsTodaysDossierByteForByte() {
		Clock clock = fixedClock();
		String today = Prompts.dossier(DossierRenderer.render(new DossierGatherer(lab(), clock).gather(15)));
		this.model.text(unknownReport()).text(unknownReport());

		// The loop of the constructor without a view, as every caller built it before the setting.
		assertThat(run(AgentMode.DECISION, clock)).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(step(1)).containsEntry("summary", today);
		this.loop.close();
		this.id = open();
		this.loop = loop(AgentMode.DECISION, DossierView.TABLES, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(step(1)).containsEntry("summary", today);
		assertThat(this.model.prompts()).extracting(prompt -> prompt.getInstructions().get(1).getText())
			.containsExactly(today, today);
	}

	@Test
	void theViewIsReadInDecisionModeOnly() {
		Clock clock = fixedClock();
		String tables = Prompts.dossier(DossierRenderer.render(new DossierGatherer(lab(), clock).gather(15)));
		this.model.quiet().quiet().text(unknownReport());
		this.loop = loop(AgentMode.DOSSIER, DossierView.SHEETS, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		// Dossier mode shows the tables whatever the setting.
		assertThat(step(1)).containsEntry("summary", tables);
		assertThat(this.model.everything()).doesNotContain("# Evidence dossier, by service");
	}

	@Test
	void theViewIsSelectedByItsVariableAndIsTheTablesByDefault() throws IOException {
		assertThat(DossierView.of("tables")).isEqualTo(DossierView.TABLES);
		assertThat(DossierView.of("sheets")).isEqualTo(DossierView.SHEETS);
		assertThat(List.of(DossierView.values())).extracting(DossierView::value).containsExactly("tables", "sheets");
		assertThatThrownBy(() -> DossierView.of("charts")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("kintsugi.agent.decision.view must be tables or sheets, got \"charts\"");
		assertThat(boundView(Map.of())).isEqualTo("tables");
		assertThat(boundView(Map.of("KINTSUGI_AGENT_DECISION_VIEW", "sheets"))).isEqualTo("sheets");
	}

	private IncidentStatus run(AgentMode mode) {
		return run(mode, Clock.systemUTC());
	}

	private IncidentStatus run(AgentMode mode, Clock clock) {
		if (this.loop == null) {
			this.loop = loop(mode, Duration.ofSeconds(5), Duration.ofMinutes(30), clock);
		}
		return this.loop.run(this.id);
	}

	/** A loop whose model calls and gathering wait at most {@code deadline}, with the incident's {@code timeBudget}. */
	private AgentLoop loop(AgentMode mode, Duration deadline, Duration timeBudget, Clock clock) {
		CallTimeouts timeouts = new CallTimeouts(deadline, deadline);
		return new AgentLoop(this.incidents,
				task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS), this.lab,
				new LoopSettings(12, timeBudget, Duration.ofSeconds(5)), clock, mode);
	}

	/** A loop in {@code mode} whose dossier, in decision mode, is written in {@code view}. */
	private AgentLoop loop(AgentMode mode, DossierView view, Clock clock) {
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		return new AgentLoop(this.incidents,
				task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS), this.lab,
				new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5)), clock, mode, view);
	}

	/** The lab of these tests: F3's traffic and order events over the recorded mcp-tools outputs. */
	private static FakeLabTools lab() {
		return new FakeLabTools().answer("traffic_counts", dossierFixture("traffic_counts-incident"))
			.answer("event_counts", dossierFixture("event_counts-order-incident"));
	}

	/** One fixed clock, so that two gatherings, windows and uptimes included, give the same dossier. */
	private static Clock fixedClock() {
		return Clock.fixed(Instant.now().plus(Duration.ofMinutes(1)), ZoneOffset.UTC);
	}

	/** {@code kintsugi.agent.decision.view} as {@code application.yaml} binds it over these environment variables. */
	private static String boundView(Map<String, Object> variables) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("environment", variables));
		new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);
		return Binder.get(environment).bind("kintsugi.agent.decision.view", String.class).get();
	}

	/** Makes the warm-up note how many lab calls were made before it came, and answer {@code OK}. */
	private AtomicInteger callsAtWarmUp() {
		AtomicInteger calls = new AtomicInteger(-1);
		this.model.warmUp(prompt -> {
			calls.set(this.lab.calls().size());
			return new ChatResponse(List.of(new Generation(new AssistantMessage("OK"))));
		});
		return calls;
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
			.sql("SELECT kind, phase, summary, tool_output FROM incident_steps WHERE incident_id = ? AND step_no = ?")
			.params(this.id, stepNo)
			.query()
			.singleRow();
	}

	private String mode() {
		return incidentColumn("mode");
	}

	private String failureReason() {
		return incidentColumn("failure_reason");
	}

	private String incidentColumn(String column) {
		return AgentDatabase.admin()
			.sql("SELECT " + column + " FROM incidents WHERE id = ?")
			.param(this.id)
			.query(String.class)
			.single();
	}

	private JsonNode storedReport() {
		return JSON.readTree(incidentColumn("report::text"));
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

	/** The instruction without its state lines: the report task, from the blank line after them. */
	private static String task(String instruction) {
		return instruction.substring(instruction.indexOf("\n\n"));
	}

	private static String schema(Prompt prompt) {
		return ((StructuredOutputChatOptions) prompt.getOptions()).getOutputSchema();
	}

}
