package dev.kintsugi.agent.rules;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static dev.kintsugi.agent.rules.Resources.bare;
import static dev.kintsugi.agent.rules.Resources.failed;
import static dev.kintsugi.agent.rules.Resources.metric;
import static dev.kintsugi.agent.rules.Resources.series;
import static dev.kintsugi.agent.rules.Resources.service;
import static dev.kintsugi.agent.rules.Resources.steady;
import static dev.kintsugi.agent.rules.Resources.window;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.Dossier.ResourceSeries;

/**
 * R-RES, Config 0R's resource rule (ADR-021, "Definitions" and "The rule"; odd/tasks/resource-facts.md T5): synthetic
 * dossiers with no other fact, so Config 0 abstains and R-RES alone decides, pin each definition at its edges: the
 * usable window, a previous mean of 0, a ratio of exactly k = 2, an incident mean equal to the previous max, failed
 * facts, a service with several metrics, and the tie-breaks.
 */
class ResourceRuleTest {

	private static final String CPU = "processor_usage";

	private static final String MEMORY = "memory_used";

	private static final String FILES = "open_files";

	@Test
	void aSeriesTwiceItsPreviousMeanAndAboveItsPreviousMaxNamesItsServiceResourceExhaustion() {
		RulesVerdict verdict = diagnose(bare(service("payment-service", metric(CPU, steady(0.1, 0.12, 0.2)))));

		assertThat(verdict).extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("payment-service", "RESOURCE_EXHAUSTION", "R-RES");
		// The facts name the series, both means, the previous max and the ratio.
		assertThat(verdict.facts()).containsExactly("payment-service processor_usage: incident mean 0.2000, 2.00x the"
				+ " previous mean 0.1000, above the previous max 0.1200");
	}

	@Test
	void aRatioJustUnderTwoIsNotAbnormal() {
		assertNone(bare(service("payment-service", metric(CPU, steady(0.1, 0.12, 0.1999)))));
	}

	@Test
	void anIncidentMeanEqualToThePreviousMaxIsNotAbnormalHoweverLargeTheRatio() {
		assertNone(bare(service("payment-service", metric(CPU, steady(0.1, 0.3, 0.3)))));
		assertThat(diagnose(bare(service("payment-service", metric(CPU, steady(0.1, 0.3, 0.30001))))).rule())
			.isEqualTo("R-RES");
	}

	@Test
	void aPreviousMeanOfZeroGivesNoRatio() {
		assertNone(bare(service("payment-service", metric(FILES, steady(0.0, 0.0, 50.0)))));
	}

	@Test
	void aWindowWithHalfThePointsOfTheFullerWindowIsUsableAndOneWithFewerIsNot() {
		// The fuller window here is the incident one, 10 points.
		assertFires(series(window(10, 5, 1.0, 1.0), window(10, 10, 3.0, 3.0)));
		assertNone(bare(service("payment-service", metric(CPU,
				series(window(10, 4, 1.0, 1.0), window(10, 10, 3.0, 3.0))))));
		assertFires(series(window(10, 10, 1.0, 1.0), window(10, 5, 3.0, 3.0)));
		assertNone(bare(service("payment-service", metric(CPU,
				series(window(10, 10, 1.0, 1.0), window(10, 4, 3.0, 3.0))))));
		// The half is of the fuller window's points: 10 values against a previous window of 20 points, but not 9.
		assertFires(series(window(20, 10, 1.0, 1.0), window(10, 10, 3.0, 3.0)));
		assertNone(bare(service("payment-service", metric(CPU,
				series(window(20, 10, 1.0, 1.0), window(10, 9, 3.0, 3.0))))));
	}

	@Test
	void aUsableWindowHoldsAtLeastTwoValues() {
		assertFires(series(window(2, 2, 1.0, 1.0), window(2, 2, 3.0, 3.0)));
		// One value of two points is half of the fuller window, but under 2 values.
		assertNone(bare(service("payment-service", metric(CPU,
				series(window(2, 1, 1.0, 1.0), window(2, 2, 3.0, 3.0))))));
		assertNone(bare(service("payment-service", metric(CPU,
				series(window(2, 2, 1.0, 1.0), window(2, 1, 3.0, 3.0))))));
		assertNone(bare(service("payment-service", metric(CPU,
				series(window(0, 0, null, null), window(10, 10, 3.0, 3.0))))));
	}

	@Test
	void aFailedResourceFactIsNoEvidence() {
		assertNone(bare(service("payment-service", failed(CPU), failed(MEMORY), failed(FILES))));
		RulesVerdict verdict = diagnose(bare(service("payment-service", failed(CPU),
				metric(MEMORY, steady(100.0, 110.0, 250.0)), failed(FILES))));
		assertThat(verdict.service()).isEqualTo("payment-service");
		assertThat(verdict.facts()).singleElement().asString().startsWith("payment-service memory_used:");
	}

	@Test
	void aServiceRatioIsTheLargestOverItsAbnormalSeriesOnly() {
		// payment-service: processor_usage 3x and memory_used 2.5x are abnormal; open_files 10x stays inside its
		// previous max, so it does not count. inventory-service's 2.8x is below payment-service's 3x.
		RulesVerdict verdict = diagnose(bare(
				service("payment-service", metric(CPU, steady(0.1, 0.2, 0.3)),
						metric(MEMORY, steady(100.0, 110.0, 250.0)), metric(FILES, steady(10.0, 200.0, 100.0))),
				service("inventory-service", metric(FILES, steady(10.0, 11.0, 28.0)))));

		assertThat(verdict.service()).isEqualTo("payment-service");
		assertThat(verdict.facts()).hasSize(2)
			.anySatisfy(fact -> assertThat(fact).startsWith("payment-service processor_usage:").contains("3.00x"))
			.anySatisfy(fact -> assertThat(fact).startsWith("payment-service memory_used:").contains("2.50x"))
			.noneSatisfy(fact -> assertThat(fact).contains("open_files"));
	}

	@Test
	void theLargestRatioWinsAndTiesGoToTheTopologysServiceOrder() {
		List<String> order = LAB.leafFirst();
		String first = order.get(0);
		String last = order.get(order.size() - 1);
		// A tie: the service earlier in the order wins, whichever comes first in the dossier.
		assertThat(diagnose(bare(service(last, metric(CPU, steady(0.1, 0.1, 0.2))),
				service(first, metric(CPU, steady(0.1, 0.1, 0.2))))).service()).isEqualTo(first);
		// A larger ratio wins over the order.
		assertThat(diagnose(bare(service(first, metric(CPU, steady(0.1, 0.1, 0.2))),
				service(last, metric(CPU, steady(0.1, 0.1, 0.21))))).service()).isEqualTo(last);
	}

	private static void assertFires(ResourceSeries series) {
		assertThat(diagnose(bare(service("payment-service", metric(CPU, series)))))
			.extracting(RulesVerdict::service, RulesVerdict::rule)
			.containsExactly("payment-service", "R-RES");
	}

	private static void assertNone(Dossier dossier) {
		assertThat(diagnose(dossier)).isEqualTo(RulesDiagnosis.diagnose(dossier, LAB))
			.extracting(RulesVerdict::service, RulesVerdict::rule)
			.containsExactly("UNKNOWN", "R-NONE");
		assertThat(RulesVersion.V2.candidates(dossier, LAB)).isEmpty();
	}

	private static RulesVerdict diagnose(Dossier dossier) {
		return RulesVersion.V2.diagnose(dossier, LAB);
	}

}
