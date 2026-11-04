package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.lab.LabTools;
import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.lab.ToolResult;
import dev.kintsugi.agent.policy.Policy;
import dev.kintsugi.agent.policy.PolicyCounts;
import dev.kintsugi.agent.policy.Risk;
import dev.kintsugi.agent.rules.RulesVersion;
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

	/** The dossier message of a gathering whose {@code traffic_counts} call the policy refuses (ADR-027). */
	static final String REFUSED_GATHERING = Prompts.dossierNotGathered(
			"the gathering failed: IllegalStateException: the policy refuses traffic_counts: its risk is WRITE");

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
			.text(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "search_logs", 3)));

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
		// The dossier's own calls are code's: the model's two calls come after the 45 of the dossier.
		assertThat(this.lab.calls()).hasSize(47).endsWith("search_logs", "search_runbooks");
		assertThat(this.lab.calls().subList(0, 45)).doesNotContain("search_runbooks", "recent_events",
				"get_order_trace");
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

		assertThat(dossier).isEqualTo(explorer).isEqualTo(Prompts.system(LAB, SYMPTOM, 15));
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
		// T5b, evidence-by-reference: the dossier is cited as toolCallId "dossier" with the number of a line, D1 as 1.
		this.model.quiet()
			.quiet()
			.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(1), dossierEvidence(999)));

		assertThat(run(AgentMode.DOSSIER)).isEqualTo(IncidentStatus.COMPLETED);

		String dossier = this.model.prompts().get(0).getInstructions().get(1).getText();
		String first = dossier.lines().filter(line -> line.startsWith("D1 ")).findFirst().orElseThrow();
		assertThat(dossier).doesNotContain("\nD999 ");
		JsonNode evidence = storedReport().get("evidence");
		assertThat(evidence).hasSize(1);
		assertThat(evidence.get(0).get("toolCallId").asString()).isEqualTo("dossier");
		assertThat(evidence.get(0).get("tool").asString()).isEqualTo("dossier");
		assertThat(evidence.get(0).get("line").intValue()).isEqualTo(1);
		assertThat(evidence.get(0).get("quote").asString()).isEqualTo(first.substring("D1 ".length()));
		assertThat((String) step(4).get("summary"))
			.isEqualTo("valid; dropped: evidence[1]: line 999 is not in the dossier");
		Prompt reportPrompt = this.model.prompts().get(2);
		assertThat(last(reportPrompt)).contains("Citable toolCallIds: dossier.")
			.contains("To cite the dossier, write toolCallId \"dossier\" and tool \"dossier\"");
		assertThat(JSON.readTree(((StructuredOutputChatOptions) reportPrompt.getOptions()).getOutputSchema()))
			.isEqualTo(JSON.readTree(Schemas.report(AgentMode.DOSSIER, LAB)));
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
		assertThat(this.lab.calls()).hasSize(45 + 6);
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
		assertThat(calls).hasSize(45);
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
				"alternatives", "confidence", "evidence", "verification", "stepsUsed");
	}

	@Test
	void theDefaultLoopIsExplorerMode() {
		this.model.quiet().quiet().quiet().text(unknownReport());
		this.loop = new AgentLoop(this.incidents, router(Duration.ofSeconds(5)), this.lab, settings(), Clock.systemUTC(),
				LAB);

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

	/**
	 * A gathering call the policy refuses (ADR-027) is never made, and the gathering fails as any failed gathering does:
	 * the run goes on with a message that says so, and the refusal is counted in the run's outcome.
	 */
	@Test
	void aGatheringCallThePolicyRefusesFailsTheGatheringAndIsCounted() {
		this.model.quiet().quiet().text(unknownReport());
		this.loop = new AgentLoop(this.incidents, router(Duration.ofSeconds(5)), this.lab, settings(), Clock.systemUTC(),
				LAB, AgentMode.DOSSIER, DossierView.TABLES, DecisionReport.PLAIN, RulesVersion.V1,
				refusing("traffic_counts"));

		AgentLoop.Outcome outcome = this.loop.runToOutcome(this.id);

		assertThat(outcome.status()).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(this.lab.calls()).isNotEmpty().doesNotContain("traffic_counts");
		assertThat(step(1)).containsEntry("kind", "DOSSIER")
			.containsEntry("tool_output", null)
			.containsEntry("summary", REFUSED_GATHERING);
		assertThat(this.model.prompts().get(0).getInstructions().get(1).getText()).isEqualTo(REFUSED_GATHERING);
		assertThat(kinds()).containsExactly("DOSSIER:DOSSIER", "ACT:INVESTIGATE", "ACT:KNOWLEDGE", "REPORT:CONCLUDE");
		// The calls made before the refusal were reads, and the refused one is counted (ADR-027).
		assertThat(outcome.policy()).isEqualTo(new PolicyCounts(0, 1));
	}

	/**
	 * A gathering the loop stops waiting for is counted as it stood at its deadline (ADR-027): the abandoned thread goes
	 * on, and the refusal it meets after the deadline is not in the run's outcome. Latches order the threads: the third
	 * call holds the gathering past its deadline, and the loop's next phase lets it go on and waits until it has ended,
	 * so the late refusal is recorded before the run counts.
	 */
	@Test
	@Timeout(30)
	void anAbandonedGatheringIsCountedAsItStoodAtItsDeadline() {
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicReference<Thread> gatherer = new AtomicReference<>();
		AtomicBoolean ended = new AtomicBoolean();
		LabTools holding = new LabTools() {

			@Override
			public ToolResult call(String tool, String jsonArgs) {
				if (AgentLoopDossierModeTest.this.lab.calls().size() == 2) {
					gatherer.set(Thread.currentThread());
					held.countDown();
					awaitIgnoringInterrupts(release);
				}
				return AgentLoopDossierModeTest.this.lab.call(tool, jsonArgs);
			}

			@Override
			public List<ToolCallback> toolsFor(Phase phase) {
				// The loop's thread, once it has stopped waiting: the gathering goes on and meets traffic_counts.
				if (held.getCount() == 0 && !ended.getAndSet(true)) {
					release.countDown();
					awaitEnd(gatherer.get());
				}
				return AgentLoopDossierModeTest.this.lab.toolsFor(phase);
			}

		};
		this.model.quiet().quiet().text(unknownReport());
		this.loop = new AgentLoop(this.incidents, router(Duration.ofMillis(500)), holding, settings(),
				Clock.systemUTC(), LAB, AgentMode.DOSSIER, DossierView.TABLES, DecisionReport.PLAIN, RulesVersion.V1,
				refusing("traffic_counts"));

		AgentLoop.Outcome outcome = this.loop.runToOutcome(this.id);

		assertThat(outcome.status()).isEqualTo(IncidentStatus.COMPLETED);
		assertThat(this.model.prompts().get(0).getInstructions().get(1).getText())
			.isEqualTo(Prompts.dossierNotGathered("the gathering took longer than 500 ms"));
		// Three reads made before the deadline, the third held past it, two more after it, then the refusal that ended
		// the abandoned gathering.
		assertThat(this.lab.calls()).containsExactly("service_restarts", "status_summary", "status_summary",
				"status_summary", "status_summary");
		assertThat(outcome.policy()).isEqualTo(PolicyCounts.NONE);
	}

	/** Waits for {@code latch}, as a call the loop has abandoned does: the loop's interrupt does not end it. */
	private static void awaitIgnoringInterrupts(CountDownLatch latch) {
		while (true) {
			try {
				latch.await();
				return;
			}
			catch (InterruptedException interrupted) {
				// The loop cancels the gathering it stops waiting for; this call goes on regardless.
			}
		}
	}

	/** Waits until {@code worker}, a pool thread, has ended its task and waits for the next one. */
	private static void awaitEnd(Thread worker) {
		while (Arrays.stream(worker.getStackTrace()).noneMatch(frame -> frame.getMethodName().equals("getTask"))) {
			Thread.onSpinWait();
		}
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
			.withMessage("kintsugi.agent.mode must be explorer, dossier or decision, got \"triage\"");
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
		String system = Prompts.system(LAB, "s".repeat(500), 360);
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

	/**
	 * The production policy but for {@code tool}, which it does not name and so refuses as a write: a gatherer tool
	 * someone added without classifying it.
	 */
	static Policy refusing(String tool) {
		Map<String, Risk> risks = new HashMap<>(Policy.RISKS);
		risks.remove(tool);
		return new Policy(risks);
	}

	private IncidentStatus run(AgentMode mode) {
		return run(mode, this.lab, Duration.ofSeconds(5));
	}

	private IncidentStatus run(AgentMode mode, LabTools labTools, Duration callTimeout) {
		if (this.loop == null) {
			this.loop = new AgentLoop(this.incidents, router(callTimeout), labTools, settings(), Clock.systemUTC(), LAB,
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
