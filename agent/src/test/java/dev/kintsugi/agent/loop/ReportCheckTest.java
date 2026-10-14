package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.FakeLabTools.fixture;
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

/** The report's schema and evidence rules, without a run (agent spec, "The report", "Evidence is checked"). */
class ReportCheckTest {

	private static final Map<Integer, ToolStep> STEPS = Map.of( //
			3, new ToolStep(3, "status_summary", "OK", fixture("status_summary-orders.json")), //
			4, new ToolStep(4, "search_logs", "OK", fixture("search_logs-order.json")), //
			5, new ToolStep(5, "recent_events", "OK", fixture("recent_events-order.json")), //
			6, new ToolStep(6, "search_logs", "REFUSED_PHASE", null));

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
	void aQuoteOfADecodedJsonStringPasses() {
		// The event detail is a JSON string holding escaped JSON; the decoded text holds plain quotes.
		assertThat(errors(report("payment-service", "DEPENDENCY_LATENCY",
				evidence(5, "recent_events", "\"exception\":\"java.net.http.HttpTimeoutException\"")))).isEmpty();
	}

	@Test
	void aFabricatedQuoteIsRejectedNamingTheItem() {
		assertThat(errors(report("order-service", "DEPENDENCY_LATENCY",
				evidence(3, "status_summary", "PAYMENT_PENDING"), evidence(4, "search_logs", "Connection refused by peer"))))
			.containsExactly("evidence[1].quote is not in the output of step 4");
	}

	@Test
	void aWrongToolAMissingStepOrARefusedCallIsRejected() {
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
		String task = Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null), List.of());
		assertThat(task).contains("at most 160 characters").contains("not the whole line");
	}

	@Test
	void aJsonAnswerInACodeFenceIsRead() {
		assertThat(errors("```json\n" + report("UNKNOWN", "UNKNOWN") + "\n```")).isEmpty();
	}

	private static List<String> errors(String answer) {
		return ReportCheck.errors(Answers.json(answer), STEPS);
	}

}
