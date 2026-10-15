package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.Phase;

/**
 * The plan check (agent spec, "The plan"): a {@code nextTools} entry outside the phase is dropped and recorded, and
 * every other rule still invalidates the plan (T5c12).
 */
class PlanCheckTest {

	private static final String HYPOTHESIS = "{\"rootCauseService\":\"gateway-mock\","
			+ "\"rootCauseCategory\":\"DEPENDENCY_LATENCY\",\"why\":\"toolCallId=4: slow answers\"}";

	@Test
	void aNextToolOutsideThePhaseIsDroppedAndThePlanStaysValid() {
		// The sixth to eighth live runs: every KNOWLEDGE plan named search_logs and was rejected (T5c12).
		PlanCheck.Result checked = PlanCheck.check(plan("\"search_logs\",\"search_runbooks\""), Phase.KNOWLEDGE);

		assertThat(checked.errors()).isEmpty();
		assertThat(checked.dropped())
			.containsExactly("nextTools[0] \"search_logs\" is not a tool of KNOWLEDGE, which offers search_runbooks");
		assertThat(checked.plan().get("nextTools")).singleElement()
			.satisfies(tool -> assertThat(tool.asString()).isEqualTo("search_runbooks"));
		assertThat(checked.plan().get("hypotheses")).hasSize(1);
	}

	@Test
	void aPlanWhoseEveryNextToolIsOutsideThePhaseKeepsAnEmptyList() {
		PlanCheck.Result checked = PlanCheck.check(plan("\"search_logs\",\"count_logs\""), Phase.KNOWLEDGE);

		assertThat(checked.errors()).isEmpty();
		assertThat(checked.dropped()).hasSize(2);
		assertThat(checked.plan().get("nextTools").isEmpty()).isTrue();
	}

	@Test
	void aPlanWithAllItsToolsInThePhaseDropsNothing() {
		PlanCheck.Result checked = PlanCheck.check(plan("\"search_logs\""), Phase.INVESTIGATE);

		assertThat(checked.errors()).isEmpty();
		assertThat(checked.dropped()).isEmpty();
		assertThat(checked.plan()).isEqualTo(plan("\"search_logs\""));
	}

	@Test
	void everyOtherRuleStillInvalidatesThePlan() {
		assertThat(PlanCheck.check(plan("3"), Phase.KNOWLEDGE).errors())
			.containsExactly("nextTools[0] must be one of this phase's tools: search_runbooks; got 3");
		assertThat(PlanCheck.check(plan("\"a\",\"b\",\"c\",\"d\",\"e\""), Phase.KNOWLEDGE).errors())
			.containsExactly("nextTools must be an array of 0 to 4 tool names");
		PlanCheck.Result badHypothesis = PlanCheck.check(JsonMapper.shared()
			.readTree("{\"hypotheses\":[],\"nextTools\":[\"search_logs\"]}"), Phase.KNOWLEDGE);
		assertThat(badHypothesis.errors()).containsExactly("hypotheses must be an array of 1 to 3 items");
	}

	private static JsonNode plan(String nextTools) {
		return JsonMapper.shared().readTree("{\"hypotheses\":[" + HYPOTHESIS + "],\"nextTools\":[" + nextTools + "]}");
	}

}
