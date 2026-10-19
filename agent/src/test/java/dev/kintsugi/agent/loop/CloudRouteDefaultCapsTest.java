package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.kintsugi.agent.AgentDatabase;

/**
 * With no override, the cloud route's caps are the ones {@code application.yaml} sets, today equal to the local ones
 * (agent spec, "Answer caps"), so giving the route its own caps changed nothing until a measurement chose otherwise.
 */
@SpringBootTest(properties = { "kintsugi.agent.route=cloud", "spring.ai.anthropic.api-key=test-only-not-a-key" })
class CloudRouteDefaultCapsTest {

	@Autowired
	ModelRouter router;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@Test
	void theCloudRouteDefaultsToTodaysCaps() {
		assertThat(this.router.route(ModelTask.REPORT).numPredict()).isEqualTo(new NumPredict(384, 160, 512));
	}

}
