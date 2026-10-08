package dev.kintsugi.agent.loop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A chat model that answers from a script, in order, and keeps every prompt it received. Each answer reports 10
 * prompt and 5 completion tokens. Once the script is used up, a call fails, so a loop that calls the model more
 * than the test planned is caught.
 */
final class ScriptedChatModel implements ChatModel {

	private final Queue<Function<Prompt, ChatResponse>> script = new ConcurrentLinkedQueue<>();

	private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

	@Override
	public ChatResponse call(Prompt prompt) {
		this.prompts.add(prompt);
		Function<Prompt, ChatResponse> next = this.script.poll();
		if (next == null) {
			throw new IllegalStateException("the script has no answer left");
		}
		return next.apply(prompt);
	}

	/** Answers {@code text}, with no tool call. */
	ScriptedChatModel text(String text) {
		this.script.add(prompt -> response(new AssistantMessage(text)));
		return this;
	}

	/** Answers with these tool calls. */
	ScriptedChatModel tools(ToolCall... calls) {
		this.script.add(prompt -> response(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build()));
		return this;
	}

	/** Waits {@code delay}, then answers {@code text}. */
	ScriptedChatModel slow(Duration delay, String text) {
		this.script.add(prompt -> {
			sleep(delay);
			return response(new AssistantMessage(text));
		});
		return this;
	}

	/** Waits {@code delay}, then answers with these tool calls. */
	ScriptedChatModel slowTools(Duration delay, ToolCall... calls) {
		this.script.add(prompt -> {
			sleep(delay);
			return response(AssistantMessage.builder().content("").toolCalls(List.of(calls)).build());
		});
		return this;
	}

	/** Throws {@code failure}, as an unreachable model does. */
	ScriptedChatModel fails(RuntimeException failure) {
		this.script.add(prompt -> {
			throw failure;
		});
		return this;
	}

	/** Answers a valid plan for {@code phase}, one hypothesis, calling no tool in particular. */
	ScriptedChatModel plan() {
		return text(plan("order-service", "DEPENDENCY_LATENCY"));
	}

	/** Answers the three evidence phases with a plan and no tool call each. */
	ScriptedChatModel quietPhases() {
		return plan().text("nothing to call").plan().text("nothing to call").plan().text("nothing to call");
	}

	/** Answers INVESTIGATE and KNOWLEDGE with a plan and no tool call each. */
	ScriptedChatModel quietPhasesAfterTriage() {
		return plan().text("nothing to call").plan().text("nothing to call");
	}

	List<Prompt> prompts() {
		return this.prompts;
	}

	int calls() {
		return this.prompts.size();
	}

	int left() {
		return this.script.size();
	}

	static ToolCall call(String name, String arguments) {
		return new ToolCall("call-" + name, "function", name, arguments);
	}

	static String plan(String service, String category, String... nextTools) {
		String tools = Arrays.stream(nextTools).map(tool -> "\"" + tool + "\"").collect(Collectors.joining(","));
		return "{\"hypotheses\":[{\"rootCauseService\":\"" + service + "\",\"rootCauseCategory\":\"" + category
				+ "\",\"why\":\"a first guess\"}],\"nextTools\":[" + tools + "]}";
	}

	/** A report whose evidence items are already JSON objects. */
	static String report(String service, String category, String... evidence) {
		return "{\"rootCauseService\":\"" + service + "\",\"rootCauseCategory\":\"" + category
				+ "\",\"summary\":\"what the evidence shows\",\"confidence\":0.7,\"evidence\":["
				+ String.join(",", evidence) + "]}";
	}

	static String evidence(int toolCallId, String tool, String quote) {
		return "{\"toolCallId\":" + toolCallId + ",\"tool\":\"" + tool + "\",\"quote\":"
				+ tools.jackson.databind.json.JsonMapper.shared().writeValueAsString(quote)
				+ ",\"why\":\"it shows the cause\"}";
	}

	static String unknownReport() {
		return "{\"rootCauseService\":\"UNKNOWN\",\"rootCauseCategory\":\"UNKNOWN\",\"summary\":\"no clear cause\","
				+ "\"confidence\":0.1,\"evidence\":[]}";
	}

	/** The text of every message of every prompt, for scans. */
	String everything() {
		List<String> texts = new ArrayList<>();
		this.prompts.forEach(prompt -> prompt.getInstructions().forEach(message -> texts.add(message.getText())));
		return String.join("\n", texts);
	}

	private static ChatResponse response(AssistantMessage message) {
		return new ChatResponse(List.of(new Generation(message)),
				ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
	}

	private static void sleep(Duration delay) {
		try {
			Thread.sleep(delay);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while answering", interrupted);
		}
	}

}
