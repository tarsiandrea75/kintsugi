package dev.kintsugi.agent.loop;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * One route of {@link ModelRouter}: a chat model and the options every call on it carries (agent spec, "Routes"). The
 * local route is {@link OllamaRoute}, the cloud route {@link AnthropicRoute} (ADR-012).
 */
public sealed interface ModelRoute permits OllamaRoute, AnthropicRoute {

	/** Which route this is, recorded on the incident and its report. */
	AgentRoute route();

	/** The model's name, sent with every call and recorded on every step. */
	String model();

	/** The chat model, called directly: in Spring AI 2.0.1 it never executes the tools it is offered. */
	ChatModel chatModel();

	/** How long one call may take before the run ends {@code TIMED_OUT}, per task. */
	CallTimeouts callTimeouts();

	/** The most tokens an answer may hold, per kind of call. */
	NumPredict numPredict();

	/**
	 * The route's thinking setting as a replay records it (agent spec, "Replay of recorded tool outputs"): {@code on} or
	 * {@code off} when the route sends one, {@code default} when it leaves it to the model.
	 */
	String thinking();

	/**
	 * The options of one call: the route's settings, the answer's cap, the tool definitions offered (none for the
	 * report) and, for the report, the JSON schema the answer is constrained by. {@code wait} is how long the loop
	 * waits for this call, its deadline or the incident's time left, whichever ends first; a route whose client does
	 * not stop when the loop interrupts the call bounds the call by it.
	 */
	ChatOptions options(int answerCap, Duration wait, List<ToolCallback> tools, String outputSchema);

	/** The options of the warm-up request, or none when the route has no model to load. */
	Optional<ChatOptions> warmUpOptions();

}
