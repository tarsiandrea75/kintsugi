package dev.kintsugi.agent.loop;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.incident.IncidentRepository;
import dev.kintsugi.agent.incident.IncidentRunner;
import dev.kintsugi.agent.incident.IncidentStatus;
import dev.kintsugi.agent.incident.OpenIncident;
import dev.kintsugi.agent.incident.StepRow;
import dev.kintsugi.agent.lab.LabTools;
import dev.kintsugi.agent.lab.LabToolsUnavailableException;
import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.lab.ToolResult;
import dev.kintsugi.agent.summary.ToolSummaries;

/**
 * The diagnosing loop (agent spec, "Phases and their tools" to "One log line per step"; ADR-010, "The loop"). Our code
 * drives it, not Spring AI's tool loop: it calls the {@code ChatModel} directly, checks every tool call the model
 * requests, runs the allowed ones one at a time through {@link LabTools}, and records every step before the next one
 * starts.
 *
 * <p>
 * {@code TRIAGE}, {@code INVESTIGATE} and {@code KNOWLEDGE} each start with a schema-constrained plan, then let the
 * model call the phase's tools within the phase's allowance and the incident's tool budget; the model sees each result
 * only as its code-built summary, in a tool-response message. {@code CONCLUDE} asks for the report, which code checks
 * against the recorded outputs. Every budget ends the run with an explicit status, except the tool budget, which moves
 * the run to {@code CONCLUDE} so that it can still be scored.
 */
public class AgentLoop implements IncidentRunner, AutoCloseable {

	/** Incidents waiting for the loop's one thread; the database already admits one {@code RUNNING} at a time. */
	static final int RUN_QUEUE = 2;

	static final String PLAN_INVALID = "plan invalid twice";

	static final String REPORT_INVALID = "report invalid twice";

	private static final List<Phase> EVIDENCE_PHASES = List.of(Phase.TRIAGE, Phase.INVESTIGATE, Phase.KNOWLEDGE);

	/** A tool name that is safe to print as is in a summary header or a log line; any other is quoted. */
	private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

	private static final JsonMapper JSON = JsonMapper.shared();

	private final IncidentRepository incidents;

	private final ModelRouter router;

	private final LabTools labTools;

	private final LoopSettings settings;

	private final Clock clock;

	private final ThreadPoolExecutor runs;

	/** Runs each model call, so that the loop can stop waiting for it at its time budget. */
	private final ExecutorService modelCalls;

	public AgentLoop(IncidentRepository incidents, ModelRouter router, LabTools labTools, LoopSettings settings,
			Clock clock) {
		this.incidents = incidents;
		this.router = router;
		this.labTools = labTools;
		this.settings = settings;
		this.clock = clock;
		this.runs = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(RUN_QUEUE),
				Thread.ofPlatform().name("agent-loop-", 1).daemon().factory());
		this.modelCalls = Executors.newCachedThreadPool(Thread.ofPlatform().name("agent-model-call-", 1).daemon().factory());
	}

	/**
	 * Queues the run and returns at once. A full queue throws {@link java.util.concurrent.RejectedExecutionException},
	 * which the API turns into a {@code FAILED} incident.
	 */
	@Override
	public void start(UUID incidentId) {
		this.runs.execute(() -> run(incidentId));
	}

	@Override
	public void close() {
		this.runs.shutdownNow();
		this.modelCalls.shutdownNow();
	}

	/**
	 * Runs the incident to its end, on the caller's thread, and stores the end.
	 * @return the status it ended with, or null when the incident was not {@code RUNNING}
	 */
	IncidentStatus run(UUID incidentId) {
		Optional<OpenIncident> open;
		try {
			open = this.incidents.findRunning(incidentId);
		}
		catch (RuntimeException unreadable) {
			log.error("incident={} event=run_not_started reason=\"the incident could not be read\"", incidentId,
					unreadable);
			return null;
		}
		if (open.isEmpty()) {
			log.warn("incident={} event=run_not_started reason=\"the incident is not RUNNING\"", incidentId);
			return null;
		}
		Run run = new Run(open.get());
		IncidentStatus status;
		String reason = null;
		String report = null;
		try {
			report = investigate(run);
			status = IncidentStatus.COMPLETED;
		}
		catch (RunEnd end) {
			status = end.status;
			reason = end.reason;
		}
		catch (RuntimeException unexpected) {
			log.error("incident={} event=run_failed", incidentId, unexpected);
			status = IncidentStatus.FAILED;
			reason = "unexpected error: " + describe(unexpected);
		}
		finish(run, status, reason, report);
		return status;
	}

	private String investigate(Run run) {
		for (Phase phase : EVIDENCE_PHASES) {
			List<ToolCallback> tools = tools(phase);
			plan(run, phase);
			act(run, phase, tools);
			if (run.toolsLeft == 0) {
				budgetStep(run, phase, "the incident's tool budget of " + LoopSettings.TOOL_BUDGET
						+ " calls is used up; the evidence phases end and the run moves to CONCLUDE");
				break;
			}
		}
		return conclude(run);
	}

	private List<ToolCallback> tools(Phase phase) {
		try {
			return this.labTools.toolsFor(phase);
		}
		catch (LabToolsUnavailableException unavailable) {
			throw new RunEnd(IncidentStatus.FAILED, "lab tools unavailable: " + unavailable.getMessage());
		}
	}

	/** The phase's plan: one call constrained by the plan schema, checked in code, with one retry. */
	private void plan(Run run, Phase phase) {
		List<String> errors = List.of();
		for (int attempt = 1; attempt <= 2; attempt++) {
			Prompts.State state = run.state(phase, LoopSettings.ALLOWANCES.get(phase), 0);
			Answer answer = call(run, ModelTask.PLAN, phase,
					List.of(run.system, new UserMessage(Prompts.plan(state, errors))), List.of(),
					Schemas.plan(phase));
			JsonNode plan = Answers.json(answer.text());
			errors = PlanCheck.errors(plan, phase);
			write(run, new StepRow(run.steps + 1, "PLAN", phase.name(), answer.model(), answer.tokens(), null, null,
					null, null, verdict(errors), answer.text(), answer.latencyMs(), now()));
			if (errors.isEmpty()) {
				run.plan = JSON.writeValueAsString(plan);
				return;
			}
		}
		throw new RunEnd(IncidentStatus.FAILED, PLAN_INVALID);
	}

	/**
	 * The phase's tool calls. The model is offered the phase's tools only; each call it requests is checked in order
	 * (budget, then phase) and runs only when both allow it. The phase ends when the model answers without a tool call
	 * or nothing is left of the phase's allowance or of the incident's budget.
	 */
	private void act(Run run, Phase phase, List<ToolCallback> tools) {
		int phaseLeft = LoopSettings.ALLOWANCES.get(phase);
		AssistantMessage lastAnswer = null;
		List<ToolResponse> lastResponses = List.of();
		while (phaseLeft > 0 && run.toolsLeft > 0) {
			List<Message> messages = new ArrayList<>();
			messages.add(run.system);
			messages.add(new UserMessage(Prompts.act(run.state(phase, phaseLeft, lastResponses.size()))));
			if (lastAnswer != null) {
				// The previous answer's calls and what they gave, as the model expects tool results.
				messages.add(lastAnswer);
				messages.add(ToolResponseMessage.builder().responses(lastResponses).build());
			}
			Answer answer = call(run, ModelTask.ACT, phase, messages, tools, null);
			List<ToolCall> calls = answer.message().getToolCalls();
			write(run, new StepRow(run.steps + 1, "ACT", phase.name(), answer.model(), answer.tokens(), null, null,
					null, null, calls.isEmpty() ? "no tool call; the phase ends" : calls.size() + " tool call(s)",
					actOutput(answer), answer.latencyMs(), now()));
			if (calls.isEmpty()) {
				return;
			}
			List<ToolResponse> responses = new ArrayList<>();
			boolean exhausted = false;
			for (ToolCall call : calls) {
				String summary;
				if (exhausted || phaseLeft == 0 || run.toolsLeft == 0) {
					// Nothing left to consume: refused, and so is every later call of this answer.
					exhausted = true;
					summary = refuse(run, phase, call, answer.model(), "REFUSED_BUDGET",
							"no tool calls are left in this phase or in the incident");
				}
				else {
					phaseLeft--;
					run.toolsLeft--;
					summary = phase.tools().contains(call.name()) ? runTool(run, phase, call, answer.model())
							: refuse(run, phase, call, answer.model(), "REFUSED_PHASE", label(call.name())
									+ " is not available in " + phase + "; this phase offers "
									+ String.join(", ", phase.tools()));
				}
				responses.add(new ToolResponse(call.id() == null ? "" : call.id(), String.valueOf(call.name()), summary));
			}
			lastAnswer = answer.message();
			lastResponses = responses;
			if (exhausted) {
				return;
			}
		}
	}

	/** Runs one checked call through mcp-tools and records the full output with the summary the model will see. */
	private String runTool(Run run, Phase phase, ToolCall call, String model) {
		checkTime(run);
		ToolResult result = this.labTools.call(call.name(), call.arguments());
		int stepNo = run.steps + 1;
		String summary = ToolSummaries.summarize(stepNo, result.tool(), result.outcome(), result.rawOutput());
		write(run, new StepRow(stepNo, "TOOL_CALL", phase.name(), model, null, result.tool(), result.args(),
				result.rawOutput(), result.outcome().name(), summary, null, (int) result.durationMs(), now()));
		run.toolSteps.put(stepNo, new ToolStep(stepNo, result.tool(), result.outcome().name(), result.rawOutput()));
		run.evidence.add(summary);
		return summary;
	}

	/** Records a call that did not run, and the summary that tells the model why. */
	private String refuse(Run run, Phase phase, ToolCall call, String model, String outcome, String why) {
		int stepNo = run.steps + 1;
		String summary = "toolCallId=" + stepNo + " tool=" + label(call.name()) + " outcome=" + outcome
				+ "\nthe call was refused and did not run: " + why;
		write(run, new StepRow(stepNo, "TOOL_CALL", phase.name(), model, null, call.name(), jsonArgs(call.arguments()),
				null, outcome, summary, null, 0, now()));
		run.toolSteps.put(stepNo, new ToolStep(stepNo, String.valueOf(call.name()), outcome, null));
		run.evidence.add(summary);
		return summary;
	}

	/** The report: one call without tools, constrained by the report schema, checked in code, with one retry. */
	private String conclude(Run run) {
		List<String> errors = List.of();
		for (int attempt = 1; attempt <= 2; attempt++) {
			Prompts.State state = run.state(Phase.CONCLUDE, 0, 0);
			Answer answer = call(run, ModelTask.REPORT, Phase.CONCLUDE,
					List.of(run.system, new UserMessage(Prompts.report(state, errors))), List.of(), Schemas.REPORT);
			JsonNode report = Answers.json(answer.text());
			errors = ReportCheck.errors(report, run.toolSteps);
			write(run, new StepRow(run.steps + 1, "REPORT", Phase.CONCLUDE.name(), answer.model(), answer.tokens(),
					null, null, null, null, verdict(errors), answer.text(), answer.latencyMs(), now()));
			if (errors.isEmpty()) {
				return stored(run, report);
			}
		}
		throw new RunEnd(IncidentStatus.INVALID_REPORT, REPORT_INVALID);
	}

	/** The report as stored: the model's fields, with {@code incidentId} and {@code stepsUsed} filled in by code. */
	private String stored(Run run, JsonNode report) {
		ObjectNode stored = JSON.createObjectNode();
		stored.put("incidentId", run.incident.id().toString());
		ReportCheck.MODEL_FIELDS.forEach(field -> stored.set(field, report.get(field)));
		stored.put("stepsUsed", run.steps);
		return JSON.writeValueAsString(stored);
	}

	/**
	 * One model call, within the model-call budget and the time budgets. A call never waits past its own time budget
	 * or the incident's, whichever ends first.
	 */
	private Answer call(Run run, ModelTask task, Phase phase, List<Message> messages, List<ToolCallback> tools,
			String outputSchema) {
		checkTime(run);
		if (run.modelCalls >= this.settings.maxModelCalls()) {
			throw new RunEnd(IncidentStatus.BUDGET_EXCEEDED,
					"the model-call budget of " + this.settings.maxModelCalls() + " calls is used up");
		}
		run.modelCalls++;
		ModelRoute route = this.router.route(task);
		Prompt prompt = new Prompt(messages, route.options(tools, outputSchema));
		Duration incidentLeft = this.settings.incidentTimeout().minus(elapsed(run));
		boolean incidentBound = incidentLeft.compareTo(route.callTimeout()) < 0;
		Duration limit = incidentBound ? incidentLeft : route.callTimeout();
		String what = "model call " + task + " in " + phase;
		long start = System.nanoTime();
		Future<ChatResponse> future = this.modelCalls.submit(() -> route.chatModel().call(prompt));
		ChatResponse response;
		try {
			response = future.get(limit.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException slow) {
			future.cancel(true);
			throw incidentBound ? incidentTimedOut("during " + what) : callTimedOut(what, limit);
		}
		catch (ExecutionException failed) {
			if (isTimeout(failed.getCause())) {
				throw callTimedOut(what, route.callTimeout());
			}
			throw new RunEnd(IncidentStatus.FAILED, what + " failed: " + describe(failed.getCause()));
		}
		catch (InterruptedException interrupted) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new RunEnd(IncidentStatus.FAILED, "the run was interrupted during " + what);
		}
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			throw new RunEnd(IncidentStatus.FAILED, what + " returned no answer");
		}
		AssistantMessage message = response.getResult().getOutput();
		Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
		return new Answer(message, message.getText() == null ? "" : message.getText(),
				usage == null ? null : usage.getTotalTokens(), route.model(),
				(int) ((System.nanoTime() - start) / 1_000_000));
	}

	/** Ends the run {@code TIMED_OUT} once the incident has used up its time budget. */
	private void checkTime(Run run) {
		if (elapsed(run).compareTo(this.settings.incidentTimeout()) >= 0) {
			throw incidentTimedOut("before the next call");
		}
	}

	private RunEnd incidentTimedOut(String when) {
		return new RunEnd(IncidentStatus.TIMED_OUT,
				"the incident ran past its time budget of " + shown(this.settings.incidentTimeout()) + ", " + when);
	}

	private static RunEnd callTimedOut(String what, Duration limit) {
		return new RunEnd(IncidentStatus.TIMED_OUT, what + " took longer than " + shown(limit));
	}

	private void budgetStep(Run run, Phase phase, String summary) {
		write(run, new StepRow(run.steps + 1, "BUDGET", phase.name(), null, null, null, null, null, null, summary,
				null, null, now()));
	}

	/** Writes the step, then logs its one line: tool outputs, summaries and model answers stay in the table. */
	private void write(Run run, StepRow step) {
		this.incidents.insertStep(run.incident.id(), step);
		run.steps = step.stepNo();
		log.info("incident={} step={} phase={} kind={} tool={} outcome={} latencyMs={} tokens={}", run.incident.id(),
				step.stepNo(), step.phase(), step.kind(), step.toolName() == null ? "-" : label(step.toolName()),
				step.toolOutcome() == null ? "-" : step.toolOutcome(),
				step.latencyMs() == null ? 0 : step.latencyMs(), step.tokens() == null ? 0 : step.tokens());
	}

	/**
	 * Stores the end. If the store fails here, the incident stays {@code RUNNING} until the next agent start, whose
	 * restart sweep ends it {@code FAILED}.
	 */
	private void finish(Run run, IncidentStatus status, String reason, String report) {
		try {
			if (status == IncidentStatus.COMPLETED) {
				this.incidents.complete(run.incident.id(), report, now());
			}
			else {
				this.incidents.end(run.incident.id(), status, reason, now());
			}
		}
		catch (RuntimeException notStored) {
			log.error("incident={} event=run_end_not_stored status={}", run.incident.id(), status, notStored);
		}
		log.info("incident={} event=run_ended status={} reason=\"{}\" durationMs={}", run.incident.id(), status,
				reason == null ? "-" : reason, elapsed(run).toMillis());
	}

	private Duration elapsed(Run run) {
		return Duration.between(run.incident.startedAt(), this.clock.instant());
	}

	private Instant now() {
		return this.clock.instant();
	}

	private static String verdict(List<String> errors) {
		return errors.isEmpty() ? "valid" : "invalid: " + String.join("; ", errors);
	}

	/** What an {@code ACT} step stores as the model's answer: its text and the calls it requested. */
	private static String actOutput(Answer answer) {
		ObjectNode output = JSON.createObjectNode();
		output.put("text", answer.text());
		ArrayNode calls = output.putArray("toolCalls");
		for (ToolCall call : answer.message().getToolCalls()) {
			calls.addObject().put("name", call.name()).put("arguments", call.arguments());
		}
		return JSON.writeValueAsString(output);
	}

	/** The model's arguments as JSON for {@code tool_args}: as written when they are JSON, else as one JSON string. */
	private static String jsonArgs(String arguments) {
		if (arguments == null || arguments.isBlank()) {
			return "{}";
		}
		try {
			return JSON.writeValueAsString(JSON.readTree(arguments));
		}
		catch (JacksonException notJson) {
			return JSON.writeValueAsString(arguments);
		}
	}

	/** A model-written tool name, printed as is when plain, else as a JSON literal cut at 64 characters. */
	private static String label(String name) {
		if (name != null && PLAIN_NAME.matcher(name).matches()) {
			return name;
		}
		String text = String.valueOf(name);
		return JSON.writeValueAsString(text.length() > 64 ? text.substring(0, 64) : text);
	}

	private static boolean isTimeout(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
					|| cause instanceof TimeoutException) {
				return true;
			}
		}
		return false;
	}

	/** The exception and its root cause, on one line. */
	private static String describe(Throwable failed) {
		Throwable root = failed;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String outer = failed.getClass().getSimpleName() + ": " + failed.getMessage();
		String text = root == failed ? outer
				: outer + " (cause " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")";
		return text.replaceAll("\\s+", " ").strip();
	}

	/** A budget as the reasons write it: {@code 10 min}, {@code 90 s} or {@code 200 ms}. */
	static String shown(Duration duration) {
		long ms = duration.toMillis();
		if (ms % 60_000 == 0) {
			return ms / 60_000 + " min";
		}
		return ms % 1000 == 0 ? ms / 1000 + " s" : ms + " ms";
	}

	/** One run's state: what the prompts are rebuilt from, and what the budgets count. */
	private static final class Run {

		final OpenIncident incident;

		final SystemMessage system;

		/** Every tool step, refusals included, by step number: what the evidence check reads. */
		final Map<Integer, ToolStep> toolSteps = new LinkedHashMap<>();

		/** The summaries of the tool steps, in step order. */
		final List<String> evidence = new ArrayList<>();

		int steps;

		int modelCalls;

		int toolsLeft = LoopSettings.TOOL_BUDGET;

		String plan;

		Run(OpenIncident incident) {
			this.incident = incident;
			this.system = new SystemMessage(Prompts.system(incident.symptom(), incident.sinceMinutes()));
		}

		/** The state for a prompt; the newest {@code inToolResponse} summaries travel in the tool-response message. */
		Prompts.State state(Phase phase, int phaseLeft, int inToolResponse) {
			return new Prompts.State(phase, phaseLeft, this.toolsLeft, this.plan,
					List.copyOf(this.evidence.subList(0, this.evidence.size() - inToolResponse)));
		}

	}

	private record Answer(AssistantMessage message, String text, Integer tokens, String model, int latencyMs) {
	}

	/** Ends the run with a status other than {@code COMPLETED}, and its reason. */
	private static final class RunEnd extends RuntimeException {

		final IncidentStatus status;

		final String reason;

		RunEnd(IncidentStatus status, String reason) {
			super(reason, null, false, false);
			this.status = status;
			this.reason = reason;
		}

	}

}
