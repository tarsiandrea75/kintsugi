package dev.kintsugi.agent.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.convention.TestBean;

/** Needs a local Ollama with granite4.1:8b. Run with {@code ./mvnw test -Plive}. */
@Tag("live")
@SpringBootTest(properties = "kintsugi.agent.route=local")
class LiveTimeToolTest {

	@TestBean
	Clock clock;

	@Autowired
	ChatClient chatClient;

	static Clock clock() {
		return Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);
	}

	@Test
	void modelCallsTheToolAndAnswersFromTheFixedClock() {
		String answer = chatClient.prompt().user("What time is it in Buenos Aires?").call().content();

		assertThat(answer).contains("09:00");
	}

}
