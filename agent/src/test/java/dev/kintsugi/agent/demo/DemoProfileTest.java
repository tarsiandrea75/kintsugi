package dev.kintsugi.agent.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.web.context.WebApplicationContext;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.kintsugi.agent.incident.IncidentController;

/**
 * The {@code demo} profile stays what it was before the incident API: a one-shot question, with no web server and no
 * database, so it runs with Ollama alone.
 */
@SpringBootTest
@ActiveProfiles("demo")
@ExtendWith(OutputCaptureExtension.class)
class DemoProfileTest {

	@TestBean
	ChatModel chatModel;

	@Autowired
	ApplicationContext context;

	static ChatModel chatModel() {
		return prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("It is noon in Buenos Aires."))));
	}

	@Test
	void theDemoRunsWithoutAServerOrADatabase(CapturedOutput output) {
		assertThat(context).isNotInstanceOf(WebApplicationContext.class);
		assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
		assertThat(context.getBeanNamesForType(IncidentController.class)).isEmpty();
		assertThat(context.containsBean("demoRunner")).isTrue();
		assertThat(output.getOut()).contains("It is noon in Buenos Aires.");
	}

	@Test
	void theDemoKeepsItsFiveMinuteReadTimeoutAndHasNoLoopCustomizer() {
		assertThat(context.getEnvironment().getProperty("spring.http.clients.read-timeout")).isEqualTo("5m");
		assertThat(context.getBeanNamesForType(RestClientCustomizer.class)).doesNotContain("loopReadTimeout");
	}

}
