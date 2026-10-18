package dev.kintsugi.agent.loop;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * The local route: the Ollama model of {@code kintsugi.agent.model} (ADR-003, ADR-010).
 *
 * @param model the model's name, sent with every call and recorded on every step
 * @param chatModel the chat model, called directly: in Spring AI 2.0.1 it never executes the tools it is offered
 * @param temperature the sampling temperature
 * @param numCtx the context window, in tokens
 * @param callTimeouts how long one call may take before the run ends {@code TIMED_OUT}, per task
 * @param numPredict the most tokens an answer may hold, per kind of call
 */
public record OllamaRoute(String model, ChatModel chatModel, double temperature, int numCtx,
		CallTimeouts callTimeouts, NumPredict numPredict) implements ModelRoute {

	@Override
	public AgentRoute route() {
		return AgentRoute.LOCAL;
	}

	/**
	 * The route's settings with the answer's cap as {@code num_predict}. {@code wait} is not sent: the loop's interruption
	 * closes the RestClient's connection, which stops Ollama (agent spec, "Budgets"), so the options stay those of every
	 * run before the cloud route.
	 */
	@Override
	public ChatOptions options(int answerCap, Duration wait, List<ToolCallback> tools, String outputSchema) {
		OllamaChatOptions.Builder options = OllamaChatOptions.builder()
			.model(this.model)
			.temperature(this.temperature)
			.numCtx(this.numCtx)
			.numPredict(answerCap)
			.toolCallbacks(tools);
		if (outputSchema != null) {
			options.outputSchema(outputSchema);
		}
		return options.build();
	}

	/**
	 * The options of the warm-up: the route's model, temperature and context window, so that Ollama loads the model
	 * exactly as the run's calls will use it (a different {@code num_ctx} would load it again), and at most one token
	 * of answer, since only the load matters.
	 */
	@Override
	public Optional<ChatOptions> warmUpOptions() {
		return Optional.of(OllamaChatOptions.builder()
			.model(this.model)
			.temperature(this.temperature)
			.numCtx(this.numCtx)
			.numPredict(1)
			.build());
	}

}
