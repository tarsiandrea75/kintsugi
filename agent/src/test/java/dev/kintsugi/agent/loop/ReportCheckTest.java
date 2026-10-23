package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.FakeLabTools.fixture;
import static dev.kintsugi.agent.loop.ScriptedChatModel.dossierEvidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.evidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.report;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.ToolOutcome;
import dev.kintsugi.agent.summary.ToolSummaries;

/** The report's schema and evidence rules, without a run (agent spec, "The report", "Evidence is checked"). */
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
	void aDossierCitationKeepsTheOtherRules() {
		// A toolCallId that is neither an integer nor "dossier" is still rejected.
		String other = "{\"toolCallId\":\"step3\",\"tool\":\"status_summary\",\"line\":3,\"why\":\"\"}";
		assertThat(checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING", other)).errors())
			.containsExactly("evidence[0].toolCallId must be an integer or \"dossier\"");
		assertThat(errors(report("payment-service", "DEPENDENCY_REJECTING", other)))
			.containsExactly("evidence[0].toolCallId must be an integer");
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
