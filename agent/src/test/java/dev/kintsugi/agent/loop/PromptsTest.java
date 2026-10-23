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
			.contains("name B when A waits for B or gets an error answer from B")
			.doesNotContain("if A times out or fails because B does, name B");
	}

	@Test
	void aCallerThatCannotReachAHealthyDependencyPointsAtTheCallersSetting() {
		// F3 runs named the callee DEPENDENCY_REJECTING for a caller that could not connect to it at all.
		assertThat(Prompts.system("orders are slow", 15))
			.contains("DEPENDENCY_REJECTING: a component is reached and answers, but refuses or fails")
			.contains("such as the address or port of a component it calls")
			.contains("When A cannot reach B at all")
			.contains("B is not the cause: suspect A's own setting for B, a CONFIGURATION of A");
	}

	@Test
	void aDependencyCauseFollowsTheChainToTheLastSlowOrRefusingComponent() {
		// After T3, every F1 run named payment-service, the caller of the slow gateway-mock: one hop only (F3 T5).
		assertThat(Prompts.system("orders are slow", 15))
			.contains("the component where the slowness or the error answer starts, not its caller")
			.contains("if B in turn waits for C or gets an error answer from C, name C")
			.contains("Follow the calls down to the last component that is slow or refuses")
			.contains("an answer that takes long is that component's own failure even when it ends in success and logs "
					+ "no error");
	}

	@Test
	void aTimeoutIsNotACallerThatCannotReachItsDependency() {
		assertThat(Prompts.system("orders are slow", 15)).contains("or the host is unknown; a timeout is not this case)");
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

	@Test
	void theReportTaskAsksForTheNumberOfALineAndNoQuote() {
		// Evidence-by-reference (ADR-013): the model chooses a line by its number; code fills in its text.
		String task = Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null), List.of(),
				List.of(new ToolStep(3, "status_summary", "OK", "{}", null)));

		assertThat(task).contains("{toolCallId, tool, line, why}")
			.contains("line is the number at the start of one line of that summary")
			.doesNotContain("quote")
			.doesNotContain("copied exactly");
		assertThat(Prompts.system("orders stay pending", 15)).contains("the number of one line of that tool call's result")
			.doesNotContain("quote copied exactly");
	}

	@Test
	void theReportTaskAsksForTheReasoningBeforeTheCause() {
		String task = Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null), List.of(), List.of());

		assertThat(task).contains("with these fields, in this order: summary")
			.containsSubsequence("summary:", "evidence:", "rootCauseService and rootCauseCategory", "confidence:");
	}

	@Test
	void theDossierIntroductionSaysHowToCiteTheDossier() {
		// T5b: the dossier has its own citation id; before, the introduction said it had no toolCallId (T5a).
		// Evidence-by-reference: it is cited by the number of a line, no longer by a quote.
		assertThat(Prompts.DOSSIER_INTRO).endsWith("It is not a tool call result: cite it with toolCallId \"dossier\" "
				+ "and tool \"dossier\", and the number of the line you cite: D7 is line 7.")
			.doesNotContain("quote")
			.doesNotContain("has no toolCallId");
		assertThat(Prompts.dossierNotGathered("the gathering failed")).contains("The dossier was not gathered")
			.contains("there is no dossier to cite")
			.doesNotContain("toolCallId \"dossier\"");
	}

	@Test
	void withADossierTheReportTaskListsItAmongTheCitableIdsAndSaysHowToCiteIt() {
		List<ToolStep> steps = List.of(new ToolStep(3, "status_summary", "OK", "{}", null));

		String dossier = Prompts.report(dossierState(Phase.CONCLUDE, true), List.of(), steps);
		assertThat(dossier).contains("Citable toolCallIds: 3, dossier.")
			.contains("To cite the dossier, write toolCallId \"dossier\" and tool \"dossier\", with the number of the "
					+ "line you cite: D7 is line 7.")
			.doesNotContain("quote");
		assertThat(Prompts.report(dossierState(Phase.CONCLUDE, true), List.of(), List.of()))
			.contains("Citable toolCallIds: dossier.");

		// Explorer mode, and a dossier run whose dossier was not gathered, never mention it.
		assertThat(Prompts.report(new Prompts.State(Phase.CONCLUDE, 0, 0, null), List.of(), steps))
			.doesNotContain("dossier");
		assertThat(Prompts.report(dossierState(Phase.CONCLUDE, false), List.of(), steps)).doesNotContain("dossier")
			.contains("Citable toolCallIds: 3.");
	}

	@Test
	void withADossierThePlansMayCiteIt() {
		String first = Prompts.act(dossierState(Phase.INVESTIGATE, true), true);
		assertThat(first).contains("at least two different services along the call path")
			.contains("\"toolCallId=dossier: ...\"")
			.doesNotContain("a fresh plan");
		String later = Prompts.act(dossierState(Phase.KNOWLEDGE, true), true);
		assertThat(later).contains("a fresh plan").contains("\"toolCallId=3: ...\" or \"toolCallId=dossier: ...\"");

		assertThat(Prompts.act(dossierState(Phase.INVESTIGATE, false), true)).doesNotContain("dossier");
		assertThat(Prompts.act(dossierState(Phase.KNOWLEDGE, false), true)).doesNotContain("dossier");
		assertThat(Prompts.act(new Prompts.State(Phase.TRIAGE, 2, 8, null), true)).doesNotContain("dossier");
		assertThat(Prompts.act(new Prompts.State(Phase.INVESTIGATE, 4, 6, null), true)).doesNotContain("dossier");
	}

	private static Prompts.State dossierState(Phase phase, boolean dossier) {
		return new Prompts.State(phase, 2, 4, null, AgentMode.DOSSIER.phases(), dossier);
	}

	private static JsonNode plan(String why) {
		return JsonMapper.shared()
			.readTree("{\"hypotheses\":[{\"rootCauseService\":\"postgres\",\"rootCauseCategory\":\"CONFIGURATION\","
					+ "\"why\":\"" + why + "\"}],\"nextTools\":[]}");
	}

}
