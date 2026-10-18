package dev.kintsugi.agent.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import dev.kintsugi.agent.AgentDatabase;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * An incident left {@code RUNNING} by a stopped agent is failed when the agent starts again (agent spec, "Agent
 * restarted during a run"). The row is seeded before this class's context starts; the property below gives the class
 * a context of its own, so the start under test is really this one.
 */
@SpringBootTest(properties = { "kintsugi.test.context=restart-recovery", "kintsugi.agent.route=local" })
@DirtiesContext
class RestartRecoveryTest {

	private static final UUID LEFT_RUNNING = UUID.randomUUID();

	private static final UUID ALREADY_ENDED = UUID.randomUUID();

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		AgentDatabase.register(registry);
	}

	@BeforeAll
	static void anAgentStoppedDuringARun() {
		AgentDatabase.migrate();
		AgentDatabase.empty();
		AgentDatabase.admin()
			.sql("INSERT INTO incidents (id, signal, status, started_at) VALUES (?, '{}', 'RUNNING', now())")
			.param(LEFT_RUNNING)
			.update();
		AgentDatabase.admin()
			.sql("INSERT INTO incidents (id, signal, status, failure_reason, started_at, ended_at)"
					+ " VALUES (?, '{}', 'TIMED_OUT', 'incident ran past 10 minutes', now(), now())")
			.param(ALREADY_ENDED)
			.update();
	}

	@Test
	void theRunningIncidentFailedWithTheRestartReason() {
		Map<String, Object> row = row(LEFT_RUNNING);

		assertThat(row).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", "agent restarted during the run")
			.containsEntry("ended", true);
	}

	@Test
	void anEndedIncidentIsLeftAsItWas() {
		assertThat(row(ALREADY_ENDED)).containsEntry("status", "TIMED_OUT")
			.containsEntry("failure_reason", "incident ran past 10 minutes");
	}

	private static Map<String, Object> row(UUID id) {
		return AgentDatabase.admin()
			.sql("SELECT status, failure_reason, ended_at IS NOT NULL AS ended FROM incidents WHERE id = ?")
			.param(id)
			.query()
			.singleRow();
	}

}
