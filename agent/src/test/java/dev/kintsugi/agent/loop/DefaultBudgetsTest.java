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
 * human's decision of 2026-10-14 after the third full run): each call deadline fits the longest call measured, and
 * the incident's time budget holds a whole run with every model call at its deadline.
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
	void anActCallMayTake300SecondsAndTheReport420() {
		assertThat(deadlines).isEqualTo(new CallTimeouts(Duration.ofSeconds(300), Duration.ofSeconds(420)));
	}

	@Test
	void theIncidentBudgetHoldsTheLongestRunWithEveryModelCallAtItsDeadline() {
		// The longest run of the third live run: 16 steps, 8 tool calls, 6 ACT answers and 2 reports (one retry).
		Duration longestRun = deadlines.of(ModelTask.ACT).multipliedBy(6).plus(deadlines.of(ModelTask.REPORT).multipliedBy(2));

		assertThat(loop.incidentTimeout()).isEqualTo(Duration.ofMinutes(45)).isGreaterThan(longestRun);
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

		assertThat(LoopConfig.readTimeout(route, loop)).isEqualTo(Duration.ofSeconds(420));
	}

}
