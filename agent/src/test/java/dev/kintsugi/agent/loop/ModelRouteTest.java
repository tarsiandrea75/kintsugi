package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.Phase;

/**
 * The two routes' options (agent spec, "Routes"): the local route's are exactly those of every run before the cloud
 * route existed, and the cloud route's carry no temperature, no thinking and the same answer caps.
 */
class ModelRouteTest {

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	private static final CallTimeouts DEADLINES = new CallTimeouts(Duration.ofSeconds(420), Duration.ofSeconds(420));

	private static final List<ToolCallback> TOOLS = new FakeLabTools().toolsFor(Phase.TRIAGE);

	private static final String SCHEMA = Schemas.report(AgentMode.EXPLORER);

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final OllamaRoute local = new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, DEADLINES, CAPS);

	private final AnthropicRoute cloud = new AnthropicRoute("claude-haiku-5-5", this.model,
			new CallTimeouts(Duration.ofSeconds(60), Duration.ofSeconds(90)), CAPS);

	@Test
	void theLocalRoutesCallOptionsAreThoseOfToday() {
		ChatOptions act = this.local.options(384, Duration.ofSeconds(420), TOOLS, null);
		ChatOptions report = this.local.options(512, Duration.ofSeconds(420), List.of(), SCHEMA);

		assertThat(act).isEqualTo(OllamaChatOptions.builder()
			.model("granite4.1:8b")
			.temperature(0.0)
			.numCtx(8192)
			.numPredict(384)
			.toolCallbacks(TOOLS)
			.build());
		assertThat(report).isEqualTo(OllamaChatOptions.builder()
			.model("granite4.1:8b")
			.temperature(0.0)
			.numCtx(8192)
			.numPredict(512)
			.toolCallbacks(List.of())
			.outputSchema(SCHEMA)
			.build());
		OllamaChatOptions options = (OllamaChatOptions) act;
		assertThat(options.getModel()).isEqualTo("granite4.1:8b");
		assertThat(options.getTemperature()).isEqualTo(0.0);
		assertThat(options.getNumCtx()).isEqualTo(8192);
		assertThat(options.getNumPredict()).isEqualTo(384);
		assertThat(options.getOutputSchema()).isNull();
		assertThat(((OllamaChatOptions) report).getOutputSchema()).isEqualTo(SCHEMA);
	}

	@Test
	void theLocalRoutesWarmUpIsThatOfToday() {
		assertThat(this.local.warmUpOptions()).hasValue(OllamaChatOptions.builder()
			.model("granite4.1:8b")
			.temperature(0.0)
			.numCtx(8192)
			.numPredict(1)
			.build());
	}

	@Test
	void theCloudRoutesOptionsHaveNoTemperatureNoThinkingAndTheSameCaps() {
		for (int cap : List.of(CAPS.firstAct(), CAPS.act(), CAPS.report())) {
			AnthropicChatOptions options = (AnthropicChatOptions) this.cloud.options(cap, Duration.ofSeconds(60), TOOLS,
					null);

			assertThat(options.getModel()).isEqualTo("claude-haiku-5-5");
			assertThat(options.getTemperature()).isNull();
			assertThat(options.getThinking()).isNotNull();
			assertThat(options.getThinking().isDisabled()).isTrue();
			assertThat(options.getMaxTokens()).isEqualTo(cap);
			assertThat(options.getToolCallbacks()).isEqualTo(TOOLS);
			assertThat(options.getOutputSchema()).isNull();
			assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(61));
		}
	}

	@Test
	void theCloudRoutesReportCarriesTheSchema() {
		AnthropicChatOptions options = (AnthropicChatOptions) this.cloud.options(512, Duration.ofMillis(42_500),
				List.of(), SCHEMA);

		assertThat(options.getMaxTokens()).isEqualTo(512);
		assertThat(options.getThinking().isDisabled()).isTrue();
		assertThat(options.getTemperature()).isNull();
		assertThat(options.getToolCallbacks()).isEmpty();
		// The loop's wait, here the incident's time left, plus the grace: the client cancels the call just after the
		// loop stops waiting for it.
		assertThat(options.getTimeout()).isEqualTo(Duration.ofMillis(43_500));
		JsonMapper json = JsonMapper.shared();
		assertThat(json.readTree(options.getOutputSchema())).isEqualTo(json.readTree(SCHEMA));
	}

	@Test
	void theCloudRouteHasNoWarmUp() {
		assertThat(this.cloud.warmUpOptions()).isEmpty();
	}

	@Test
	void eachRouteNamesItselfAndItsModel() {
		assertThat(this.local.route()).isEqualTo(AgentRoute.LOCAL);
		assertThat(this.local.model()).isEqualTo("granite4.1:8b");
		assertThat(this.cloud.route()).isEqualTo(AgentRoute.CLOUD);
		assertThat(this.cloud.model()).isEqualTo("claude-haiku-5-5");
	}

}
