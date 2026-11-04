package dev.kintsugi.agent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.dossier.WindowChange;

/**
 * The dossier's change rules copy Config 0's thresholds rather than share them, so Config 0's code does not change
 * (ADR-028, Decision 5); this pins each copy to its rule's constant. It lives in the rules package, whose constants are
 * package-private, so the dossier package never imports the rules.
 */
class WindowChangeConstantsTest {

	@Test
	void theLatencyRuleIsRDownsSlowTest() {
		assertThat(WindowChange.SLOW_RATIO).isEqualTo(RulesDiagnosis.SLOW_RATIO);
		assertThat(WindowChange.SLOW_SECONDS).isEqualTo(RulesDiagnosis.SLOW_SECONDS);
	}

	@Test
	void theResourceRuleIsRRes() {
		assertThat(WindowChange.RESOURCE_RATIO).isEqualTo(ResourceRule.RATIO);
		assertThat(WindowChange.USABLE_VALUES).isEqualTo(ResourceRule.USABLE_VALUES);
		assertThat(WindowChange.USABLE_SHARE).isEqualTo(ResourceRule.USABLE_SHARE);
	}

	@Test
	void theCountRulesMinimumMirrorsRDownsCounts() {
		// A new rule (ADR-028, Decision 5), whose 3 mirrors R-DOWN's REFUSING_ANSWERS and SLOW_TIMEOUTS.
		assertThat(WindowChange.COUNT_MIN).isEqualTo(RulesDiagnosis.REFUSING_ANSWERS)
			.isEqualTo(RulesDiagnosis.SLOW_TIMEOUTS);
	}

}
