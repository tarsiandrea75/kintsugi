package dev.kintsugi.agent.loop;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
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
 * @param think Ollama's {@code think} request field (agent spec, "Routes"; ADR-017): null sends none, the request of
 * every run before the qwen-dev experiment; {@code true} or {@code false} sends it with that value. Ollama counts the
 * thinking tokens against {@code num_predict}, and Spring AI returns the thinking apart from the answer (the message's
 * {@code thinking} property), so the loop reads the content alone.
 */
public record OllamaRoute(String model, ChatModel chatModel, double temperature, int numCtx,
		CallTimeouts callTimeouts, NumPredict numPredict, Boolean think) implements ModelRoute {

	/** The route with no {@code think} field, as every run before the qwen-dev experiment sent it. */
	public OllamaRoute(String model, ChatModel chatModel, double temperature, int numCtx, CallTimeouts callTimeouts,
			NumPredict numPredict) {
		this(model, chatModel, temperature, numCtx, callTimeouts, numPredict, null);
	}

	@Override
	public AgentRoute route() {
		return AgentRoute.LOCAL;
	}

	/** {@code default} when no {@code think} field is sent, else {@code on} or {@code off}. */
	@Override
	public String thinking() {
		if (this.think == null) {
			return "default";
		}
		return this.think ? "on" : "off";
	}

	/** The {@code num_ctx} every call and the warm-up send. */
	@Override
	public OptionalInt contextWindow() {
		return OptionalInt.of(this.numCtx);
	}

	/**
	 * The route's settings with the answer's cap as {@code num_predict}. {@code wait} is not sent: the loop's interruption
	 * closes the RestClient's connection, which stops Ollama (agent spec, "Budgets"), so the options stay those of every
	 * run before the cloud route. The {@code think} field is sent only when the route has a setting for it.
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
		thinkOption().ifPresent(options::thinkOption);
		return options.build();
	}

	/**
	 * The options of the warm-up: the route's model, temperature, context window and {@code think} setting, so that
	 * Ollama loads the model exactly as the run's calls will use it (a different {@code num_ctx} would load it again),
	 * and at most one token of answer, since only the load matters.
	 */
	@Override
	public Optional<ChatOptions> warmUpOptions() {
		OllamaChatOptions.Builder options = OllamaChatOptions.builder()
			.model(this.model)
			.temperature(this.temperature)
			.numCtx(this.numCtx)
			.numPredict(1);
		thinkOption().ifPresent(options::thinkOption);
		return Optional.of(options.build());
	}

	/** Ollama's {@code think} field for the route's setting, or none when it has none. */
	private Optional<ThinkOption> thinkOption() {
		return Optional.ofNullable(this.think)
			.map(enabled -> enabled ? ThinkOption.ThinkBoolean.ENABLED : ThinkOption.ThinkBoolean.DISABLED);
	}

}
