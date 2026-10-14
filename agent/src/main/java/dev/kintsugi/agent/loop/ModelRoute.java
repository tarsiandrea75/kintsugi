package dev.kintsugi.agent.loop;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * One route of {@link ModelRouter}: a chat model and the options every call on it carries.
 *
 * @param model the model's name, sent with every call and recorded on every step
 * @param chatModel the chat model, called directly: in Spring AI 2.0.1 it never executes the tools it is offered
 * @param temperature the sampling temperature
 * @param numCtx the context window, in tokens
 * @param callTimeouts how long one call may take before the run ends {@code TIMED_OUT}, per task
 * @param numPredict the most tokens an answer may hold, per kind of call
 */
public record ModelRoute(String model, ChatModel chatModel, double temperature, int numCtx,
		CallTimeouts callTimeouts, NumPredict numPredict) {

	/**
	 * The options of one call: the route's settings, the answer's cap ({@code num_predict}), the tool definitions
	 * offered (none for the report) and, for the report, the JSON schema the answer is constrained by. In M2 every
	 * route is Ollama's.
	 */
	public ChatOptions options(int answerCap, List<ToolCallback> tools, String outputSchema) {
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
	public ChatOptions warmUpOptions() {
		return OllamaChatOptions.builder()
			.model(this.model)
			.temperature(this.temperature)
			.numCtx(this.numCtx)
			.numPredict(1)
			.build();
	}

}
