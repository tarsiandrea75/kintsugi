package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The deadlines and budgets the agent ships with, read from the packaged application.yaml (agent spec, "Budgets"; the
 * human's decisions of 2026-10-14 and 2026-10-16): each call deadline fits the longest call measured, and the
 * incident's time budget holds the longest incident measured.
 */
class DefaultBudgetsTest {

	private static CallTimeouts deadlines;

	private static LoopSettings loop;

	@BeforeAll
	static void readPackagedDefaults() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);
		Binder binder = Binder.get(environment);
		deadlines = binder.bind("kintsugi.agent.model.call-timeout", CallTimeouts.class).get();
		loop = binder.bind("kintsugi.agent.loop", LoopSettings.class).get();
	}

	@Test
	void anActCallAndTheReportMayTake600Seconds() {
		// Dossier mode on the local route (local-dossier-timing): a phase's first ACT answer took up to 406 s and the
		// report 447 s, past the 420 s these deadlines were.
		assertThat(deadlines).isEqualTo(new CallTimeouts(Duration.ofSeconds(600), Duration.ofSeconds(600)));
	}

	@Test
	void theIncidentBudgetHoldsTheLongestIncidentMeasured() {
		// The longest incident measured (runs 11-13) took 1915 s. Since T5c15 the budget no longer holds a run with every model
		// call at its deadline (6 ACT answers and 2 reports at 600 s since local-dossier-timing, 80 min): a recorded follow-up.
		Duration longestIncidentMeasured = Duration.ofSeconds(1915);

		assertThat(loop.incidentTimeout()).isEqualTo(Duration.ofMinutes(45)).isGreaterThan(longestIncidentMeasured);
	}

	@Test
	void theModelCallBudgetHoldsAValidRunWithEveryUncountedRefusalAndEveryRetry() {
		// Each phase's answers are at most its allowance (8 in all); each uncounted refusal can add one answer (T5c9),
		// and so can each evidence phase's one retry (T5c12); then 2 report attempts. A valid run never reaches the
		// model-call budget, which only guards against loop bugs.
		int phaseRetries = 3;
		int longestValidRun = LoopSettings.TOOL_BUDGET + LoopSettings.UNCOUNTED_REFUSALS + phaseRetries + 2;

		assertThat(LoopSettings.UNCOUNTED_REFUSALS).isEqualTo(2);
		assertThat(loop.maxModelCalls()).isEqualTo(15).isGreaterThanOrEqualTo(longestValidRun);
	}

	@Test
	void theLoopsReadTimeoutFollowsTheReportDeadline() {
		ModelRouteProperties route = new ModelRouteProperties("granite4.1:8b", 0, 8192, deadlines,
				new NumPredict(384, 160, 512));

		assertThat(LoopConfig.readTimeout(route, loop)).isEqualTo(Duration.ofSeconds(600));
	}

}
