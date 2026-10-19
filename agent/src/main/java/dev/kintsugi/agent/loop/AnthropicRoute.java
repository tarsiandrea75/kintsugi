package dev.kintsugi.agent.loop;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * The cloud route: Anthropic's {@code claude-haiku-5-5} through Spring AI (ADR-012; agent spec, "Routes"). Each call
 * carries complete options, because Spring AI 2.0.1's {@code AnthropicChatModel} uses a prompt's options as they are,
 * without the auto-configured defaults.
 *
 * <ul>
 * <li>No temperature: the model rejects a sampling value other than its default.</li>
 * <li>Thinking disabled: thinking tokens count against {@code max_tokens}, so with it the answer caps would no longer
 * mean what they mean on the local route.</li>
 * <li>The report's schema without the keywords Anthropic's structured outputs reject ({@link AnthropicSchema});
 * {@link ReportCheck} enforces their constraints on both routes.</li>
 * <li>{@code max_tokens} is the local route's {@code num_predict} cap for the same call.</li>
 * <li>A per-call timeout, the loop's wait plus {@link #ABORT_GRACE}. The SDK's OkHttp call blocks in a socket read
 * that ignores the interruption the loop sends when it stops waiting, so without it an abandoned call would hold its
 * connection, and the provider would go on writing, until the client's timeout ({@code spring.ai.anthropic.timeout},
 * the longest deadline). Spring AI passes the option to the SDK as the call's {@code RequestOptions}, and OkHttp's
 * call timeout cancels the call. The grace keeps the loop's deadline, not the client, the one that ends a call.</li>
 * </ul>
 *
 * @param model the model's name, {@code kintsugi.agent.cloud.name}
 * @param chatModel the auto-configured Anthropic chat model
 * @param callTimeouts the cloud route's deadlines, {@code kintsugi.agent.cloud.call-timeout}
 * @param numPredict the cloud route's answer caps, {@code kintsugi.agent.cloud.num-predict}, sent as {@code maxTokens}
 */
public record AnthropicRoute(String model, ChatModel chatModel, CallTimeouts callTimeouts,
		NumPredict numPredict) implements ModelRoute {

	/** How long after the loop stops waiting the client cancels the call. */
	static final Duration ABORT_GRACE = Duration.ofSeconds(1);

	@Override
	public AgentRoute route() {
		return AgentRoute.CLOUD;
	}

	@Override
	public ChatOptions options(int answerCap, Duration wait, List<ToolCallback> tools, String outputSchema) {
		AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
			.model(this.model)
			.maxTokens(answerCap)
			.thinkingDisabled()
			.timeout(wait.plus(ABORT_GRACE))
			.toolCallbacks(tools);
		if (outputSchema != null) {
			options.outputSchema(cloudSchema(outputSchema));
		}
		return options.build();
	}

	/**
	 * The report schema as this route sends it: without the keywords Anthropic's structured outputs reject, every
	 * object closed ({@link AnthropicSchema}). {@link ReportCheck} enforces the dropped constraints.
	 */
	static String cloudSchema(String schema) {
		return AnthropicSchema.of(schema);
	}

	/** None: there is no model to load, and a request would only cost a call. */
	@Override
	public Optional<ChatOptions> warmUpOptions() {
		return Optional.empty();
	}

}
