package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.kintsugi.agent.AgentDatabase;

/**
 * With no override, the cloud route's caps are the ones {@code application.yaml} sets (agent spec, "Answer caps"): the
 * local ones but a report cap of 768, chosen on the replay (report-length): it removed the cut reports at 512.
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
	void theCloudRouteDefaultsToTheMeasuredCaps() {
		assertThat(this.router.route(ModelTask.REPORT).numPredict()).isEqualTo(new NumPredict(384, 160, 768));
	}

}
