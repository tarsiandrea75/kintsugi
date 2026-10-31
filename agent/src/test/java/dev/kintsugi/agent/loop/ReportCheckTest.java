package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static dev.kintsugi.agent.loop.FakeLabTools.fixture;
import static dev.kintsugi.agent.loop.ScriptedChatModel.assessedReport;
import static dev.kintsugi.agent.loop.ScriptedChatModel.assessment;
import static dev.kintsugi.agent.loop.ScriptedChatModel.dossierEvidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.evidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.report;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.dossier.DossierRenderer;
import dev.kintsugi.agent.dossier.ServiceSheets;
import dev.kintsugi.agent.lab.ToolOutcome;
import dev.kintsugi.agent.rules.RulesCandidate;
import dev.kintsugi.agent.summary.ToolSummaries;

/**
 * The report's schema and evidence rules, without a run (agent spec, "The report", "Evidence is checked"), and those of
 * the assessed report, whose evidence code derives from the assessments (service-assessment T2).
 */
class ReportCheckTest {

	/** This incident's tool steps, each with the numbered summary the model was shown (evidence-by-reference). */
	private static final Map<Integer, ToolStep> STEPS = Map.of( //
			3, ran(3, "status_summary", "status_summary-orders.json"), //
			4, ran(4, "search_logs", "search_logs-order.json"), //
			5, ran(5, "recent_events", "recent_events-order.json"), //
			6, new ToolStep(6, "search_logs", "REFUSED_PHASE", null,
					"toolCallId=6 tool=search_logs outcome=REFUSED_PHASE\n1: the call was refused and did not run: x"), //
			7, new ToolStep(7, "recent_events", "OK", "{}", "toolCallId=7 tool=recent_events outcome=OK returned=20 "
					+ "truncated=false\n1: a\n2: b\n3: c\n4: d\n5: e\n6: the last kept line\n[summary cut at 1500 characters]"), //
			8, new ToolStep(8, "search_logs", "OK", "{}",
					"toolCallId=8 tool=search_logs outcome=OK returned=1 truncated=false\n1: " + "\uD83D\uDE00".repeat(200)));

	/** A rendered dossier, the golden F3 one (T4b), as the dossier message carries it after its introduction. */
	private static final String DOSSIER = golden();

	/**
	 * Sheets as {@code ServiceSheets} writes them, cut down to two lines per service, with the owner of every numbered
	 * line (service-assessment T2): D1 is the header's, D2 to D9 two per service in sheet order, and D10 is
	 * payment-service's in the last section.
	 */
	private static final ServiceSheets.Rendered SHEETS = new ServiceSheets.Rendered("""
			# Evidence dossier, by service

			D1 - Incident window: the last 10 min, 2026-10-16T09:50:00Z to 2026-10-16T10:00:00Z.

			## gateway-mock

			D2 - calls: none; called by: payment-service.
			D3 - latency /authorize: incident mean 8.898 s, max 10.015 s (previous mean 0.012 s).

			## inventory-service

			D4 - calls: none; called by: order-service.
			D5 - inbound POST /reservations status=201 outcome=SUCCESS: incident 6 (previous 4).

			## payment-service

			D6 - calls: gateway-mock; called by: order-service.
			D7 - latency /payments: incident mean 8.917 s, max 10.033 s (previous mean 0.045 s).

			## order-service

			D8 - calls: inventory-service, payment-service; called by: external client.
			D9 - outbound to payment-service status=CLIENT_ERROR outcome=UNKNOWN exception=HttpTimeoutException: \
			incident 6 (previous 0).

			## Seen only in the previous window

			### payment-service

			D10 - payments table status=AUTHORIZED: incident 0 (previous 4).
			""", List.of(ServiceSheets.HEADER, "gateway-mock", "gateway-mock", "inventory-service", "inventory-service",
			"payment-service", "payment-service", "order-service", "order-service", "payment-service"));

	/** The assessments of a slow leaf: gateway-mock the origin, its callers affected, inventory-service unaffected. */
	private static final String[] SLOW_LEAF = { assessment("gateway-mock", "ORIGIN", 3),
			assessment("inventory-service", "UNAFFECTED", 5), assessment("payment-service", "AFFECTED", 7),
			assessment("order-service", "AFFECTED", 9) };

	@Test
	void anExistingLineIsVerifiedAndItsQuoteIsThatLineWithoutItsNumber() {
		// Step 4's line 2 holds the newest group, "Completed initialization in 2 ms".
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY", evidence(4, "search_logs", 2)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).isEmpty();
		assertThat(result.evidence()).singleElement().satisfies(item -> {
			assertThat(item.propertyNames()).containsExactly("toolCallId", "tool", "line", "quote", "why");
			assertThat(item.get("toolCallId").intValue()).isEqualTo(4);
			assertThat(item.get("tool").asString()).isEqualTo("search_logs");
			assertThat(item.get("line").intValue()).isEqualTo(2);
			assertThat(item.get("quote").asString()).isEqualTo(ToolSummaries.line(STEPS.get(4).summary(), 2).orElseThrow())
				.isEqualTo("count=1 newest=\"2026-10-08T16:32:03.396024993Z\" untrustedText=\"Completed initialization in 2 ms\"");
			assertThat(item.get("why").asString()).isEqualTo("it shows the cause");
		});
	}

	@Test
	void aLineTheSummaryReshapedOrComputedIsCitable() {
		// status="PAYMENT_PENDING" count=7 is not in the raw output, and events=20 is computed: both are lines the
		// model was shown, built from the output by code.
		assertThat(STEPS.get(3).output()).doesNotContain("status=\"PAYMENT_PENDING\" count=7");
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", 3), evidence(5, "recent_events", 1)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.evidence()).extracting(item -> item.get("quote").asString())
			.containsExactly("status=\"PAYMENT_PENDING\" count=7", "events=20");
	}

	@Test
	void oneLineOutOfRangeAmongVerifiedOnesIsDroppedAndRecorded() {
		// T5c11: one item that does not hold up no longer costs a valid report. Step 4's summary has 6 numbered lines.
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "status_summary", 3),
				evidence(4, "search_logs", 9), evidence(5, "recent_events", 4)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1]: line 9 is not in the output of step 4");
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").intValue()).containsExactly(3, 5);
	}

	@Test
	void aReportNoneOfWhoseLinesExistsIsRejectedNamingEachItem() {
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "status_summary", 5),
				evidence(4, "search_logs", 7)));

		assertThat(result.errors()).containsExactly("evidence[0]: line 5 is not in the output of step 3",
				"evidence[1]: line 7 is not in the output of step 4");
		assertThat(result.evidence()).isNull();
	}

	@Test
	void anUnknownReportStaysValidWhenItsOnlyLineIsDropped() {
		ReportCheck.Result result = check(report("UNKNOWN", "UNKNOWN", evidence(4, "search_logs", 7)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[0]: line 7 is not in the output of step 4");
		assertThat(result.evidence()).isEmpty();
	}

	@Test
	void theHeaderAndTheCutNoteAreNotLines() {
		// Step 7's summary is its header, 6 numbered lines and the cut note: neither of those two has a number.
		ReportCheck.Result result = check(report("payment-service", "DEPENDENCY_LATENCY", evidence(7, "recent_events", 6),
				evidence(7, "recent_events", 7)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1]: line 7 is not in the output of step 7");
		assertThat(result.evidence()).singleElement()
			.satisfies(item -> assertThat(item.get("quote").asString()).isEqualTo("the last kept line"));
	}

	@Test
	void theQuoteIsTheFirst160CharactersOfALongLine() {
		// Step 5's line 8 is the newest event, with its untrustedText, well over 160 characters.
		String line = ToolSummaries.line(STEPS.get(5).summary(), 8).orElseThrow();
		assertThat(line).hasSizeGreaterThan(200);

		ReportCheck.Result result = check(report("payment-service", "DEPENDENCY_LATENCY", evidence(5, "recent_events", 8)));

		assertThat(result.evidence()).singleElement()
			.satisfies(item -> assertThat(item.get("quote").asString()).isEqualTo(line.substring(0, 160)));
	}

	@Test
	void theQuoteCapCountsCharactersAsTheSchemaDoesAndNeverSplitsOne() {
		// Code points, as Causes.string counts them: 160 emoji, never a lone surrogate.
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY", evidence(8, "search_logs", 1)));

		String quote = result.evidence().get(0).get("quote").asString();
		assertThat(quote).isEqualTo("\uD83D\uDE00".repeat(160));
	}

	@Test
	void aQuoteWrittenByTheModelFailsTheSchemaAndIsNeverDropped() {
		String withQuote = "{\"toolCallId\":3,\"tool\":\"status_summary\",\"line\":3,\"quote\":\"PAYMENT_PENDING\","
				+ "\"why\":\"\"}";
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY", evidence(4, "search_logs", 2), withQuote)))
			.containsExactly("evidence[1].unexpected field \"quote\"; allowed: line, tool, toolCallId, why");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1", "1.5", "\"2\"", "\"D7\"", "null" })
	void aLineThatIsNotAnIntegerOfAtLeastOneFailsTheSchema(String line) {
		String item = "{\"toolCallId\":3,\"tool\":\"status_summary\",\"line\":" + line + ",\"why\":\"\"}";
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY", evidence(4, "search_logs", 2), item)))
			.containsExactly("evidence[1].line must be an integer of at least 1");
		assertThat(checkInDossierMode(report("order-service", "DEPENDENCY_LATENCY", evidence(4, "search_logs", 2),
				item.replace("3,\"tool\":\"status_summary\"", "\"dossier\",\"tool\":\"dossier\""))).errors())
			.containsExactly("evidence[1].line must be an integer of at least 1");
	}

	@Test
	void aMissingLineFailsTheSchema() {
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY",
				"{\"toolCallId\":3,\"tool\":\"status_summary\",\"why\":\"\"}")))
			.containsExactly("evidence[0].line must be an integer of at least 1");
	}

	@Test
	void aCitedRefusedCallStillRejectsTheReportAndTheRetryNamesTheBadLineToo() {
		// Only a line that is not in its step, or a wrong tool (T5c14), is dropped; a refused or missing step still
		// rejects the report.
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "status_summary", 3),
				evidence(6, "search_logs", 1), evidence(4, "search_logs", 9)));

		assertThat(result.errors()).containsExactly("evidence[1].toolCallId 6 was refused (REFUSED_PHASE) and did not run",
				"evidence[2]: line 9 is not in the output of step 4");
		assertThat(result.evidence()).isNull();
	}

	@Test
	void anItemCitingTheWrongToolAmongVerifiedItemsIsDroppedAndRecorded() {
		// The twelfth live run, F2: the report named the true cause but cited a recent_events step as search_runbooks (T5c14).
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "status_summary", 3),
				evidence(4, "search_logs", 2), evidence(5, "search_runbooks", 8)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[2].tool is search_runbooks, but step 5 is recent_events");
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").intValue()).containsExactly(3, 4);
	}

	@Test
	void aReportWhoseEveryItemCitesTheWrongToolIsRejectedNamingEachItem() {
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "search_logs", 3),
				evidence(5, "search_runbooks", 8)));

		assertThat(result.errors()).containsExactly("evidence[0].tool is search_logs, but step 3 is status_summary",
				"evidence[1].tool is search_runbooks, but step 5 is recent_events");
		assertThat(result.dropped()).isEmpty();
		assertThat(result.evidence()).isNull();
	}

	@Test
	void aMissingStepOrARefusedCallIsRejectedAndTheRetryNamesAWrongToolToo() {
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "search_logs", 3),
				evidence(9, "search_logs", 1), evidence(6, "search_logs", 1))))
			.containsExactly("evidence[0].tool is search_logs, but step 3 is status_summary",
					"evidence[1].toolCallId 9 is not a tool call of this incident",
					"evidence[2].toolCallId 6 was refused (REFUSED_PHASE) and did not run");
	}

	@Test
	void aValueOutsideTheEnumsNamesTheFieldAndItsValues() {
		assertThat(errors(report("payment-gateway", "DEPENDENCY_LATENCY", evidence(3, "status_summary", 3))))
			.containsExactly("rootCauseService must be one of gateway-mock, payment-service, order-service, "
					+ "inventory-service, postgres, UNKNOWN; got \"payment-gateway\"");
	}

	@Test
	void unknownWithNoEvidenceIsValidButANamedCauseNeedsEvidence() {
		assertThat(errors(report("UNKNOWN", "UNKNOWN"))).isEmpty();
		assertThat(errors(report("order-service", "UNKNOWN")))
			.containsExactly("evidence must hold 1 to 5 items unless both causes are UNKNOWN");
	}

	@ParameterizedTest
	@ValueSource(strings = { "not json", "[]", "{}",
			"{\"rootCauseService\":\"UNKNOWN\",\"rootCauseCategory\":\"UNKNOWN\",\"summary\":\"x\",\"confidence\":2,\"evidence\":[]}",
			"{\"rootCauseService\":\"UNKNOWN\",\"rootCauseCategory\":\"UNKNOWN\",\"summary\":\"\",\"confidence\":0.5,\"evidence\":[]}",
			"{\"rootCauseService\":\"UNKNOWN\",\"rootCauseCategory\":\"UNKNOWN\",\"summary\":\"x\",\"confidence\":0.5,\"evidence\":[],\"stepsUsed\":3}",
			"{\"rootCauseService\":\"UNKNOWN\",\"rootCauseCategory\":\"UNKNOWN\",\"summary\":\"x\",\"confidence\":\"high\",\"evidence\":[]}" })
	void aReportOutsideTheSchemaIsRejected(String answer) {
		assertThat(errors(answer)).isNotEmpty();
	}

	@Test
	void theSchemaSentToTheModelAsksForALineOfAtLeastOneAndNoQuote() {
		for (AgentMode mode : AgentMode.values()) {
			JsonNode item = JsonMapper.shared().readTree(Schemas.report(mode)).at("/properties/evidence/items");
			assertThat(item.get("properties").propertyNames()).as(mode.value())
				.containsExactly("toolCallId", "tool", "line", "why");
			assertThat(item.get("required")).extracting(JsonNode::asString)
				.containsExactly("toolCallId", "tool", "line", "why");
			assertThat(item.at("/properties/line/type").asString()).isEqualTo("integer");
			assertThat(item.at("/properties/line/minimum").intValue()).isEqualTo(1);
			assertThat(item.get("additionalProperties").booleanValue()).isFalse();
		}
	}

	@Test
	void aJsonAnswerInACodeFenceIsRead() {
		assertThat(errors("```json\n" + report("UNKNOWN", "UNKNOWN") + "\n```")).isEmpty();
	}

	@Test
	void aDossierLineIsVerifiedAndItsQuoteIsThatLineWithoutItsNumber() {
		// T5b, evidence-by-reference: D17 is written as line 17 with toolCallId and tool "dossier".
		assertThat(DOSSIER).contains("\nD17 | client | CLIENT_ERROR | UNKNOWN | ConnectException | 0 | 6 |\n");
		ReportCheck.Result result = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				dossierEvidence(17), dossierEvidence(1)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).isEmpty();
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").asString())
			.containsExactly("dossier", "dossier");
		assertThat(result.evidence()).extracting(item -> item.get("quote").asString())
			.containsExactly("| client | CLIENT_ERROR | UNKNOWN | ConnectException | 0 | 6 |",
					"- Incident window: the last 10 min, 2026-10-16T09:50:00Z to 2026-10-16T10:00:00Z.");
		assertThat(result.evidence().get(0).propertyNames()).containsExactly("toolCallId", "tool", "line", "quote", "why");
	}

	@Test
	void aDossierLineThatDoesNotExistIsDropped() {
		// The golden F3 dossier numbers its lines D1 to D49.
		assertThat(DOSSIER).contains("\nD49 ").doesNotContain("\nD50 ");
		ReportCheck.Result result = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", 3), dossierEvidence(50)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1]: line 50 is not in the dossier");
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").asString()).containsExactly("3");
	}

	@Test
	void aDossierCitationNamingAnotherToolIsDroppedLikeAWrongTool() {
		ReportCheck.Result result = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", 3), dossierEvidence("traffic_counts", 17)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped())
			.containsExactly("evidence[1].tool is traffic_counts, but toolCallId dossier is the dossier");
	}

	@Test
	void inExplorerModeADossierCitationIsDroppedAsCitingNoSource() {
		ReportCheck.Result result = check(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", 3), dossierEvidence(17)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1].toolCallId dossier: this run has no dossier");
		assertThat(result.evidence()).hasSize(1);
	}

	@Test
	void aDossierModeRunWhoseDossierWasNotGatheredDropsADossierCitation() {
		ReportCheck.Result result = ReportCheck.check(Answers.json(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", 3), dossierEvidence(17))), STEPS, AgentMode.DOSSIER, null);

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1].toolCallId dossier: the dossier was not gathered");
	}

	@Test
	void dossierAndToolCitationsMixAndKeepTheirOrder() {
		ReportCheck.Result result = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(5, "recent_events", 8), dossierEvidence(17), evidence(3, "status_summary", 3)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").asString())
			.containsExactly("5", "dossier", "3");
	}

	@Test
	void aReportWhoseOnlyEvidenceIsADroppedDossierItemFollowsTheNoEvidenceLeftRule() {
		ReportCheck.Result rejected = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				dossierEvidence(50)));
		assertThat(rejected.errors()).containsExactly("evidence[0]: line 50 is not in the dossier");
		assertThat(rejected.evidence()).isNull();

		ReportCheck.Result unknown = checkInDossierMode(report("UNKNOWN", "UNKNOWN", dossierEvidence(50)));
		assertThat(unknown.errors()).isEmpty();
		assertThat(unknown.evidence()).isEmpty();
	}

	@Test
	void decisionModeChecksAndConstrainsAReportAsDossierModeDoes() {
		ReportCheck.Result result = ReportCheck.check(Answers.json(report("payment-service", "DEPENDENCY_REJECTING",
				dossierEvidence(17))), STEPS, AgentMode.DECISION, DOSSIER);

		assertThat(result.errors()).isEmpty();
		assertThat(result.evidence()).extracting(item -> item.get("quote").asString())
			.containsExactly("| client | CLIENT_ERROR | UNKNOWN | ConnectException | 0 | 6 |");
		String other = "{\"toolCallId\":\"step3\",\"tool\":\"status_summary\",\"line\":3,\"why\":\"\"}";
		assertThat(ReportCheck.check(Answers.json(report("payment-service", "DEPENDENCY_REJECTING", other)), STEPS,
				AgentMode.DECISION, DOSSIER).errors()).containsExactly("evidence[0].toolCallId must be an integer or \"dossier\"");
		assertThat(Schemas.report(AgentMode.DECISION)).isEqualTo(Schemas.report(AgentMode.DOSSIER));
	}

	@Test
	void aDossierCitationKeepsTheOtherRules() {
		// A toolCallId that is neither an integer nor "dossier" is still rejected.
		String other = "{\"toolCallId\":\"step3\",\"tool\":\"status_summary\",\"line\":3,\"why\":\"\"}";
		assertThat(checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING", other)).errors())
			.containsExactly("evidence[0].toolCallId must be an integer or \"dossier\"");
		assertThat(errors(report("payment-service", "DEPENDENCY_REJECTING", other)))
			.containsExactly("evidence[0].toolCallId must be an integer");
	}

	@Test
	void aSummaryTooLongIsCutAt500CharactersAndTheReportStaysValid() {
		// report-field-order: with the reasoning first, cloud summaries ran 1-25% past 500 characters, also when the
		// retry was told the length. The summary is reasoning, not evidence: code cuts it instead of rejecting it.
		String answer = report("order-service", "CONFIGURATION", evidence(4, "search_logs", 2))
			.replace("what the evidence shows", "😀".repeat(612));

		ReportCheck.Result result = check(answer);

		assertThat(result.errors()).isEmpty();
		assertThat(result.summary()).isEqualTo("😀".repeat(499) + "…");
		assertThat(result.dropped()).containsExactly("summary cut from 612 to 500 characters");
	}

	@Test
	void aSummaryWithinTheLimitIsKeptWhole() {
		ReportCheck.Result result = check(report("order-service", "CONFIGURATION", evidence(4, "search_logs", 2)));

		assertThat(result.summary()).isEqualTo("what the evidence shows");
		assertThat(result.dropped()).isEmpty();
	}

	@Test
	void aSummaryOf500CharactersIsKeptAndOf501IsCut() {
		String base = report("order-service", "CONFIGURATION", evidence(4, "search_logs", 2));

		assertThat(check(base.replace("what the evidence shows", "b".repeat(500))).summary()).isEqualTo("b".repeat(500));
		assertThat(check(base.replace("what the evidence shows", "b".repeat(501))).summary())
			.isEqualTo("b".repeat(499) + "…");
	}

	@Test
	void anEmptySummaryIsStillAnError() {
		String answer = report("order-service", "CONFIGURATION", evidence(4, "search_logs", 2))
			.replace("what the evidence shows", "");

		assertThat(errors(answer)).contains("summary must be a string of 1 to 500 characters");
	}

	@Test
	void theSchemaPutsTheReasoningBeforeTheCause() {
		// report-field-order: the model writes left to right, so the summary and the evidence come before the cause
		// they lead to.
		List<String> order = List.of("summary", "evidence", "rootCauseService", "rootCauseCategory", "confidence");
		for (AgentMode mode : AgentMode.values()) {
			JsonNode report = JsonMapper.shared().readTree(Schemas.report(mode));
			assertThat(report.get("properties").propertyNames()).as(mode.value()).containsExactlyElementsOf(order);
			assertThat(report.get("required")).extracting(JsonNode::asString).containsExactlyElementsOf(order);
		}
		assertThat(ReportCheck.MODEL_FIELDS).containsExactlyElementsOf(order);
	}

	@Test
	void onlyTheDossierModeSchemaLetsAToolCallIdBeDossier() {
		JsonNode explorer = JsonMapper.shared().readTree(Schemas.report(AgentMode.EXPLORER))
			.at("/properties/evidence/items/properties/toolCallId");
		assertThat(Schemas.report(AgentMode.EXPLORER)).isEqualTo(Schemas.REPORT);
		assertThat(explorer.get("type").asString()).isEqualTo("integer");
		JsonNode dossier = JsonMapper.shared().readTree(Schemas.report(AgentMode.DOSSIER))
			.at("/properties/evidence/items/properties/toolCallId/anyOf");
		assertThat(dossier.get(0).get("type").asString()).isEqualTo("integer");
		assertThat(dossier.get(1).get("enum").get(0).asString()).isEqualTo("dossier");
	}

	@Test
	void anAssessedReportIsValidAndCodeDerivesItsEvidenceFromTheOriginAndTheAffectedServices() {
		ReportCheck.Result result = checkAssessed(assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).isEmpty();
		assertThat(result.summary()).isEqualTo("what the sheets show");
		// The ORIGIN and AFFECTED lines, in sheet order, each cited as the dossier is and quoted by code; the
		// UNAFFECTED service's line is no evidence.
		assertThat(result.evidence()).extracting(item -> item.get("line").intValue()).containsExactly(3, 7, 9);
		assertThat(result.evidence()).allSatisfy(item -> {
			assertThat(item.propertyNames()).containsExactly("toolCallId", "tool", "line", "quote", "why");
			assertThat(item.get("toolCallId").asString()).isEqualTo(ReportCheck.DOSSIER);
			assertThat(item.get("tool").asString()).isEqualTo(ReportCheck.DOSSIER);
			assertThat(item.get("quote").asString())
				.isEqualTo(DossierRenderer.line(SHEETS.text(), item.get("line").intValue()).orElseThrow());
			assertThat(item.get("why").asString()).isEqualTo("its own facts");
		});
		assertThat(result.evidence().get(0).get("quote").asString())
			.isEqualTo("- latency /authorize: incident mean 8.898 s, max 10.015 s (previous mean 0.012 s).");
	}

	@Test
	void anAssessedReportWhoseEveryServiceIsUnaffectedHasNoEvidence() {
		ReportCheck.Result result = checkAssessed(assessedReport("UNKNOWN", "UNKNOWN",
				assessment("gateway-mock", "UNAFFECTED", 2), assessment("inventory-service", "UNAFFECTED", 4),
				assessment("payment-service", "UNAFFECTED", 6), assessment("order-service", "UNAFFECTED", 8)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.evidence()).isEmpty();
	}

	@Test
	void coverageEveryServiceIsAssessedAndNoOther() {
		String missing = assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF[0], SLOW_LEAF[1],
				SLOW_LEAF[2]);
		assertThat(checkAssessed(missing).errors())
			.containsExactly("assessments.order-service is missing: assess every service once");
		String other = assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF[0], SLOW_LEAF[1], SLOW_LEAF[2],
				SLOW_LEAF[3], assessment("postgres", "UNAFFECTED", 1));
		assertThat(checkAssessed(other).errors()).containsExactly("assessments.unexpected field \"postgres\"; allowed: "
				+ "gateway-mock, inventory-service, order-service, payment-service");
		assertThat(checkAssessed(missing).evidence()).isNull();
	}

	@Test
	void citationEachLineIsOneOfItsOwnServicesLines() {
		// payment-service cites gateway-mock's latency line, then a header line, then a line the sheets do not have.
		for (Map.Entry<Integer, String> cited : Map.of(3, "is a line of gateway-mock, not of payment-service", 1,
				"is a line of the header, not of payment-service", 11, "is not in the sheets").entrySet()) {
			String answer = assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF[0], SLOW_LEAF[1],
					assessment("payment-service", "AFFECTED", cited.getKey()), SLOW_LEAF[3]);

			ReportCheck.Result result = checkAssessed(answer);

			assertThat(result.errors()).as("line %d", cited.getKey())
				.containsExactly("assessments.payment-service.line " + cited.getKey() + " " + cited.getValue()
						+ "; cite a line of its own sheet or of its part of \"Seen only in the previous window\"");
			assertThat(result.evidence()).isNull();
		}
		// Its part of the last section is its own.
		String previousOnly = assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF[0], SLOW_LEAF[1],
				assessment("payment-service", "AFFECTED", 10), SLOW_LEAF[3]);
		assertThat(checkAssessed(previousOnly).errors()).isEmpty();
	}

	@Test
	void oneOriginAtMostOneServiceIsTheOrigin() {
		String two = assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF[0], SLOW_LEAF[1],
				assessment("payment-service", "ORIGIN", 7), SLOW_LEAF[3]);

		ReportCheck.Result result = checkAssessed(two);

		assertThat(result.errors()).containsExactly(
				"at most one service is the ORIGIN; got 2: gateway-mock, payment-service");
		assertThat(result.evidence()).isNull();
	}

	@Test
	void oneOriginWithNoOriginEveryServiceIsUnaffectedAndBothCausesAreUnknown() {
		// An AFFECTED service with no ORIGIN: something is wrong, so the problem must start somewhere.
		String noOrigin = assessedReport("UNKNOWN", "UNKNOWN", assessment("gateway-mock", "UNAFFECTED", 2),
				SLOW_LEAF[1], SLOW_LEAF[2], SLOW_LEAF[3]);
		// Graph then names payment-service too: its only callee, gateway-mock, is UNAFFECTED.
		assertThat(checkAssessed(noOrigin).errors()).containsExactly(
				"no service is the ORIGIN, so every service must be UNAFFECTED; got AFFECTED: payment-service, "
						+ "order-service",
				"assessments.payment-service is AFFECTED but calls no ORIGIN or AFFECTED service (it calls "
						+ "gateway-mock: UNAFFECTED); a service is AFFECTED only through a service it calls that is "
						+ "ORIGIN or AFFECTED");
		// Every service UNAFFECTED, but a cause named: the cause is then UNKNOWN.
		String named = assessedReport("payment-service", "DEPENDENCY_LATENCY",
				assessment("gateway-mock", "UNAFFECTED", 2), assessment("inventory-service", "UNAFFECTED", 4),
				assessment("payment-service", "UNAFFECTED", 6), assessment("order-service", "UNAFFECTED", 8));
		assertThat(checkAssessed(named).errors()).containsExactly("no service is the ORIGIN, so rootCauseService "
				+ "and rootCauseCategory must both be UNKNOWN; got \"payment-service\" and \"DEPENDENCY_LATENCY\"");
	}

	@Test
	void graphAnAffectedServiceCallsAnOriginOrAffectedService() {
		// inventory-service calls no service, so nothing it calls can pass a problem on to it.
		String leaf = assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF[0],
				assessment("inventory-service", "AFFECTED", 5), SLOW_LEAF[2], SLOW_LEAF[3]);
		assertThat(checkAssessed(leaf).errors()).containsExactly("assessments.inventory-service is AFFECTED but "
				+ "calls no service; a service is AFFECTED only through a service it calls that is ORIGIN or "
				+ "AFFECTED");
		// payment-service calls gateway-mock, which is UNAFFECTED here.
		String unaffectedCallee = assessedReport("order-service", "CONFIGURATION",
				assessment("gateway-mock", "UNAFFECTED", 2), SLOW_LEAF[1], SLOW_LEAF[2],
				assessment("order-service", "ORIGIN", 9));
		assertThat(checkAssessed(unaffectedCallee).errors()).containsExactly("assessments.payment-service is "
				+ "AFFECTED but calls no ORIGIN or AFFECTED service (it calls gateway-mock: UNAFFECTED); a service "
				+ "is AFFECTED only through a service it calls that is ORIGIN or AFFECTED");
		// A caller of an AFFECTED service is AFFECTED through it: order-service through payment-service.
		assertThat(checkAssessed(assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF)).errors())
			.isEmpty();
	}

	@Test
	void answerTheRootCauseServiceIsTheOrigin() {
		String other = assessedReport("payment-service", "DEPENDENCY_LATENCY", SLOW_LEAF);

		ReportCheck.Result result = checkAssessed(other);

		assertThat(result.errors())
			.containsExactly("rootCauseService must be the ORIGIN, gateway-mock; got \"payment-service\"");
		assertThat(result.evidence()).isNull();
		// A service no assessment can name is not the ORIGIN either.
		assertThat(checkAssessed(assessedReport("postgres", "DEPENDENCY_LATENCY", SLOW_LEAF)).errors())
			.containsExactly("rootCauseService must be the ORIGIN, gateway-mock; got \"postgres\"");
	}

	@Test
	void theGraphRulesWaitForEveryServiceToBeAssessed() {
		// With a service missing, only Coverage is reported: the other rules read the whole graph.
		String missing = assessedReport("payment-service", "DEPENDENCY_LATENCY", SLOW_LEAF[1], SLOW_LEAF[2],
				SLOW_LEAF[3]);
		assertThat(checkAssessed(missing).errors())
			.containsExactly("assessments.gateway-mock is missing: assess every service once");
	}

	@Test
	void anAssessmentOutsideItsSchemaNamesItsField() {
		String answer = assessedReport("gateway-mock", "DEPENDENCY_LATENCY",
				"\"gateway-mock\":{\"role\":\"SLOW\",\"line\":0,\"why\":\"" + "w".repeat(201) + "\"}",
				"\"inventory-service\":{\"role\":\"UNAFFECTED\",\"line\":5,\"why\":\"\",\"quote\":\"x\"}",
				"\"payment-service\":\"AFFECTED\"", SLOW_LEAF[3]);

		assertThat(checkAssessed(answer).errors()).containsExactly(
				"assessments.gateway-mock.role must be one of ORIGIN, AFFECTED, UNAFFECTED; got \"SLOW\"",
				"assessments.gateway-mock.line must be an integer of at least 1",
				"assessments.gateway-mock.why must be a string of at most 200 characters",
				"assessments.inventory-service.unexpected field \"quote\"; allowed: line, role, why",
				"assessments.payment-service must be an object {role, line, why}");
	}

	@Test
	void anAssessmentLineMustBeAnIntegerInTheIntRange() {
		// A non-integral number, a numeric string and a value past the int range are each refused by the schema rule,
		// never truncated, parsed or wrapped into a line (service-assessment T2r, R3-line-edge-tests).
		for (String line : List.of("3.5", "\"3\"", "2147483648")) {
			String answer = assessedReport("gateway-mock", "DEPENDENCY_LATENCY",
					"\"gateway-mock\":{\"role\":\"ORIGIN\",\"line\":" + line + ",\"why\":\"its own facts\"}",
					SLOW_LEAF[1], SLOW_LEAF[2], SLOW_LEAF[3]);

			ReportCheck.Result result = checkAssessed(answer);

			assertThat(result.errors()).as(line)
				.containsExactly("assessments.gateway-mock.line must be an integer of at least 1");
			assertThat(result.evidence()).as(line).isNull();
		}
	}

	@Test
	void anOwnedLineWhoseTextIsNotInTheSheetsIsAnInvalidReportNotAFailure() {
		// The owners list D3 as gateway-mock's, but the text has no D3: the evidence cannot quote it, so the report is
		// rejected for the retry, with an exact error, instead of failing the run (service-assessment T2r,
		// R3-quote-orElseThrow).
		String text = SHEETS.text().lines().filter(line -> !line.startsWith("D3 ")).collect(joining("\n", "", "\n"));
		ServiceSheets.Rendered gap = new ServiceSheets.Rendered(text, SHEETS.owners());

		ReportCheck.Result result = ReportCheck.checkAssessed(
				Answers.json(assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF)), gap, LAB);

		assertThat(result.errors()).containsExactly("assessments.gateway-mock.line 3 is a line of gateway-mock whose "
				+ "text is not in the sheets; cite another line of its own sheet or of its part of \"Seen only in the "
				+ "previous window\"");
		assertThat(result.evidence()).isNull();
	}

	@Test
	void anAssessedReportOutsideItsSchemaIsRejected() {
		assertThat(checkAssessed("not json").errors()).containsExactly("the answer is not a JSON object");
		// The model writes no evidence: code derives it.
		String withEvidence = assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF)
			.replace("{\"assessments\"", "{\"evidence\":[],\"assessments\"");
		assertThat(checkAssessed(withEvidence).errors()).containsExactly("unexpected field \"evidence\"; allowed: "
				+ "assessments, confidence, rootCauseCategory, rootCauseService, summary");
		String noAssessments = "{\"summary\":\"x\",\"rootCauseService\":\"UNKNOWN\",\"rootCauseCategory\":\"UNKNOWN\","
				+ "\"confidence\":2}";
		assertThat(checkAssessed(noAssessments).errors()).containsExactly(
				"assessments must be an object with one {role, line, why} per service: gateway-mock, "
						+ "inventory-service, payment-service, order-service",
				"confidence must be a number from 0 to 1");
		String outsideTheEnums = assessedReport("gateway-mock", "SLOW", SLOW_LEAF)
			.replace("\"summary\":\"what the sheets show\",", "");
		assertThat(checkAssessed(outsideTheEnums).errors()).containsExactly(
				"rootCauseCategory must be one of DEPENDENCY_LATENCY, DEPENDENCY_REJECTING, CONFIGURATION, "
						+ "RESOURCE_EXHAUSTION, UNKNOWN; got \"SLOW\"",
				"summary must be a string of 1 to 500 characters");
	}

	@Test
	void anAssessedReportsSummaryTooLongIsCutAsThePlainReportsIs() {
		String answer = assessedReport("gateway-mock", "DEPENDENCY_LATENCY", SLOW_LEAF)
			.replace("what the sheets show", "b".repeat(612));

		ReportCheck.Result result = checkAssessed(answer);

		assertThat(result.errors()).isEmpty();
		assertThat(result.summary()).isEqualTo("b".repeat(499) + "…");
		assertThat(result.dropped()).containsExactly("summary cut from 612 to 500 characters");
	}

	@Test
	void theAssessedSchemaAsksForTheAssessmentsFirstOnePerServiceInSheetOrderAndNoEvidence() {
		JsonNode schema = JsonMapper.shared().readTree(Schemas.assessedReport(LAB));
		List<String> order = List.of("assessments", "summary", "rootCauseService", "rootCauseCategory", "confidence");

		assertThat(schema.get("properties").propertyNames()).containsExactlyElementsOf(order);
		assertThat(schema.get("required")).extracting(JsonNode::asString).containsExactlyElementsOf(order);
		assertThat(ReportCheck.ASSESSED_FIELDS).containsExactlyElementsOf(order);
		assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
		JsonNode assessments = schema.at("/properties/assessments");
		assertThat(assessments.get("properties").propertyNames()).containsExactlyElementsOf(LAB.leafFirst())
			.containsExactly("gateway-mock", "inventory-service", "payment-service", "order-service");
		assertThat(assessments.get("required")).extracting(JsonNode::asString)
			.containsExactlyElementsOf(LAB.leafFirst());
		assertThat(assessments.get("additionalProperties").booleanValue()).isFalse();
		for (String service : LAB.leafFirst()) {
			JsonNode assessment = assessments.get("properties").get(service);
			assertThat(assessment.get("properties").propertyNames()).as(service).containsExactly("role", "line", "why");
			assertThat(assessment.get("required")).extracting(JsonNode::asString).containsExactly("role", "line", "why");
			assertThat(assessment.get("additionalProperties").booleanValue()).isFalse();
			assertThat(assessment.at("/properties/role/enum")).extracting(JsonNode::asString)
				.containsExactly("ORIGIN", "AFFECTED", "UNAFFECTED");
			// The plain report's conventions: a line of at least 1, a why of at most 200 characters.
			assertThat(assessment.at("/properties/line")).isEqualTo(JsonMapper.shared().readTree(Schemas.REPORT)
				.at("/properties/evidence/items/properties/line"));
			assertThat(assessment.at("/properties/why")).isEqualTo(JsonMapper.shared().readTree(Schemas.REPORT)
				.at("/properties/evidence/items/properties/why"));
		}
		// The summary, the cause and the confidence as in the plain report.
		JsonNode plain = JsonMapper.shared().readTree(Schemas.report(AgentMode.DECISION)).get("properties");
		for (String field : List.of("summary", "rootCauseService", "rootCauseCategory", "confidence")) {
			assertThat(schema.at("/properties/" + field)).as(field).isEqualTo(plain.get(field));
		}
	}

	@Test
	void aHybridReportNamingAListedCandidateIsCheckedAsAPlainOne() {
		ReportCheck.Result result = checkHybrid(report("order-service", "CONFIGURATION", dossierEvidence(17)));

		assertThat(result.errors()).isEmpty();
		assertThat(result.evidence()).extracting(item -> item.get("quote").asString())
			.containsExactly("| client | CLIENT_ERROR | UNKNOWN | ConnectException | 0 | 6 |");
	}

	@Test
	void aHybridReportWithBothCausesUnknownIsValid() {
		ReportCheck.Result result = checkHybrid(report("UNKNOWN", "UNKNOWN"));

		assertThat(result.errors()).isEmpty();
		assertThat(result.evidence()).isEmpty();
	}

	@Test
	void aHybridReportNamingAPairOutsideTheListIsInvalidAndTheErrorNamesTheAllowedPairs() {
		// The service is listed, but with another category: the pair, not the service, must be listed.
		ReportCheck.Result result = checkHybrid(report("order-service", "DEPENDENCY_LATENCY", dossierEvidence(17)));

		assertThat(result.errors()).containsExactly(HYBRID_OUTSIDE.formatted("order-service|DEPENDENCY_LATENCY"));
		assertThat(result.evidence()).isNull();
	}

	@Test
	void aHybridReportBreakingAnotherRuleGetsBothErrorsForTheRetry() {
		ReportCheck.Result result = checkHybrid(report("postgres", "RESOURCE_EXHAUSTION"));

		assertThat(result.errors()).containsExactly("evidence must hold 1 to 5 items unless both causes are UNKNOWN",
				HYBRID_OUTSIDE.formatted("postgres|RESOURCE_EXHAUSTION"));
	}

	/** The candidates of the hybrid checks: two, in the order the rules propose them. */
	private static final List<RulesCandidate> CANDIDATES = List.of(
			new RulesCandidate("order-service", "CONFIGURATION", "R-REACH", List.of("a connection failure")),
			new RulesCandidate("payment-service", "DEPENDENCY_LATENCY", "R-DOWN", List.of("slow")));

	/** The membership error for those candidates, with the pair the report named. */
	private static final String HYBRID_OUTSIDE = "rootCauseService|rootCauseCategory %s is not a listed candidate: "
			+ "name one of order-service|CONFIGURATION, payment-service|DEPENDENCY_LATENCY, or UNKNOWN|UNKNOWN";

	private static ReportCheck.Result checkHybrid(String answer) {
		return ReportCheck.checkHybrid(Answers.json(answer), STEPS, AgentMode.DECISION, DOSSIER, CANDIDATES);
	}

	/** A step that ran, with its fixture's output and the summary the loop builds from it. */
	private static ToolStep ran(int stepNo, String tool, String fixture) {
		String output = fixture(fixture);
		return new ToolStep(stepNo, tool, "OK", output, ToolSummaries.summarize(stepNo, tool, ToolOutcome.OK, output));
	}

	private static ReportCheck.Result checkInDossierMode(String answer) {
		return ReportCheck.check(Answers.json(answer), STEPS, AgentMode.DOSSIER, DOSSIER);
	}

	private static ReportCheck.Result check(String answer) {
		return ReportCheck.check(Answers.json(answer), STEPS);
	}

	private static ReportCheck.Result checkAssessed(String answer) {
		return ReportCheck.checkAssessed(Answers.json(answer), SHEETS, LAB);
	}

	private static List<String> errors(String answer) {
		return ReportCheck.errors(Answers.json(answer), STEPS);
	}

	private static String golden() {
		try (var in = ReportCheckTest.class.getResourceAsStream("/dossier/golden/f3.md")) {
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

}
