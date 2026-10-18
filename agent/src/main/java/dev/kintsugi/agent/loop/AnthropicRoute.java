package dev.kintsugi.agent.loop;

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
 * <li>{@code max_tokens} is the local route's {@code num_predict} cap for the same call.</li>
 * </ul>
 *
 * @param model the model's name, {@code kintsugi.agent.cloud.name}
 * @param chatModel the auto-configured Anthropic chat model
 * @param callTimeouts the cloud route's deadlines, {@code kintsugi.agent.cloud.call-timeout}
 * @param numPredict the answer caps, the same as the local route's
 */
public record AnthropicRoute(String model, ChatModel chatModel, CallTimeouts callTimeouts,
		NumPredict numPredict) implements ModelRoute {

	@Override
	public AgentRoute route() {
		return AgentRoute.CLOUD;
	}

	@Override
	public ChatOptions options(int answerCap, List<ToolCallback> tools, String outputSchema) {
		AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
			.model(this.model)
			.maxTokens(answerCap)
			.thinkingDisabled()
			.toolCallbacks(tools);
		if (outputSchema != null) {
			options.outputSchema(outputSchema);
		}
		return options.build();
	}

	/** None: there is no model to load, and a request would only cost a call. */
	@Override
	public Optional<ChatOptions> warmUpOptions() {
		return Optional.empty();
	}

}
