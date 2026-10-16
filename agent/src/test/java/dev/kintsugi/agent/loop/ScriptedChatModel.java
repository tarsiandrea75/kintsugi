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
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A chat model that answers from a script, in order, and keeps every prompt it received. Each answer reports 10
 * prompt and 5 completion tokens. Once the script is used up, a call fails, so a loop that calls the model more
 * than the test planned is caught. The run's warm-up request is answered apart, by {@link #warmUp} ({@code OK} unless
 * a test sets another), and kept apart: it is neither in the script nor in {@link #prompts()}.
 */
final class ScriptedChatModel implements ChatModel {

	private final Queue<Function<Prompt, ChatResponse>> script = new ConcurrentLinkedQueue<>();

	private final List<Prompt> prompts = new CopyOnWriteArrayList<>();

	private final List<Prompt> warmUps = new CopyOnWriteArrayList<>();

	private volatile Function<Prompt, ChatResponse> warmUp = prompt -> response(new AssistantMessage("OK"));

	@Override
	public ChatResponse call(Prompt prompt) {
		if (prompt.getInstructions().size() == 1 && Prompts.WARM_UP.equals(prompt.getInstructions().get(0).getText())) {
			this.warmUps.add(prompt);
			return this.warmUp.apply(prompt);
		}
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

	/** Answers {@code text} and these tool calls. */
	ScriptedChatModel answer(String text, ToolCall... calls) {
		this.script.add(prompt -> response(AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build()));
		return this;
	}

	/** Answers with no text and no tool call: the ACT task's way to end a phase, which gets no retry (T5c12). */
	ScriptedChatModel quiet() {
		return text("");
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

	/**
	 * Answers {@code text} and these tool calls, cut at the answer's cap: Ollama's {@code done_reason} is
	 * {@code length}, as when the model runs on until {@code num_predict} stops it.
	 */
	ScriptedChatModel cut(String text, ToolCall... calls) {
		AssistantMessage message = AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build();
		this.script.add(prompt -> new ChatResponse(
				List.of(new Generation(message, ChatGenerationMetadata.builder().finishReason("length").build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build()));
		return this;
	}

	/** Throws {@code failure}, as an unreachable model does. */
	ScriptedChatModel fails(RuntimeException failure) {
		this.script.add(prompt -> {
			throw failure;
		});
		return this;
	}

	/** Answers with a valid plan in the text and these tool calls, as the first answer of a phase does. */
	ScriptedChatModel planned(ToolCall... calls) {
		String text = "My plan:\n" + plan("order-service", "DEPENDENCY_LATENCY") + "\nCalling the tools now.";
		this.script.add(prompt -> response(AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build()));
		return this;
	}

	/**
	 * Answers a valid plan and no tool call. As a phase's first answer, it gets the phase's one retry, which the script
	 * must answer too (T5c12).
	 */
	ScriptedChatModel plan() {
		return text(plan("order-service", "DEPENDENCY_LATENCY"));
	}

	/** Answers the three evidence phases with no text and no tool call each: each phase ends at its first answer. */
	ScriptedChatModel quietPhases() {
		return quiet().quiet().quiet();
	}

	/** Answers INVESTIGATE and KNOWLEDGE with no text and no tool call each. */
	ScriptedChatModel quietPhasesAfterTriage() {
		return quiet().quiet();
	}

	/** Answers the warm-up with {@code answer} instead of {@code OK}, for every run from now on. */
	ScriptedChatModel warmUp(Function<Prompt, ChatResponse> answer) {
		this.warmUp = answer;
		return this;
	}

	/** Makes the warm-up wait {@code delay} before it answers {@code OK}. */
	ScriptedChatModel slowWarmUp(Duration delay) {
		return warmUp(prompt -> {
			sleep(delay);
			return response(new AssistantMessage("OK"));
		});
	}

	/** Makes the warm-up throw {@code failure}. */
	ScriptedChatModel warmUpFails(RuntimeException failure) {
		return warmUp(prompt -> {
			throw failure;
		});
	}

	List<Prompt> warmUps() {
		return this.warmUps;
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

	/** An evidence item citing the dossier (T5b): {@code toolCallId} and {@code tool} are both {@code "dossier"}. */
	static String dossierEvidence(String quote) {
		return dossierEvidence("dossier", quote);
	}

	/** An evidence item citing the dossier with {@code tool}. */
	static String dossierEvidence(String tool, String quote) {
		return "{\"toolCallId\":\"dossier\",\"tool\":\"" + tool + "\",\"quote\":"
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
