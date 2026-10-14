package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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
			.contains("PAYMENT_PENDING or PAYMENT_FAILED");
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

}
