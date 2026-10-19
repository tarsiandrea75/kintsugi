package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** The cloud route's properties, {@code kintsugi.agent.cloud} (agent spec, "Routes" and "Answer caps"). */
class CloudRoutePropertiesTest {

	private static final CallTimeouts DEADLINES = new CallTimeouts(Duration.ofSeconds(60), Duration.ofSeconds(90));

	/**
	 * The replay measures a cloud report cap set only in the environment, so the environment variable must bind by
	 * Spring Boot's relaxed rules for system environment variables (upper case, {@code _} for {@code .} and {@code -}).
	 */
	@Test
	void theEnvironmentVariableOverridesTheCloudReportCap() {
		CloudRouteProperties cloud = bindFromEnvironment(Map.of("KINTSUGI_AGENT_CLOUD_NAME", "claude-haiku-5-5",
				"KINTSUGI_AGENT_CLOUD_CALL_TIMEOUT_ACT", "60s", "KINTSUGI_AGENT_CLOUD_CALL_TIMEOUT_REPORT", "90s",
				"KINTSUGI_AGENT_CLOUD_NUM_PREDICT_FIRST_ACT", "384", "KINTSUGI_AGENT_CLOUD_NUM_PREDICT_ACT", "160",
				"KINTSUGI_AGENT_CLOUD_NUM_PREDICT_REPORT", "768"));

		assertThat(cloud.callTimeout()).isEqualTo(DEADLINES);
		assertThat(cloud.numPredict()).isEqualTo(new NumPredict(384, 160, 768));
	}

	@Test
	void aRouteWithoutDeadlinesOrCapsIsRejectedNamingTheProperty() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new CloudRouteProperties("claude-haiku-5-5", null, new NumPredict(384, 160, 512)))
			.withMessageContaining("kintsugi.agent.cloud.call-timeout");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new CloudRouteProperties("claude-haiku-5-5", DEADLINES, null))
			.withMessageContaining("kintsugi.agent.cloud.num-predict");
	}

	private static CloudRouteProperties bindFromEnvironment(Map<String, Object> variables) {
		MutablePropertySources sources = new MutablePropertySources();
		sources.addFirst(new SystemEnvironmentPropertySource(
				StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
		return new Binder(ConfigurationPropertySources.from(sources)).bindOrCreate("kintsugi.agent.cloud",
				CloudRouteProperties.class);
	}

}
