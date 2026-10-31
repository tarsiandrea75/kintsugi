package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static dev.kintsugi.agent.loop.AgentLoopDossierModeTest.dossierFixture;
import static dev.kintsugi.agent.loop.ScriptedChatModel.assessedReport;
import static dev.kintsugi.agent.loop.ScriptedChatModel.assessment;
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
import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.rules.RulesCandidate;
import dev.kintsugi.agent.rules.RulesDiagnosis;

/**
 * Decision mode (agent spec, "Decision mode"; odd/tasks/decision-instrument.md T1): code gathers the evidence dossier
 * before the warm-up, and the model writes the report from it alone, with no evidence phase. The report call reads what
 * a dossier-mode report call reads when no drill-down result exists, except the run's own phases and budget. The
 * dossier is written in the view of {@code kintsugi.agent.decision.view} (odd/tasks/service-assessment.md T1): the
 * tables by default, or the per-service sheets. The report is that of {@code kintsugi.agent.decision.report} (T2): the
 * plain report by default, today's byte for byte, or the assessed report, which needs the sheets.
 */
class AgentLoopDecisionModeTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final String SYMPTOM = "Checkout orders stay pending for a long time";

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	/**
	 * The instruction of a decision run's report call with the plain report, as the commit before the assessed report
	 * wrote it (service-assessment T2): the plain report stays today's, byte for byte.
	 */
	private static final String PLAIN_INSTRUCTION = """
			Phase: CONCLUDE (the phases run in order: CONCLUDE)
			Tools in this phase: none
			Tool calls left: 0 in this phase, 0 in the incident

			Task: write the final report as a JSON object with these fields, in this order: summary first, then the \
			evidence, then the cause they lead to. summary: your reasoning in brief, at most 500 characters (about 80 \
			words). evidence: 1 to 5 items {toolCallId, tool, line, why}, or none when both causes are UNKNOWN; each \
			item cites a step and the number of one line of its summary: toolCallId and tool come from the summary's \
			header, line is the number at the start of one line of that summary (2 for the line that starts with \
			"2: "), and why is at most 200 characters. rootCauseService and rootCauseCategory, from the values listed \
			in the system message, as your summary concludes. confidence: a number from 0 to 1. To cite the dossier, \
			write toolCallId "dossier" and tool "dossier", with the number of the line you cite: D7 is line 7. \
			Citable toolCallIds: dossier.""";

	/** The schema of the same call, as the same commit sent it. */
	private static final String PLAIN_SCHEMA = "{\"type\":\"object\",\"properties\":{\"summary\":{\"type\":\"string\","
			+ "\"minLength\":1,\"maxLength\":500},\"evidence\":{\"type\":\"array\",\"minItems\":0,\"maxItems\":5,"
			+ "\"items\":{\"type\":\"object\",\"properties\":{\"toolCallId\":{\"anyOf\":[{\"type\":\"integer\","
			+ "\"minimum\":1},{\"type\":\"string\",\"enum\":[\"dossier\"]}]},\"tool\":{\"type\":\"string\"},"
			+ "\"line\":{\"type\":\"integer\",\"minimum\":1},\"why\":{\"type\":\"string\",\"minLength\":0,"
			+ "\"maxLength\":200}},\"required\":[\"toolCallId\",\"tool\",\"line\",\"why\"],"
			+ "\"additionalProperties\":false}},\"rootCauseService\":{\"type\":\"string\",\"enum\":[\"gateway-mock\","
			+ "\"payment-service\",\"order-service\",\"inventory-service\",\"postgres\",\"UNKNOWN\"]},"
			+ "\"rootCauseCategory\":{\"type\":\"string\",\"enum\":[\"DEPENDENCY_LATENCY\",\"DEPENDENCY_REJECTING\","
			+ "\"CONFIGURATION\",\"RESOURCE_EXHAUSTION\",\"UNKNOWN\"]},\"confidence\":{\"type\":\"number\","
			+ "\"minimum\":0,\"maximum\":1}},\"required\":[\"summary\",\"evidence\",\"rootCauseService\","
			+ "\"rootCauseCategory\",\"confidence\"],\"additionalProperties\":false}";

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

	/**
	 * A thinking model's answer (qwen-dev T3; agent spec, "Routes"): the report is read from the answer's content alone,
	 * so the thinking, here naming another cause, never reaches the parsed report or the stored step.
	 */
	@Test
	void aThinkingModelsReportIsReadFromItsContentNotItsThinking() {
		String thinking = "The pool looks exhausted, so " + report("postgres", "RESOURCE_EXHAUSTION", dossierEvidence(1));
		this.model.thinking(thinking, report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(1)));

		assertThat(run(AgentMode.DECISION)).isEqualTo(IncidentStatus.COMPLETED);

		JsonNode stored = storedReport();
		assertThat(stored.get("rootCauseService").asString()).isEqualTo("payment-service");
		assertThat(stored.get("rootCauseCategory").asString()).isEqualTo("DEPENDENCY_REJECTING");
		assertThat(AgentDatabase.admin()
			.sql("SELECT count(*) FROM incident_steps WHERE incident_id = ? AND (model_output LIKE '%postgres%' "
					+ "OR summary LIKE '%pool looks exhausted%')")
			.param(this.id)
			.query(Long.class)
			.single()).isZero();
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
			.isEqualTo(Prompts.system(LAB, SYMPTOM, 15));
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
		assertThat(schema(decisionReport)).isEqualTo(schema(dossierReport)).isEqualTo(Schemas.report(AgentMode.DOSSIER, LAB));
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
		ServiceSheets.Rendered sheets = ServiceSheets.render(new DossierGatherer(lab(), clock, LAB).gather(15), LAB);
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
		String today = Prompts.dossier(DossierRenderer.render(new DossierGatherer(lab(), clock, LAB).gather(15), LAB));
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
	void thePlainReportIsTodaysByteForByteInBothViews() {
		// The default loop, the tables and the plain report, then the sheets with the plain report named: V1 changes only
		// the dossier, never the report call's instruction or schema.
		this.model.text(unknownReport()).text(unknownReport());
		assertThat(run(AgentMode.DECISION)).isEqualTo(IncidentStatus.COMPLETED);
		this.loop.close();
		this.id = open();
		this.loop = loop(AgentMode.DECISION, DossierView.SHEETS, DecisionReport.PLAIN, fixedClock());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(this.model.prompts()).hasSize(2).allSatisfy(prompt -> {
			assertThat(last(prompt)).isEqualTo(PLAIN_INSTRUCTION);
			assertThat(schema(prompt)).isEqualTo(PLAIN_SCHEMA);
		});
		assertThat(storedReport().has("assessments")).isFalse();
	}

	@Test
	void anAssessedRunAsksForTheAssessmentsAndStoresTheEvidenceCodeDerivesFromThem() {
		Clock clock = fixedClock();
		ServiceSheets.Rendered sheets = sheets(clock);
		this.model.text(assessedReport("order-service", "CONFIGURATION", originAt(sheets, "order-service")));
		this.loop = loop(AgentMode.DECISION, DossierView.SHEETS, DecisionReport.ASSESSED, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		// One report call: the sheets after the same introduction, then the assessed task, with its own schema.
		assertThat(this.model.prompts()).hasSize(1);
		Prompt prompt = this.model.prompts().get(0);
		assertThat(prompt.getInstructions().get(1).getText()).isEqualTo(Prompts.dossier(sheets.text()));
		assertThat(last(prompt)).isEqualTo(Prompts.assessedReport(
				new Prompts.State(Phase.CONCLUDE, 0, 0, null, AgentMode.DECISION.phases(), true), List.of()));
		assertThat(schema(prompt)).isEqualTo(Schemas.assessedReport(LAB));
		assertThat(step(2)).containsEntry("kind", "REPORT").containsEntry("summary", "valid");
		// The stored report keeps the checked assessments and the evidence code derived from them: the ORIGIN's line,
		// quoted by code, cited as the dossier is, so that storage and scoring read it as a plain report's.
		JsonNode stored = storedReport();
		assertThat(stored.get("mode").asString()).isEqualTo("decision");
		assertThat(stored.get("rootCauseService").asString()).isEqualTo("order-service");
		assertThat(stored.get("rootCauseCategory").asString()).isEqualTo("CONFIGURATION");
		assertThat(stored.get("summary").asString()).isEqualTo("what the sheets show");
		assertThat(stored.get("stepsUsed").asInt()).isEqualTo(2);
		assertThat(stored.get("assessments").propertyNames()).containsExactlyInAnyOrderElementsOf(LAB.leafFirst());
		assertThat(stored.at("/assessments/order-service/role").asString()).isEqualTo("ORIGIN");
		int line = firstLine(sheets, "order-service");
		JsonNode evidence = stored.get("evidence");
		assertThat(evidence).hasSize(1);
		assertThat(evidence.get(0).get("toolCallId").asString()).isEqualTo("dossier");
		assertThat(evidence.get(0).get("tool").asString()).isEqualTo("dossier");
		assertThat(evidence.get(0).get("line").asInt()).isEqualTo(line);
		assertThat(evidence.get(0).get("quote").asString())
			.isEqualTo(DossierRenderer.line(sheets.text(), line).orElseThrow())
			.isEqualTo("- calls: inventory-service, payment-service; called by: external client.");
		assertThat(evidence.get(0).get("why").asString()).isEqualTo("its own facts");
	}

	@Test
	void anAssessedReportThatBreaksARuleIsRetriedOnceWithTheExactError() {
		Clock clock = fixedClock();
		ServiceSheets.Rendered sheets = sheets(clock);
		int gatewayLine = firstLine(sheets, "gateway-mock");
		// payment-service cites a line of gateway-mock's sheet.
		String[] wrong = originAt(sheets, "order-service");
		wrong[2] = assessment("payment-service", "UNAFFECTED", gatewayLine);
		this.model.text(assessedReport("order-service", "CONFIGURATION", wrong))
			.text(assessedReport("order-service", "CONFIGURATION", originAt(sheets, "order-service")));
		this.loop = loop(AgentMode.DECISION, DossierView.SHEETS, DecisionReport.ASSESSED, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		String error = "assessments.payment-service.line " + gatewayLine + " is a line of gateway-mock, not of "
				+ "payment-service; cite a line of its own sheet or of its part of \"Seen only in the previous window\"";
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE", "REPORT:CONCLUDE");
		assertThat(step(2)).containsEntry("summary", "invalid: " + error);
		assertThat(last(this.model.prompts().get(0))).doesNotContain("Your previous report was rejected");
		assertThat(last(this.model.prompts().get(1))).contains("\n\nYour previous report was rejected:\n- " + error
				+ "\nWrite the report again, fixing every error.");
		assertThat(schema(this.model.prompts().get(1))).isEqualTo(Schemas.assessedReport(LAB));
		assertThat(step(3)).containsEntry("summary", "valid");
	}

	@Test
	void aCutAssessedReportIsRetriedAskingForShorterWhysAndASecondInvalidOneEndsTheRun() {
		this.model.cut("{\"assessments\":{\"gateway-mock\":{\"role\":\"UNAFF").text("not a report");
		this.loop = loop(AgentMode.DECISION, DossierView.SHEETS, DecisionReport.ASSESSED, fixedClock());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.INVALID_REPORT);

		// The model writes no evidence items, so it is asked for shorter whys instead.
		String cut = "the answer was cut at 512 tokens before it ended; write a shorter report, with a shorter summary "
				+ "and shorter whys";
		assertThat(step(2)).containsEntry("summary", "invalid: " + cut + "; the answer is not a JSON object");
		assertThat(last(this.model.prompts().get(1))).contains("- " + cut + "\n");
		assertThat(failureReason()).isEqualTo(AgentLoop.REPORT_INVALID);
		assertThat(this.model.calls()).isEqualTo(2);
	}

	@Test
	void anAssessedRunWhoseGatheringGaveNoDossierAsksForThePlainReport() {
		// No sheets, so no line to assess: the plain report, with nothing to cite, as with the plain setting.
		this.lab.breaks(new NoClassDefFoundError("dev/kintsugi/Missing"));
		this.model.text(unknownReport());
		this.loop = loop(AgentMode.DECISION, DossierView.SHEETS, DecisionReport.ASSESSED, fixedClock());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		Prompt prompt = this.model.prompts().get(0);
		assertThat(schema(prompt)).isEqualTo(Schemas.report(AgentMode.DECISION, LAB));
		assertThat(last(prompt)).contains("Citable toolCallIds: none.").doesNotContain("assessments");
		assertThat(storedReport().get("rootCauseService").asString()).isEqualTo("UNKNOWN");
		assertThat(storedReport().has("assessments")).isFalse();
	}

	@Test
	void theAssessedReportNeedsTheSheetsInDecisionMode() {
		String needsSheets = "kintsugi.agent.decision.report=assessed needs kintsugi.agent.decision.view=sheets, got "
				+ "tables: the assessed report's checks read the service that owns each line of the sheets";
		assertThatThrownBy(() -> loop(AgentMode.DECISION, DossierView.TABLES, DecisionReport.ASSESSED, fixedClock()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(needsSheets);
		// The start stops with it.
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		ModelRouter router = task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS);
		LoopSettings settings = new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5));
		assertThatThrownBy(() -> new LoopConfig().agentLoop(this.incidents, router, this.lab, settings, fixedClock(),
				LAB, "decision", "tables", "assessed"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(needsSheets);
		// Explorer and dossier mode apply neither setting, so the pair does not stop them.
		for (AgentMode mode : List.of(AgentMode.EXPLORER, AgentMode.DOSSIER)) {
			new LoopConfig().agentLoop(this.incidents, router, this.lab, settings, fixedClock(), LAB, mode.value(),
					"tables", "assessed")
				.close();
		}
	}

	@Test
	void theReportIsReadInDecisionModeOnly() {
		// Dossier mode asks for the plain report whatever the setting: its report call is the default loop's, byte for
		// byte.
		Clock clock = fixedClock();
		this.model.quiet().quiet().text(unknownReport()).quiet().quiet().text(unknownReport());
		this.loop = loop(AgentMode.DOSSIER, DossierView.SHEETS, clock);
		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);
		Prompt plain = this.model.prompts().getLast();
		this.loop.close();
		this.id = open();
		this.loop = loop(AgentMode.DOSSIER, DossierView.SHEETS, DecisionReport.ASSESSED, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		Prompt assessed = this.model.prompts().getLast();
		assertThat(last(assessed)).isEqualTo(last(plain)).doesNotContain("assessments");
		assertThat(schema(assessed)).isEqualTo(schema(plain)).isEqualTo(Schemas.report(AgentMode.DOSSIER, LAB));
		assertThat(storedReport().has("assessments")).isFalse();
		// The one rule of which mode reads the setting, as for the view.
		assertThat(AgentMode.DECISION.reportFrom(DecisionReport.ASSESSED)).hasValue(DecisionReport.ASSESSED);
		assertThat(AgentMode.DOSSIER.reportFrom(DecisionReport.ASSESSED)).isEmpty();
		assertThat(AgentMode.EXPLORER.reportFrom(DecisionReport.ASSESSED)).isEmpty();
	}

	@Test
	void anyOtherReportAnEmptyOneIncludedStopsTheStartInEveryMode() throws IOException {
		assertThat(boundReport(Map.of("KINTSUGI_AGENT_DECISION_REPORT", ""))).isEmpty();
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		ModelRouter router = task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS);
		LoopSettings settings = new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5));
		// Checked in every mode, as the view is, although only decision mode applies it.
		for (AgentMode mode : AgentMode.values()) {
			for (String report : List.of("", "rich")) {
				assertThatThrownBy(() -> new LoopConfig().agentLoop(this.incidents, router, this.lab, settings,
						fixedClock(), LAB, mode.value(), "sheets", report))
					.as("%s, \"%s\"", mode.value(), report)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessage(
							"kintsugi.agent.decision.report must be plain, assessed or hybrid, got \"" + report + "\"");
			}
		}
	}

	@Test
	void theReportIsSelectedByItsVariableAndIsPlainByDefault() throws IOException {
		assertThat(DecisionReport.of("plain")).isEqualTo(DecisionReport.PLAIN);
		assertThat(DecisionReport.of("assessed")).isEqualTo(DecisionReport.ASSESSED);
		assertThat(DecisionReport.of("hybrid")).isEqualTo(DecisionReport.HYBRID);
		assertThat(List.of(DecisionReport.values())).extracting(DecisionReport::value)
			.containsExactly("plain", "assessed", "hybrid");
		assertThatThrownBy(() -> DecisionReport.of("rich")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("kintsugi.agent.decision.report must be plain, assessed or hybrid, got \"rich\"");
		// The default of the @Value bindings, and the one application.yaml writes as a literal.
		assertThat(DecisionReport.of(DecisionReport.DEFAULT)).isEqualTo(DecisionReport.PLAIN);
		assertThat(boundReport(Map.of())).isEqualTo(DecisionReport.DEFAULT);
		assertThat(boundReport(Map.of("KINTSUGI_AGENT_DECISION_REPORT", "assessed"))).isEqualTo("assessed");
		assertThat(boundReport(Map.of("KINTSUGI_AGENT_DECISION_REPORT", "hybrid"))).isEqualTo("hybrid");
	}

	@Test
	void aNullReportIsRefusedWhenTheLoopIsBuiltInEveryMode() {
		for (AgentMode mode : AgentMode.values()) {
			assertThatThrownBy(() -> loop(mode, DossierView.SHEETS, null, fixedClock())).as(mode.value())
				.isInstanceOf(NullPointerException.class)
				.hasMessage(AgentLoop.NULL_REPORT);
		}
	}

	@Test
	void theViewIsReadInDecisionModeOnly() {
		Clock clock = fixedClock();
		String tables = Prompts.dossier(DossierRenderer.render(new DossierGatherer(lab(), clock, LAB).gather(15), LAB));
		this.model.quiet().quiet().text(unknownReport());
		this.loop = loop(AgentMode.DOSSIER, DossierView.SHEETS, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		// Dossier mode shows the tables whatever the setting.
		assertThat(step(1)).containsEntry("summary", tables);
		assertThat(this.model.everything()).doesNotContain("# Evidence dossier, by service");
		// The one rule of which mode reads the setting, which the loop and the replay driver both follow.
		assertThat(AgentMode.DECISION.viewFrom(DossierView.SHEETS)).hasValue(DossierView.SHEETS);
		assertThat(AgentMode.DOSSIER.viewFrom(DossierView.SHEETS)).isEmpty();
		assertThat(AgentMode.EXPLORER.viewFrom(DossierView.SHEETS)).isEmpty();
	}

	@Test
	void anyOtherViewAnEmptyOneIncludedStopsTheStartInEveryMode() throws IOException {
		// An empty variable is a value, which the default does not replace.
		assertThat(boundView(Map.of("KINTSUGI_AGENT_DECISION_VIEW", ""))).isEmpty();
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		ModelRouter router = task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS);
		LoopSettings settings = new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5));
		// Checked in every mode, although only decision mode applies it: a typo fails at once.
		for (AgentMode mode : AgentMode.values()) {
			for (String view : List.of("", "charts")) {
				assertThatThrownBy(() -> new LoopConfig().agentLoop(this.incidents, router, this.lab, settings,
						fixedClock(), LAB, mode.value(), view, DecisionReport.DEFAULT))
					.as("%s, \"%s\"", mode.value(), view)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessage("kintsugi.agent.decision.view must be tables or sheets, got \"" + view + "\"");
			}
		}
	}

	@Test
	void aNullViewIsRefusedWhenTheLoopIsBuiltInEveryMode() {
		// At construction, not later in a decision run's gathering.
		for (AgentMode mode : AgentMode.values()) {
			assertThatThrownBy(() -> loop(mode, null, fixedClock())).as(mode.value())
				.isInstanceOf(NullPointerException.class)
				.hasMessage("decisionView must not be null: DossierView.TABLES is the default view");
		}
	}

	@Test
	void theViewIsSelectedByItsVariableAndIsTheTablesByDefault() throws IOException {
		assertThat(DossierView.of("tables")).isEqualTo(DossierView.TABLES);
		assertThat(DossierView.of("sheets")).isEqualTo(DossierView.SHEETS);
		assertThat(List.of(DossierView.values())).extracting(DossierView::value).containsExactly("tables", "sheets");
		assertThatThrownBy(() -> DossierView.of("charts")).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("kintsugi.agent.decision.view must be tables or sheets, got \"charts\"");
		// The default of the @Value bindings, and the one application.yaml writes as a literal.
		assertThat(DossierView.of(DossierView.DEFAULT)).isEqualTo(DossierView.TABLES);
		assertThat(boundView(Map.of())).isEqualTo(DossierView.DEFAULT);
		assertThat(boundView(Map.of("KINTSUGI_AGENT_DECISION_VIEW", "sheets"))).isEqualTo("sheets");
	}

	/**
	 * The hybrid report (hybrid T3; ADR-019): the plain report's task, schema and checks, with the rules' candidates
	 * listed after the citable ids, and a cause that must be one of them or UNKNOWN.
	 */
	@Test
	void aHybridRunListsTheRulesCandidatesAfterThePlainTaskAndStoresTheListedPairItNames() {
		Clock clock = fixedClock();
		List<RulesCandidate> candidates = candidates(hybridLab(), clock);
		assertThat(candidates).isNotEmpty();
		RulesCandidate first = candidates.getFirst();
		this.model.text(report(first.service(), first.category(), dossierEvidence(1)));
		this.loop = loop(hybridLab(), DossierView.TABLES, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		Prompt prompt = this.model.prompts().getFirst();
		StringBuilder block = new StringBuilder("\n\nCandidates the rules propose from the dossier's facts, in their ")
			.append("order:");
		for (int i = 0; i < candidates.size(); i++) {
			RulesCandidate candidate = candidates.get(i);
			block.append("\n").append(i + 1).append(". ").append(candidate.service()).append("|")
				.append(candidate.category()).append(", by ").append(candidate.rule()).append(": ")
				.append(String.join("; ", candidate.facts()));
		}
		block.append("\nPick exactly one candidate: write its rootCauseService and rootCauseCategory as listed, or "
				+ "both UNKNOWN when the evidence supports none of them. Say in the summary why you picked it, and cite "
				+ "the dossier lines that support it.");
		assertThat(last(prompt)).isEqualTo(PLAIN_INSTRUCTION + block);
		assertThat(schema(prompt)).isEqualTo(PLAIN_SCHEMA);
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE");
		JsonNode stored = storedReport();
		assertThat(stored.get("rootCauseService").asString()).isEqualTo(first.service());
		assertThat(stored.get("rootCauseCategory").asString()).isEqualTo(first.category());
	}

	@Test
	void aHybridReportOutsideTheListIsRetriedOnceThenEndsTheRunInvalidReport() {
		Clock clock = fixedClock();
		String allowed = candidates(hybridLab(), clock).stream()
			.map(candidate -> candidate.service() + "|" + candidate.category())
			.collect(java.util.stream.Collectors.joining(", "));
		// postgres is in no rule's reach: it is never a candidate.
		String outside = "rootCauseService|rootCauseCategory postgres|DEPENDENCY_LATENCY is not a listed candidate: "
				+ "name one of " + allowed + ", or UNKNOWN|UNKNOWN";
		this.model.text(report("postgres", "DEPENDENCY_LATENCY", dossierEvidence(1)))
			.text(report("postgres", "DEPENDENCY_LATENCY", dossierEvidence(1)));
		this.loop = loop(hybridLab(), DossierView.TABLES, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.INVALID_REPORT);

		assertThat(this.model.calls()).isEqualTo(2);
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE", "REPORT:CONCLUDE");
		assertThat(step(2).get("summary")).isEqualTo("invalid: " + outside);
		assertThat(last(this.model.prompts().get(1))).contains("Your previous report was rejected:\n- " + outside)
			.contains("Candidates the rules propose");
		assertThat(failureReason()).isEqualTo(AgentLoop.REPORT_INVALID);
	}

	@Test
	void aHybridRetryWithBothCausesUnknownCompletesTheRun() {
		Clock clock = fixedClock();
		this.model.text(report("postgres", "DEPENDENCY_LATENCY", dossierEvidence(1))).text(unknownReport());
		this.loop = loop(hybridLab(), DossierView.SHEETS, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(this.model.calls()).isEqualTo(2);
		assertThat(storedReport().get("rootCauseService").asString()).isEqualTo("UNKNOWN");
		assertThat(storedReport().get("rootCauseCategory").asString()).isEqualTo("UNKNOWN");
	}

	@Test
	void aHybridRunWithNoCandidateIsUnknownWithNoModelCall() {
		FakeLabTools quiet = noCandidateLab();
		Clock clock = fixedClock();
		assertThat(candidates(noCandidateLab(), clock)).isEmpty();
		this.loop = loop(quiet, DossierView.TABLES, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		// No warm-up and no report call: the model is never asked.
		assertThat(this.model.calls()).isZero();
		assertThat(this.model.warmUps()).isEmpty();
		assertThat(quiet.calls()).hasSize(33);
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "REPORT:CONCLUDE");
		assertThat(step(2).get("summary")).isEqualTo(AgentLoop.NO_CANDIDATE);
		JsonNode stored = storedReport();
		assertThat(stored.get("rootCauseService").asString()).isEqualTo("UNKNOWN");
		assertThat(stored.get("rootCauseCategory").asString()).isEqualTo("UNKNOWN");
		assertThat(stored.get("evidence")).isEmpty();
		assertThat(stored.get("mode").asString()).isEqualTo("decision");
		assertThat(stored.get("stepsUsed").asInt()).isEqualTo(2);
	}

	@Test
	void aHybridRunWhoseGatheringGaveNoDossierAsksForThePlainReport() {
		// No dossier, so no candidate can be computed: the plain report, with nothing to cite, as the assessed one.
		this.lab.breaks(new NoClassDefFoundError("dev/kintsugi/Missing"));
		this.model.text(unknownReport());
		this.loop = loop(AgentMode.DECISION, DossierView.TABLES, DecisionReport.HYBRID, fixedClock());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		Prompt prompt = this.model.prompts().get(0);
		assertThat(schema(prompt)).isEqualTo(Schemas.report(AgentMode.DECISION, LAB));
		assertThat(last(prompt)).contains("Citable toolCallIds: none.").doesNotContain("Candidates");
	}

	@Test
	void theHybridReportAppliesInDecisionModeOnlyAndInEitherView() {
		Clock clock = fixedClock();
		this.model.quiet().quiet().text(unknownReport());
		this.loop = loop(AgentMode.DOSSIER, DossierView.TABLES, DecisionReport.HYBRID, clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		Prompt prompt = this.model.prompts().getLast();
		assertThat(last(prompt)).doesNotContain("Candidates");
		assertThat(schema(prompt)).isEqualTo(Schemas.report(AgentMode.DOSSIER, LAB));
		assertThat(AgentMode.DOSSIER.reportFrom(DecisionReport.HYBRID)).isEmpty();
		assertThat(AgentMode.EXPLORER.reportFrom(DecisionReport.HYBRID)).isEmpty();
		// It reads the typed dossier, never the rendered text, so either view takes it.
		for (DossierView view : DossierView.values()) {
			loop(AgentMode.DECISION, view, DecisionReport.HYBRID, clock).close();
		}
	}

	private IncidentStatus run(AgentMode mode) {
		return run(mode, Clock.systemUTC());
	}

	/** The candidates the rules propose on the dossier {@code lab} gives on {@code clock}. */
	private static List<RulesCandidate> candidates(FakeLabTools lab, Clock clock) {
		return RulesDiagnosis.candidates(new DossierGatherer(lab, clock, LAB).gather(15), LAB);
	}

	/** A decision loop over {@code lab} that writes its dossier in {@code view} and asks for the hybrid report. */
	private AgentLoop loop(FakeLabTools lab, DossierView view, Clock clock) {
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		return new AgentLoop(this.incidents,
				task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS), lab,
				new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5)), clock, LAB, AgentMode.DECISION,
				view, DecisionReport.HYBRID);
	}

	/**
	 * This test's F3 lab with no restart in the incident window, so that payment-service is healthy and R-REACH names
	 * order-service: the rules propose one candidate, {@code order-service|CONFIGURATION}.
	 */
	private static FakeLabTools hybridLab() {
		return lab().answer("service_restarts", resource("/dossier/healthy/service_restarts.json"));
	}

	/**
	 * A lab with the healthy traffic of the golden dossiers: no connection failure, and no slow or refusing service, so
	 * the rules propose no candidate.
	 */
	private static FakeLabTools noCandidateLab() {
		return new FakeLabTools().answer("traffic_counts", resource("/dossier/healthy/traffic_counts-incident.json"));
	}

	private static String resource(String path) {
		try (var in = AgentLoopDecisionModeTest.class.getResourceAsStream(path)) {
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
		}
		catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
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
				new LoopSettings(12, timeBudget, Duration.ofSeconds(5)), clock, LAB, mode);
	}

	/** A loop in {@code mode} whose dossier, in decision mode, is written in {@code view}. */
	private AgentLoop loop(AgentMode mode, DossierView view, Clock clock) {
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		return new AgentLoop(this.incidents,
				task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS), this.lab,
				new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5)), clock, LAB, mode, view);
	}

	/** A loop in {@code mode} that, in decision mode, writes its dossier in {@code view} and asks for {@code report}. */
	private AgentLoop loop(AgentMode mode, DossierView view, DecisionReport report, Clock clock) {
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		return new AgentLoop(this.incidents,
				task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS), this.lab,
				new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5)), clock, LAB, mode, view, report);
	}

	/** The sheets a decision run of this test's lab writes on {@code clock}, with the owner of each line. */
	private static ServiceSheets.Rendered sheets(Clock clock) {
		return ServiceSheets.render(new DossierGatherer(lab(), clock, LAB).gather(15), LAB);
	}

	/** The number of the first line {@code service} owns in {@code sheets}: its place in the call graph. */
	private static int firstLine(ServiceSheets.Rendered sheets, String service) {
		return sheets.owners().indexOf(service) + 1;
	}

	/**
	 * The four assessments, in sheet order, each citing its service's first line: {@code origin} the ORIGIN and the
	 * others UNAFFECTED, which every rule of the assessed report allows.
	 */
	private static String[] originAt(ServiceSheets.Rendered sheets, String origin) {
		return LAB.leafFirst().stream()
			.map(service -> assessment(service, service.equals(origin) ? "ORIGIN" : "UNAFFECTED",
					firstLine(sheets, service)))
			.toArray(String[]::new);
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
		return bound(DossierView.PROPERTY, variables);
	}

	/** {@code kintsugi.agent.decision.report} as {@code application.yaml} binds it over these environment variables. */
	private static String boundReport(Map<String, Object> variables) throws IOException {
		return bound(DecisionReport.PROPERTY, variables);
	}

	private static String bound(String property, Map<String, Object> variables) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("environment", variables));
		new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);
		return Binder.get(environment).bind(property, String.class).get();
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
