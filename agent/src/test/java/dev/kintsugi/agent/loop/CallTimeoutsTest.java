package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The deadline of one model call, per task (agent spec, "Budgets"). */
class CallTimeoutsTest {

	private static final CallTimeouts DEADLINES = new CallTimeouts(Duration.ofSeconds(240), Duration.ofSeconds(300));

	@Test
	void eachTaskHasItsOwnDeadline() {
		assertThat(DEADLINES.of(ModelTask.ACT)).isEqualTo(Duration.ofSeconds(240));
		assertThat(DEADLINES.of(ModelTask.REPORT)).isEqualTo(Duration.ofSeconds(300));
		assertThat(DEADLINES.longest()).isEqualTo(Duration.ofSeconds(300));
		assertThat(new CallTimeouts(Duration.ofSeconds(400), Duration.ofSeconds(300)).longest())
			.isEqualTo(Duration.ofSeconds(400));
	}

	@Test
	void aMissingOrNonPositiveDeadlineIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> new CallTimeouts(null, Duration.ofSeconds(300)));
		assertThatIllegalArgumentException().isThrownBy(() -> new CallTimeouts(Duration.ofSeconds(240), Duration.ZERO));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new CallTimeouts(Duration.ofSeconds(-1), Duration.ofSeconds(300)));
	}

	@Test
	void theRouteBindsBothDeadlinesAndTheCaps() {
		ModelRouteProperties route = bind(Map.of("kintsugi.agent.model.name", "granite4.1:8b",
				"kintsugi.agent.model.call-timeout.act", "240s", "kintsugi.agent.model.call-timeout.report", "300s",
				"kintsugi.agent.model.num-predict.first-act", "384", "kintsugi.agent.model.num-predict.act", "160",
				"kintsugi.agent.model.num-predict.report", "512"));

		assertThat(route.callTimeout()).isEqualTo(DEADLINES);
		assertThat(route.numPredict()).isEqualTo(new NumPredict(384, 160, 512));
	}

	@Test
	void aRouteWithoutDeadlinesOrCapsIsRejectedNamingTheProperty() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ModelRouteProperties("granite4.1:8b", 0, 8192, null, new NumPredict(384, 160, 512)))
			.withMessageContaining("kintsugi.agent.model.call-timeout");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ModelRouteProperties("granite4.1:8b", 0, 8192, DEADLINES, null))
			.withMessageContaining("kintsugi.agent.model.num-predict");
	}

	private static ModelRouteProperties bind(Map<String, String> properties) {
		return new Binder(new MapConfigurationPropertySource(properties))
			.bindOrCreate("kintsugi.agent.model", ModelRouteProperties.class);
	}

}
