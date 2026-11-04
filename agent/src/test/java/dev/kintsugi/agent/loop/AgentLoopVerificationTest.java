package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static dev.kintsugi.agent.loop.AgentLoopDecisionModeTest.fixedClock;
import static dev.kintsugi.agent.loop.AgentLoopDecisionModeTest.lab;
import static dev.kintsugi.agent.loop.AgentLoopDecisionModeTest.noCandidateLab;
import static dev.kintsugi.agent.loop.AgentLoopDecisionModeTest.open;
import static dev.kintsugi.agent.loop.ScriptedChatModel.assessedReport;
import static dev.kintsugi.agent.loop.ScriptedChatModel.assessment;
import static dev.kintsugi.agent.loop.ScriptedChatModel.dossierEvidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.report;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.AgentDatabase;
import dev.kintsugi.agent.dossier.Change;
import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.DossierRenderer;
import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.dossier.LineFact;
import dev.kintsugi.agent.dossier.ServiceSheets;
import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.incident.IncidentStatus;

/**
 * The stored verification level (verification-levels T4; ADR-028; agent spec, "The report"): every accepted report is
 * stored with {@code verification: {level, checks}} after the model's fields and before {@code stepsUsed}, read from the
 * line facts of the dossier the model read, in either view; no level causes a model call, and a run with no stored
 * report has no level.
 *
 * <p>
 * The lab of these runs is F3's ({@link AgentLoopDecisionModeTest#lab}): each service's restart line is a changed fact,
 * order-service calls payment-service, payment-service calls gateway-mock, and inventory-service is on another branch.
 */
class AgentLoopVerificationTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	/** The plain report's stored fields, in the order the loop writes them (jsonb keeps the fields, not their order). */
	private static final List<String> PLAIN_ORDER = List.of("incidentId", "mode", "route", "model", "summary",
			"evidence", "rootCauseService", "rootCauseCategory", "alternatives", "confidence", "verification",
			"stepsUsed");

	private static final List<String> PLAIN_CHECKS = List.of("schema", "citations", "owners-on-path",
			"cause-fact-changed");

	/** The report string the loop last stored, as it wrote it, before jsonb reorders its fields. */
	private final AtomicReference<String> completed = new AtomicReference<>();

	private final IncidentRepository incidents = new IncidentRepository(AgentDatabase.asAgentApp(), JSON) {

		@Override
		public boolean complete(UUID id, String report, Instant endedAt) {
			AgentLoopVerificationTest.this.completed.set(report);
			return super.complete(id, report, endedAt);
		}

	};

	private final ScriptedChatModel model = new ScriptedChatModel();

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

	/**
	 * The tables' line facts reach the check at run time (T3 advisory R3-loop-facts-wiring-unproved): the cause's own
	 * changed line is stored at L3, every check passing, with the level written {@code L0} to {@code L3}.
	 */
	@Test
	void aTablesRunCitingTheCausesChangedLineIsStoredAtL3AfterTheModelsFieldsAndBeforeStepsUsed() {
		Clock clock = fixedClock();
		int cause = changedLine(tables(clock), "payment-service");
		this.model.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(cause)));
		this.loop = loop(AgentMode.DECISION, DossierView.TABLES, DecisionReport.PLAIN, lab(), clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(JSON.readTree(this.completed.get()).propertyNames()).containsExactlyElementsOf(PLAIN_ORDER);
		JsonNode verification = storedReport().get("verification");
		assertThat(verification.get("level").asString()).isEqualTo("L3");
		assertThat(verification.get("checks")).extracting(check -> check.get("name").asString())
			.containsExactlyElementsOf(PLAIN_CHECKS);
		assertThat(verification.get("checks")).extracting(check -> check.get("level").asString())
			.containsExactly("L0", "L1", "L2", "L3");
		assertThat(verification.get("checks")).allSatisfy(check -> {
			assertThat(check.propertyNames()).containsExactlyInAnyOrder("level", "name", "pass", "detail");
			assertThat(check.get("pass").asBoolean()).isTrue();
			assertThat(check.get("detail").isNull()).isTrue();
		});
	}

	/** A caller's changed line passes L2 and fails L3 alone, so the run is stored at L2 (R3-loop-facts-wiring-unproved). */
	@Test
	void aTablesRunCitingOnlyACallersLineIsStoredAtL2() {
		Clock clock = fixedClock();
		int caller = changedLine(tables(clock), "order-service");
		this.model.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(caller)));
		this.loop = loop(AgentMode.DECISION, DossierView.TABLES, DecisionReport.PLAIN, lab(), clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		JsonNode verification = storedReport().get("verification");
		assertThat(verification.get("level").asString()).isEqualTo("L2");
		assertThat(check(verification, "owners-on-path").get("pass").asBoolean()).isTrue();
		JsonNode l3 = check(verification, "cause-fact-changed");
		assertThat(l3.get("pass").asBoolean()).isFalse();
		assertThat(l3.get("detail").asString()).contains("none of its lines is cited");
	}

	/** An L2 failure is an annotation (AC3): the report is stored at L1 after exactly one model call, with no retry. */
	@Test
	void anL2FailureIsStoredWithNoRetry() {
		Clock clock = fixedClock();
		DossierRenderer.Indexed tables = tables(clock);
		int cause = changedLine(tables, "payment-service");
		int otherBranch = changedLine(tables, "inventory-service");
		this.model.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(cause),
				dossierEvidence(otherBranch)));
		this.loop = loop(AgentMode.DECISION, DossierView.TABLES, DecisionReport.PLAIN, lab(), clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(this.model.calls()).isEqualTo(1);
		JsonNode stored = storedReport();
		assertThat(stored.get("evidence")).hasSize(2);
		JsonNode verification = stored.get("verification");
		assertThat(verification.get("level").asString()).isEqualTo("L1");
		JsonNode l2 = check(verification, "owners-on-path");
		assertThat(l2.get("pass").asBoolean()).isFalse();
		assertThat(l2.get("detail").asString()).isEqualTo("D" + otherBranch
				+ " is a line of inventory-service, not of payment-service or a service on its call path");
		assertThat(check(verification, "cause-fact-changed").get("pass").asBoolean()).isTrue();
	}

	/** The sheets' line facts reach the plain report's check too (R3-loop-facts-wiring-unproved). */
	@Test
	void aSheetsRunCitingTheCausesChangedLineIsStoredAtL3() {
		Clock clock = fixedClock();
		int cause = changedLine(sheets(clock).facts(), "payment-service");
		this.model.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(cause)));
		this.loop = loop(AgentMode.DECISION, DossierView.SHEETS, DecisionReport.PLAIN, lab(), clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(storedReport().at("/verification/level").asString()).isEqualTo("L3");
	}

	/**
	 * The assessed report reads the sheets' facts through the ORIGIN's derived evidence: the ORIGIN citing its changed
	 * line is stored at L3 with the graph rules as L2 checks, after its assessments and the model's fields.
	 */
	@Test
	void anAssessedRunWhoseOriginCitesItsChangedLineIsStoredAtL3() {
		Clock clock = fixedClock();
		ServiceSheets.Rendered sheets = sheets(clock);
		String[] assessments = LAB.leafFirst().stream()
			.map(service -> service.equals("payment-service")
					? assessment(service, "ORIGIN", changedLine(sheets.facts(), service))
					: assessment(service, "UNAFFECTED", sheets.owners().indexOf(service) + 1))
			.toArray(String[]::new);
		this.model.text(assessedReport("payment-service", "DEPENDENCY_REJECTING", assessments));
		this.loop = loop(AgentMode.DECISION, DossierView.SHEETS, DecisionReport.ASSESSED, lab(), clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(JSON.readTree(this.completed.get()).propertyNames()).containsExactly("incidentId", "mode", "route",
				"model", "assessments", "summary", "evidence", "rootCauseService", "rootCauseCategory", "confidence",
				"verification", "stepsUsed");
		JsonNode verification = storedReport().get("verification");
		assertThat(verification.get("level").asString()).isEqualTo("L3");
		assertThat(verification.get("checks")).extracting(check -> check.get("name").asString())
			.containsExactly("schema", "citations", "owners-on-path", "one-origin", "graph", "answer",
					"cause-fact-changed");
	}

	/**
	 * A hybrid run whose rules proposed no candidate (T3 advisory R3-without-evidence-level-unproved): no model call, and
	 * its report, both causes UNKNOWN with no evidence, is stored at L1, its L2 and L3 checks not applicable.
	 */
	@Test
	void aHybridRunWithNoCandidateIsStoredAtL1() {
		this.loop = loop(AgentMode.DECISION, DossierView.TABLES, DecisionReport.HYBRID, noCandidateLab(), fixedClock());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(this.model.calls()).isZero();
		assertThat(JSON.readTree(this.completed.get()).propertyNames()).containsExactly("incidentId", "mode", "route",
				"model", "summary", "evidence", "rootCauseService", "rootCauseCategory", "confidence", "verification",
				"stepsUsed");
		JsonNode verification = storedReport().get("verification");
		assertThat(verification.get("level").asString()).isEqualTo("L1");
		assertThat(verification.get("checks")).extracting(check -> check.get("name").asString())
			.containsExactlyElementsOf(PLAIN_CHECKS);
		assertThat(verification.get("checks")).extracting(check -> check.get("pass").isNull())
			.containsExactly(false, false, true, true);
		assertThat(check(verification, "cause-fact-changed").get("detail").asString())
			.isEqualTo("both causes are UNKNOWN: the report names no cause to check");
	}

	/** A dossier-mode run is stored with its level too: a dropped item gives L0, its detail naming the item. */
	@Test
	void aDossierRunWithADroppedItemIsStoredAtL0() {
		Clock clock = fixedClock();
		int cause = changedLine(tables(clock), "payment-service");
		this.model.quiet()
			.quiet()
			.text(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence(cause), dossierEvidence(999)));
		this.loop = loop(AgentMode.DOSSIER, DossierView.TABLES, DecisionReport.PLAIN, lab(), clock);

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.COMPLETED);

		assertThat(JSON.readTree(this.completed.get()).propertyNames()).containsExactlyElementsOf(PLAIN_ORDER);
		JsonNode verification = storedReport().get("verification");
		assertThat(verification.get("level").asString()).isEqualTo("L0");
		JsonNode citations = check(verification, "citations");
		assertThat(citations.get("pass").asBoolean()).isFalse();
		assertThat(citations.get("detail").asString()).isEqualTo("evidence[1]: line 999 is not in the dossier");
		// The L2 and L3 checks still run on the stored item.
		assertThat(check(verification, "cause-fact-changed").get("pass").asBoolean()).isTrue();
	}

	/** A run ending INVALID_REPORT stores no report, so no level. */
	@Test
	void anInvalidReportRunStoresNoVerification() {
		this.model.text("not a report").text("still not a report");
		this.loop = loop(AgentMode.DECISION, DossierView.TABLES, DecisionReport.PLAIN, lab(), fixedClock());

		assertThat(this.loop.run(this.id)).isEqualTo(IncidentStatus.INVALID_REPORT);

		assertThat(this.completed.get()).isNull();
		assertThat(AgentDatabase.admin()
			.sql("SELECT count(*) FROM incidents WHERE id = ? AND report IS NOT NULL")
			.param(this.id)
			.query(Integer.class)
			.single()).isZero();
	}

	/** The tables a run of {@code lab()} writes on {@code clock}, with the fact of each line. */
	private static DossierRenderer.Indexed tables(Clock clock) {
		return DossierRenderer.indexed(new DossierGatherer(lab(), clock, LAB).gather(15), LAB);
	}

	private static int changedLine(DossierRenderer.Indexed tables, String owner) {
		return changedLine(tables.facts(), owner);
	}

	/** The sheets a run of {@code lab()} writes on {@code clock}, with the fact of each line. */
	private static ServiceSheets.Rendered sheets(Clock clock) {
		return ServiceSheets.render(new DossierGatherer(lab(), clock, LAB).gather(15), LAB);
	}

	/** The number of the first line {@code owner} owns whose fact changed between the windows. */
	private static int changedLine(List<LineFact> facts, String owner) {
		for (int i = 0; i < facts.size(); i++) {
			if (facts.get(i).owner().equals(owner) && facts.get(i).changed() == Change.YES) {
				return i + 1;
			}
		}
		throw new AssertionError("no changed line of " + owner);
	}

	private static JsonNode check(JsonNode verification, String name) {
		for (JsonNode check : verification.get("checks")) {
			if (check.get("name").asString().equals(name)) {
				return check;
			}
		}
		throw new AssertionError("no check " + name + " in " + verification);
	}

	private AgentLoop loop(AgentMode mode, DossierView view, DecisionReport report, FakeLabTools lab, Clock clock) {
		CallTimeouts timeouts = new CallTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(5));
		return new AgentLoop(this.incidents,
				task -> new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, timeouts, CAPS), lab,
				new LoopSettings(12, Duration.ofMinutes(30), Duration.ofSeconds(5)), clock, LAB, mode, view, report);
	}

	private JsonNode storedReport() {
		return JSON.readTree(AgentDatabase.admin()
			.sql("SELECT report::text FROM incidents WHERE id = ?")
			.param(this.id)
			.query(String.class)
			.single());
	}

}
