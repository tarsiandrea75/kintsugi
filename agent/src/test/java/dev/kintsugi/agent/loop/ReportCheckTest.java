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

import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.lab.ToolOutcome;
import dev.kintsugi.agent.summary.ToolSummaries;

/** The report's schema and evidence rules, without a run (agent spec, "The report", "Evidence is checked"). */
class ReportCheckTest {

	private static final Map<Integer, ToolStep> STEPS = Map.of( //
			3, new ToolStep(3, "status_summary", "OK", fixture("status_summary-orders.json"), null), //
			4, new ToolStep(4, "search_logs", "OK", fixture("search_logs-order.json"), null), //
			5, new ToolStep(5, "recent_events", "OK", fixture("recent_events-order.json"), null), //
			6, new ToolStep(6, "search_logs", "REFUSED_PHASE", null, null));

	/** A rendered dossier, the golden F3 one (T4b), as the dossier message carries it after its introduction. */
	private static final String DOSSIER = golden();

	/** The first event detail of recent_events-order.json, decoded: a whole line, about 230 characters. */
	private static final String WHOLE_DETAIL = "{\"exception\":\"java.net.http.HttpTimeoutException\","
			+ "\"url\":\"http://payment-service:8081/payments\",\"message\":\"Payment of order "
			+ "5c3034dc-dacc-474c-b272-3c5fac9023a4 failed: HttpTimeoutException calling POST "
			+ "http://payment-service:8081/payments: Request cancelled\"}";

	@Test
	void aQuoteOfTheRawOutputPasses() {
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", "{\"status\":\"PAYMENT_PENDING\",\"count\":7}"))))
			.isEmpty();
	}

	@Test
	void whitespaceIsNormalizedOnBothSidesButCaseIsKept() {
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY",
				evidence(4, "search_logs", "  16:32:03.395Z \n INFO   1 --- [order-service]  ")))).isEmpty();
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY",
				evidence(4, "search_logs", "16:32:03.395Z info 1 --- [order-service]")))).hasSize(1);
	}

	@Test
	void normalizationDecodesEscapesThenCollapsesWhitespace() {
		assertThat(ReportCheck.normalized("  a\\n\\nb\\t c \n\n d \\\"e\\\" f\\\\n  ")).isEqualTo("a b c d \"e\" f\\n");
	}

	@Test
	void aQuoteOfTheSummaryIsCheckedLikeTheOutput() {
		Map<Integer, ToolStep> steps = Map.of(3, new ToolStep(3, "status_summary", "OK",
				fixture("status_summary-orders.json"), "toolCallId=3 tool=status_summary outcome=OK\nstatus=\"PAID\" count=3"));
		assertThat(ReportCheck.errors(Answers.json(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", "status=\"PAID\" count=3"))), steps)).isEmpty();
		assertThat(ReportCheck.errors(Answers.json(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", "status=\"PAID\" count=4"))), steps))
			.containsExactly("evidence[0].quote is not in the output of step 3");
	}

	@Test
	void aQuoteOfTextTheSummaryCodeWroteIsRejected() {
		// T5c6 review WARNING A: only the summary lines that copy the tool output count, without the (cut) markers.
		String events = fixture("recent_events-order.json");
		String eventsSummary = ToolSummaries.summarize(5, "recent_events", ToolOutcome.OK, events);
		String failed = "{\"outcome\":\"UPSTREAM_ERROR\",\"reason\":\"Loki answered 503\",\"returned\":0,"
				+ "\"truncated\":false,\"items\":[]}";
		String logs = manyLogLines();
		Map<Integer, ToolStep> steps = Map.of( //
				5, new ToolStep(5, "recent_events", "OK", events, eventsSummary), //
				7, new ToolStep(7, "search_logs", "UPSTREAM_ERROR", failed,
						ToolSummaries.summarize(7, "search_logs", ToolOutcome.UPSTREAM_ERROR, failed)), //
				8, new ToolStep(8, "search_logs", "OK", logs, ToolSummaries.summarize(8, "search_logs", ToolOutcome.OK, logs)));
		String durations = ToolSummaries.lines(eventsSummary).stream().filter(line -> line.startsWith("durationMs min=")).findFirst().orElseThrow();
		assertThat(eventsSummary).contains("b272-3c5\"(cut)");
		assertThat(steps.get(8).summary()).endsWith("[summary cut at 1500 characters]");

		for (String[] quote : List.of(new String[] { "5", "recent_events", "toolCallId=5 tool=recent_events outcome=OK" },
				new String[] { "5", "recent_events", "events=20" }, new String[] { "5", "recent_events", durations },
				new String[] { "5", "recent_events", "newest 3:" },
				new String[] { "5", "recent_events", "b272-3c5\"(cut)" },
				new String[] { "7", "search_logs", "a data source of the tool failed" },
				new String[] { "8", "search_logs", "[summary cut at 1500 characters]" })) {
			assertThat(ReportCheck.errors(Answers.json(report("payment-service", "DEPENDENCY_LATENCY",
					evidence(Integer.parseInt(quote[0]), quote[1], quote[2]))), steps))
				.as(quote[2])
				.containsExactly("evidence[0].quote is not in the output of step " + quote[0]);
		}
		// A line that copies the output still counts, with its labels: the shape the model sees.
		assertThat(ReportCheck.errors(Answers.json(report("payment-service", "DEPENDENCY_LATENCY",
				evidence(5, "recent_events", "type=\"PAYMENT_UNAVAILABLE\" outcome=\"PAYMENT_PENDING\""),
				evidence(7, "search_logs", "Loki answered 503"))), steps)).isEmpty();
	}

	@Test
	void aQuoteOfADecodedJsonStringPasses() {
		// The event detail is a JSON string holding escaped JSON; the decoded text holds plain quotes.
		assertThat(errors(report("payment-service", "DEPENDENCY_LATENCY",
				evidence(5, "recent_events", "\"exception\":\"java.net.http.HttpTimeoutException\"")))).isEmpty();
	}

	@Test
	void aFabricatedQuoteAmongVerifiedItemsIsDroppedAndRecorded() {
		// The seventh live run: one item's quote was not verbatim, the others were (T5c11).
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", "PAYMENT_PENDING"), evidence(4, "search_logs", "Connection refused by peer"),
				evidence(5, "recent_events", "HttpTimeoutException calling POST")));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1].quote is not in the output of step 4");
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").intValue()).containsExactly(3, 5);
	}

	@Test
	void aReportWhoseEveryQuoteIsFabricatedIsRejectedNamingEachItem() {
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", "PAYMENT_FAILED badly"), evidence(4, "search_logs", "Connection refused by peer")));

		assertThat(result.errors()).containsExactly("evidence[0].quote is not in the output of step 3",
				"evidence[1].quote is not in the output of step 4");
		assertThat(result.evidence()).isNull();
	}

	@Test
	void aCitedRefusedCallStillRejectsTheReportAndTheRetryNamesTheBadQuoteToo() {
		// Only a quote that is not in its step, or a wrong tool (T5c14), is dropped; a refused or missing step still
		// rejects the report.
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", "PAYMENT_PENDING"), evidence(6, "search_logs", "PAYMENT_PENDING"),
				evidence(4, "search_logs", "Connection refused by peer")));

		assertThat(result.errors()).containsExactly("evidence[1].toolCallId 6 was refused (REFUSED_PHASE) and did not run",
				"evidence[2].quote is not in the output of step 4");
		assertThat(result.evidence()).isNull();
	}

	@Test
	void anItemCitingTheWrongToolAmongVerifiedItemsIsDroppedAndRecorded() {
		// The twelfth live run, F2: the report named the true cause but cited a recent_events step as search_runbooks (T5c14).
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", "PAYMENT_PENDING"), evidence(4, "search_logs", "16:32:03.395Z"),
				evidence(5, "search_runbooks", "HttpTimeoutException calling POST")));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[2].tool is search_runbooks, but step 5 is recent_events");
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").intValue()).containsExactly(3, 4);
	}

	@Test
	void aReportWhoseEveryItemCitesTheWrongToolIsRejectedNamingEachItem() {
		ReportCheck.Result result = check(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "search_logs", "PAYMENT_PENDING"), evidence(5, "search_runbooks", "HttpTimeoutException calling POST")));

		assertThat(result.errors()).containsExactly("evidence[0].tool is search_logs, but step 3 is status_summary",
				"evidence[1].tool is search_runbooks, but step 5 is recent_events");
		assertThat(result.dropped()).isEmpty();
		assertThat(result.evidence()).isNull();
	}

	@Test
	void anItemOutsideItsQuoteBoundsIsNeverDropped() {
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "status_summary", "PAYMENT_PENDING"),
				evidence(3, "status_summary", "PAID")))).containsExactly("evidence[1].quote must be a string of 8 to 160 characters");
	}

	@Test
	void anUnknownReportStaysValidWhenItsOnlyQuoteIsDropped() {
		ReportCheck.Result result = check(report("UNKNOWN", "UNKNOWN", evidence(4, "search_logs", "Connection refused by peer")));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[0].quote is not in the output of step 4");
		assertThat(result.evidence()).isEmpty();
	}

	@Test
	void aMissingStepOrARefusedCallIsRejectedAndTheRetryNamesAWrongToolToo() {
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "search_logs", "PAYMENT_PENDING"),
				evidence(9, "search_logs", "PAYMENT_PENDING"), evidence(6, "search_logs", "PAYMENT_PENDING"))))
			.containsExactly("evidence[0].tool is search_logs, but step 3 is status_summary",
					"evidence[1].toolCallId 9 is not a tool call of this incident",
					"evidence[2].toolCallId 6 was refused (REFUSED_PHASE) and did not run");
	}

	@Test
	void aValueOutsideTheEnumsNamesTheFieldAndItsValues() {
		assertThat(errors(report("payment-gateway", "DEPENDENCY_LATENCY", evidence(3, "status_summary", "PAYMENT_PENDING"))))
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
	void aQuoteOutsideItsBoundsIsRejected() {
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY", evidence(3, "status_summary", "PAID"))))
			.containsExactly("evidence[0].quote must be a string of 8 to 160 characters");
	}

	@Test
	void aQuoteLongerThan160CharactersIsRejectedWithAReasonTheRetryCanActOn() {
		// A whole event detail, decoded: it is in the output, so only its length is wrong (the third live run).
		assertThat(WHOLE_DETAIL).hasSizeGreaterThan(160);
		assertThat(ReportCheck.quoted(WHOLE_DETAIL, STEPS.get(5).output())).isTrue();

		assertThat(errors(report("payment-service", "DEPENDENCY_LATENCY", evidence(5, "recent_events", WHOLE_DETAIL))))
			.containsExactly("evidence[0].quote is longer than 160 characters; quote only its distinctive fragment, "
					+ "such as an exception name, a status or an error message, not the whole line");
	}

	@Test
	void aQuoteOfExactly160CharactersPasses() {
		assertThat(errors(report("payment-service", "DEPENDENCY_LATENCY",
				evidence(5, "recent_events", WHOLE_DETAIL.substring(0, 160))))).isEmpty();
	}

	@Test
	void aShortFragmentOfAnEscapedLineMatchesEscapedOrDecoded() {
		// The summary shows the detail as an escaped JSON literal; the model may copy it either way.
		assertThat(errors(report("payment-service", "DEPENDENCY_LATENCY",
				evidence(5, "recent_events", "\\\"exception\\\":\\\"java.net.http.HttpTimeoutException\\\""),
				evidence(5, "recent_events", "HttpTimeoutException calling POST")))).isEmpty();
	}

	@Test
	void theSchemaSentToTheModelHasTheSameQuoteBounds() {
		JsonNode quote = JsonMapper.shared()
			.readTree(Schemas.REPORT)
			.at("/properties/evidence/items/properties/quote");
		assertThat(quote.get("minLength").intValue()).isEqualTo(8);
		assertThat(quote.get("maxLength").intValue()).isEqualTo(160);
	}

	@Test
	void theReportTaskAsksForAShortFragmentPerQuote() {
		String task = Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null), List.of(), STEPS.values());
		assertThat(task).contains("at most 160 characters").contains("not the whole line");
	}

	@Test
	void aJsonAnswerInACodeFenceIsRead() {
		assertThat(errors("```json\n" + report("UNKNOWN", "UNKNOWN") + "\n```")).isEmpty();
	}

	@Test
	void aDossierCitationWhoseQuoteIsInTheDossierPassesInDossierMode() {
		// T5b: the dossier is cited as toolCallId "dossier", tool "dossier", its quote matched like a tool output's.
		ReportCheck.Result result = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				dossierEvidence("CLIENT_ERROR | UNKNOWN | ConnectException"),
				dossierEvidence("  PAYMENT_PENDING \n|  0 | 6  ")));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).isEmpty();
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").asString())
			.containsExactly("dossier", "dossier");
	}

	@Test
	void aDossierCitationWhoseQuoteIsNotInTheDossierIsDropped() {
		ReportCheck.Result result = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", "PAYMENT_PENDING"), dossierEvidence("payment-service restarted 3 times")));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1].quote is not in the dossier");
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").asString()).containsExactly("3");
	}

	@Test
	void aDossierCitationNamingAnotherToolIsDroppedLikeAWrongTool() {
		ReportCheck.Result result = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", "PAYMENT_PENDING"),
				dossierEvidence("traffic_counts", "CLIENT_ERROR | UNKNOWN | ConnectException")));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped())
			.containsExactly("evidence[1].tool is traffic_counts, but toolCallId dossier is the dossier");
	}

	@Test
	void inExplorerModeADossierCitationIsDroppedAsCitingNoSource() {
		ReportCheck.Result result = check(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", "PAYMENT_PENDING"),
				dossierEvidence("CLIENT_ERROR | UNKNOWN | ConnectException")));

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1].toolCallId dossier: this run has no dossier");
		assertThat(result.evidence()).hasSize(1);
	}

	@Test
	void aDossierModeRunWhoseDossierWasNotGatheredDropsADossierCitation() {
		ReportCheck.Result result = ReportCheck.check(Answers.json(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", "PAYMENT_PENDING"), dossierEvidence("CLIENT_ERROR | UNKNOWN"))), STEPS,
				AgentMode.DOSSIER, null);

		assertThat(result.errors()).isEmpty();
		assertThat(result.dropped()).containsExactly("evidence[1].toolCallId dossier: the dossier was not gathered");
	}

	@Test
	void dossierAndToolCitationsMixAndKeepTheirOrder() {
		ReportCheck.Result result = checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING",
				evidence(5, "recent_events", "HttpTimeoutException calling POST"),
				dossierEvidence("CLIENT_ERROR | UNKNOWN | ConnectException"), evidence(3, "status_summary", "PAYMENT_PENDING")));

		assertThat(result.errors()).isEmpty();
		assertThat(result.evidence()).extracting(item -> item.get("toolCallId").asString())
			.containsExactly("5", "dossier", "3");
	}

	@Test
	void aReportWhoseOnlyEvidenceIsADroppedDossierItemFollowsTheNoEvidenceLeftRule() {
		String named = report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence("payment-service restarted 3 times"));
		ReportCheck.Result rejected = checkInDossierMode(named);
		assertThat(rejected.errors()).containsExactly("evidence[0].quote is not in the dossier");
		assertThat(rejected.evidence()).isNull();

		ReportCheck.Result unknown = checkInDossierMode(report("UNKNOWN", "UNKNOWN",
				dossierEvidence("payment-service restarted 3 times")));
		assertThat(unknown.errors()).isEmpty();
		assertThat(unknown.evidence()).isEmpty();
	}

	@Test
	void aDossierCitationKeepsTheOtherRules() {
		// Bounds stay fatal, and a toolCallId that is neither an integer nor "dossier" is still rejected.
		assertThat(checkInDossierMode(report("payment-service", "DEPENDENCY_REJECTING", dossierEvidence("6 | 6"))).errors())
			.containsExactly("evidence[0].quote must be a string of 8 to 160 characters");
		String other = "{\"toolCallId\":\"step3\",\"tool\":\"status_summary\",\"quote\":\"PAYMENT_PENDING\",\"why\":\"\"}";
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

	/** A search_logs result whose summary passes the cap: 40 distinct long lines full of quotes. */
	private static String manyLogLines() {
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < 40; i++) {
			items.append(i == 0 ? "" : ",")
				.append("{\"timestamp\":\"2026-10-08T10:00:%02dZ\",\"service\":\"order-service\",".formatted(i))
				.append("\"level\":\"WARN\",\"untrustedText\":\"x : failure ")
				// A letter, not a number: lines that differ only in a number form one group (T5c10).
				.append("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN".charAt(i))
				.append(' ')
				// Quotes, which the summary escapes, so that five lines no longer fit.
				.append("\\\"".repeat(190))
				.append("\"}");
		}
		return "{\"outcome\":\"OK\",\"returned\":40,\"truncated\":false,\"items\":[" + items + "]}";
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
