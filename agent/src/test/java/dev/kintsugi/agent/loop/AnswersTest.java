package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Reading the plan out of an answer's text (agent spec, "The plan"). */
class AnswersTest {

	private static final String PLAN = "{\"hypotheses\":[],\"nextTools\":[\"search_logs\"]}";

	@Test
	void theWholeTextIsThePlan() {
		assertThat(Answers.embedded(PLAN).get("nextTools").get(0).asString()).isEqualTo("search_logs");
	}

	@Test
	void aFencedPlanAmidProseIsRead() {
		String text = "I suspect the database.\n```json\n" + PLAN + "\n```\nNow calling the tools.";
		assertThat(Answers.embedded(text).has("hypotheses")).isTrue();
	}

	@Test
	void anUnfencedPlanAmidProseIsRead() {
		assertThat(Answers.embedded("My plan: " + PLAN + " and nothing else.").has("nextTools")).isTrue();
	}

	@Test
	void textWithoutAJsonObjectHasNoPlan() {
		assertThat(Answers.embedded("")).isNull();
		assertThat(Answers.embedded(null)).isNull();
		assertThat(Answers.embedded("I will call status_summary.")).isNull();
		assertThat(Answers.embedded("[1, 2]")).isNull();
		assertThat(Answers.embedded("an unclosed { brace")).isNull();
	}

}
