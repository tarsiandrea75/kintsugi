package dev.kintsugi.agent.loop;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.springframework.ai.chat.metadata.Usage;
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
		return cutWith("length", text, calls);
	}

	/**
	 * Answers {@code text} and these tool calls, cut at the answer's cap with this finish reason: {@code length} on
	 * Ollama, {@code max_tokens} on Anthropic (Spring AI passes the provider's stop reason as it is).
	 */
	ScriptedChatModel cutWith(String finishReason, String text, ToolCall... calls) {
		return cutUsing(finishReason, 10, 5, text, calls);
	}

	/**
	 * Answers {@code text} and these tool calls, cut with this finish reason and this usage: with prompt and completion
	 * tokens that add up to the route's context window, the window filled before the answer ended (context-full-cut T2).
	 */
	ScriptedChatModel cutUsing(String finishReason, int promptTokens, int completionTokens, String text,
			ToolCall... calls) {
		AssistantMessage message = AssistantMessage.builder().content(text).toolCalls(List.of(calls)).build();
		this.script.add(prompt -> new ChatResponse(
				List.of(new Generation(message, ChatGenerationMetadata.builder().finishReason(finishReason).build())),
				ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, completionTokens)).build()));
		return this;
	}

	/**
	 * Answers {@code text} with this usage instead of the usual 10 and 5 tokens; with null, the response carries no
	 * usage, so its metadata reports Spring AI's {@code EmptyUsage}, as a provider that counts nothing does.
	 */
	ScriptedChatModel metered(Usage usage, String text) {
		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
		if (usage != null) {
			metadata.usage(usage);
		}
		this.script.add(prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
				metadata.build()));
		return this;
	}

	/**
	 * Answers {@code text} after {@code thinking}, as Spring AI 2.0.1's {@code OllamaChatModel} returns a thinking
	 * model's answer: the content is the answer, and the thinking travels apart, as the message's {@code thinking}
	 * property and the generation's {@code thinking} metadata.
	 */
	ScriptedChatModel thinking(String thinking, String text) {
		AssistantMessage message = AssistantMessage.builder()
			.content(text)
			.properties(Map.of("thinking", thinking))
			.build();
		this.script.add(prompt -> new ChatResponse(
				List.of(new Generation(message,
						ChatGenerationMetadata.builder().finishReason("stop").metadata("thinking", thinking).build())),
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

	/** A plain report with no alternatives, whose evidence items are already JSON objects. */
	static String report(String service, String category, String... evidence) {
		return rankedReport(service, category, List.of(), evidence);
	}

	/**
	 * A plain report ranking {@code alternatives}, each {@code service|category}, after its cause (ranked-report T2;
	 * ADR-025).
	 */
	static String rankedReport(String service, String category, List<String> alternatives, String... evidence) {
		String ranked = alternatives.stream().map(pair -> pair.split("\\|", -1))
			.map(pair -> "{\"rootCauseService\":\"" + pair[0] + "\",\"rootCauseCategory\":\"" + pair[1] + "\"}")
			.collect(Collectors.joining(","));
		return "{\"rootCauseService\":\"" + service + "\",\"rootCauseCategory\":\"" + category
				+ "\",\"alternatives\":[" + ranked + "],\"summary\":\"what the evidence shows\",\"confidence\":0.7,"
				+ "\"evidence\":[" + String.join(",", evidence) + "]}";
	}

	/** A hybrid report (hybrid T3; ADR-019): the plain report as it was before the alternatives, which it never has. */
	static String hybridReport(String service, String category, String... evidence) {
		return "{\"rootCauseService\":\"" + service + "\",\"rootCauseCategory\":\"" + category
				+ "\",\"summary\":\"what the evidence shows\",\"confidence\":0.7,\"evidence\":["
				+ String.join(",", evidence) + "]}";
	}

	/** An evidence item citing line {@code line} of step {@code toolCallId}'s summary (evidence-by-reference). */
	static String evidence(int toolCallId, String tool, int line) {
		return "{\"toolCallId\":" + toolCallId + ",\"tool\":\"" + tool + "\",\"line\":" + line
				+ ",\"why\":\"it shows the cause\"}";
	}

	/**
	 * An evidence item citing line {@code D<line>} of the dossier (T5b): {@code toolCallId} and {@code tool} are both
	 * {@code "dossier"}.
	 */
	static String dossierEvidence(int line) {
		return dossierEvidence("dossier", line);
	}

	/** An evidence item citing the dossier with {@code tool}. */
	static String dossierEvidence(String tool, int line) {
		return "{\"toolCallId\":\"dossier\",\"tool\":\"" + tool + "\",\"line\":" + line
				+ ",\"why\":\"it shows the cause\"}";
	}

	static String unknownReport() {
		return "{\"rootCauseService\":\"UNKNOWN\",\"rootCauseCategory\":\"UNKNOWN\",\"alternatives\":[],"
				+ "\"summary\":\"no clear cause\",\"confidence\":0.1,\"evidence\":[]}";
	}

	/** {@link #unknownReport} as a hybrid report writes it: no alternatives. */
	static String unknownHybridReport() {
		return "{\"rootCauseService\":\"UNKNOWN\",\"rootCauseCategory\":\"UNKNOWN\",\"summary\":\"no clear cause\","
				+ "\"confidence\":0.1,\"evidence\":[]}";
	}

	/**
	 * An assessed report (service-assessment T2): its assessments, already JSON members, then the summary and the
	 * cause, in the order the schema asks for them.
	 */
	static String assessedReport(String service, String category, String... assessments) {
		return "{\"assessments\":{" + String.join(",", assessments) + "},\"summary\":\"what the sheets show\","
				+ "\"rootCauseService\":\"" + service + "\",\"rootCauseCategory\":\"" + category
				+ "\",\"confidence\":0.7}";
	}

	/** One service's assessment, the member {@code "<service>":{role, line, why}}, citing line {@code D<line>}. */
	static String assessment(String service, String role, int line) {
		return "\"" + service + "\":{\"role\":\"" + role + "\",\"line\":" + line + ",\"why\":\"its own facts\"}";
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
