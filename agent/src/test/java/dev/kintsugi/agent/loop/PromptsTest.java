package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.Phase;

/**
 * What the prompts tell the model about where to look and whom to blame (T5c8; agent spec, "What one call sees",
 * "The plan"). The texts stay generic: they describe the lab's call path and the meaning of the categories, never a
 * cause the lab may be put through.
 */
class PromptsTest {

	private static final String PLAN = "{\"hypotheses\":[{\"rootCauseService\":\"postgres\","
			+ "\"rootCauseCategory\":\"CONFIGURATION\",\"why\":\"a guess\"}],\"nextTools\":[]}";

	@Test
	void theSystemMessageDescribesTheCallPath() {
		assertThat(Prompts.system("orders are slow", 15))
			.contains("order-service calls inventory-service and payment-service")
			.contains("payment-service calls gateway-mock");
	}

	@Test
	void aDependencyCauseNamesTheDependencyNotItsCaller() {
		assertThat(Prompts.system("orders are slow", 15)).contains("DEPENDENCY_LATENCY and DEPENDENCY_REJECTING")
			.contains("not its caller")
			.contains("if A times out or fails because B does, name B");
	}

	@Test
	void anAffectedOrderToTraceIsOneThatDidNotEndPaid() {
		assertThat(Prompts.system("orders are slow", 15)).contains("recent_events with source=order")
			.contains("not an older PAID one");
	}

	@Test
	void anOrderNeverPaidIsFoundAsPendingNotAsFailed() {
		// The fifth live run filtered source=order by outcome=PAYMENT_FAILED while its orders were PAYMENT_PENDING (T5c9).
		assertThat(Prompts.system("orders are slow", 15))
			.contains("an order never paid stays PAYMENT_PENDING, with PAYMENT_UNAVAILABLE order events")
			.contains("type=PAYMENT_UNAVAILABLE or outcome=PAYMENT_PENDING")
			.contains("outcome=PAYMENT_FAILED only when status_summary shows PAYMENT_FAILED orders");
	}

	@Test
	void aToolArgumentIsARealValueNeverAPlaceholder() {
		assertThat(Prompts.system("orders are slow", 15)).contains("copy a real orderId from a result, never a placeholder");
	}

	@Test
	void theActTaskStatesHowManyToolCallsThePhaseStillAllows() {
		// The seventh live run: TRIAGE planned 3 calls against a phase allowance of 2, and the third was refused (T5c11).
		assertThat(Prompts.act(new Prompts.State(Phase.TRIAGE, 2, 8, null), true))
			.contains("You may make at most 2 tool calls in this phase; more are refused.");
		assertThat(Prompts.act(new Prompts.State(Phase.INVESTIGATE, 3, 1, null), false))
			.contains("You may make at most 1 tool call in this phase; more are refused.");
	}

	@Test
	void theRetryAsksForExactlyOneToolCallNowAndNoPlan() {
		// The eighth live run: F1's INVESTIGATE answer wrote its plan, then described its calls in Markdown (T5c12).
		String retry = Prompts.actRetry(new Prompts.State(Phase.INVESTIGATE, 4, 6, null));

		assertThat(retry).startsWith("Phase: INVESTIGATE")
			.contains("Your previous answer made no tool call")
			.contains("exactly one tool call now")
			.contains("no plan")
			.doesNotContain("first write your plan")
			.doesNotContain("hypotheses")
			.doesNotContain("answer with no text");
	}

	@Test
	void aTriagePlanCoversTwoServicesOnTheCallPath() {
		String triage = Prompts.act(new Prompts.State(Phase.TRIAGE, 2, 8, null), true);

		assertThat(triage).contains("at least two different services along the call path");
	}

	@Test
	void aLaterPhaseStartsFromAFreshPlanWhoseHypothesesCiteAToolCallId() {
		String investigate = Prompts.act(new Prompts.State(Phase.INVESTIGATE, 4, 6, null), true);

		assertThat(investigate).contains("a fresh plan").contains("toolCallId").doesNotContain("at least two");
	}

	@Test
	void onlyThePhasesOwnPlanIsShownAndNeverAsTheLatestPlan() {
		assertThat(new Prompts.State(Phase.INVESTIGATE, 3, 5, PLAN).text()).contains("Your plan for this phase: " + PLAN)
			.doesNotContain("Latest plan");
		assertThat(new Prompts.State(Phase.INVESTIGATE, 4, 6, null).text()).doesNotContain("plan");
	}

	@Test
	void theReportTaskListsTheCitableCallsAndMarksTheRefusedOnesNotCitable() {
		// The sixth live run's F2 cited a refused call in both reports and ended INVALID_REPORT (T5c10).
		List<ToolStep> steps = List.of(new ToolStep(2, "query_metric", "OK", "{}", null),
				new ToolStep(7, "get_order_trace", "REFUSED_PLACEHOLDER", null, null),
				new ToolStep(8, "count_logs", "INVALID_PARAMETER", "{}", null),
				new ToolStep(9, "recent_events", "REFUSED_DUPLICATE", null, null));

		String task = Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null), List.of(), steps);

		assertThat(task).contains("Citable toolCallIds: 2, 8.")
			.contains("Refused calls did not run and are not citable: 7, 9.");
	}

	@Test
	void withNoRefusedCallTheReportTaskListsOnlyTheCitableOnes() {
		String task = Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null), List.of(),
				List.of(new ToolStep(3, "status_summary", "OK", "{}", null)));

		assertThat(task).contains("Citable toolCallIds: 3.").doesNotContain("not citable");
		assertThat(Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null), List.of(), List.of()))
			.contains("Citable toolCallIds: none.");
	}

	@Test
	void aPlanHypothesisWhyMayHold300Characters() {
		// Plans of the sixth live run were rejected for a why over 200 characters (T5c10).
		assertThat(Prompts.act(new Prompts.State(Phase.TRIAGE, 2, 8, null), true)).contains("why at most 300 characters");
		assertThat(PlanCheck.errors(plan("w".repeat(300)), Phase.TRIAGE)).isEmpty();
		assertThat(PlanCheck.errors(plan("w".repeat(301)), Phase.TRIAGE))
			.containsExactly("hypotheses[0].why must be a string of at most 300 characters");
	}

	private static JsonNode plan(String why) {
		return JsonMapper.shared()
			.readTree("{\"hypotheses\":[{\"rootCauseService\":\"postgres\",\"rootCauseCategory\":\"CONFIGURATION\","
					+ "\"why\":\"" + why + "\"}],\"nextTools\":[]}");
	}

}
