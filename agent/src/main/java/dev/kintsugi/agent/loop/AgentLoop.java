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
 * A run starts with one warm-up request, which loads the model and counts against no budget. {@code TRIAGE},
 * {@code INVESTIGATE} and {@code KNOWLEDGE} then let the model call the phase's tools within the phase's allowance and
 * the incident's tool budget; the first answer of each phase also writes the phase's plan in its text, which code
 * checks and records but which never ends the run. The model sees each result only as its code-built summary, in a
 * tool-response message. {@code CONCLUDE} asks for the report, which code checks against the recorded outputs. Every
 * budget ends the run with an explicit status, except the tool budget, which moves the run to {@code CONCLUDE} so that
 * it can still be scored.
 */
public class AgentLoop implements IncidentRunner, AutoCloseable {

	/** Incidents waiting for the loop's one thread; the database already admits one {@code RUNNING} at a time. */
	static final int RUN_QUEUE = 2;

	static final String REPORT_INVALID = "report invalid twice";

	/** How reasons name the warm-up request. */
	static final String WARM_UP = "the model warm-up";

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
		catch (RuntimeException | Error unexpected) {
			// An Error too (a missing class, a stack overflow): the run must still end, or its RUNNING row would block
			// every later incident until the agent restarts. The loop's thread survives to serve the next run.
			log.error("incident={} event=run_failed", incidentId, unexpected);
			status = IncidentStatus.FAILED;
			reason = "unexpected error: " + describe(unexpected);
		}
		finish(run, status, reason, report);
		return status;
	}

	private String investigate(Run run) {
		warmUp(run);
		for (Phase phase : EVIDENCE_PHASES) {
			List<ToolCallback> tools = tools(phase);
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

	/**
	 * Loads the model with one tiny request before the run, on the route of its first call and with the same options,
	 * so that the first real call does not pay the load. It counts against no budget: the incident's time budget starts
	 * when it ends. It is bounded by its own timeout, and a failure ends the run {@code FAILED} with the reason.
	 */
	private void warmUp(Run run) {
		ModelRoute route = this.router.route(ModelTask.ACT);
		Prompt prompt = new Prompt(List.of(new UserMessage(Prompts.WARM_UP)), route.warmUpOptions());
		Duration limit = this.settings.warmUpTimeout();
		long start = System.nanoTime();
		Future<ChatResponse> future = this.modelCalls.submit(() -> route.chatModel().call(prompt));
		try {
			future.get(limit.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException slow) {
			future.cancel(true);
			throw new RunEnd(IncidentStatus.FAILED, WARM_UP + " took longer than " + shown(limit));
		}
		catch (ExecutionException failed) {
			throw new RunEnd(IncidentStatus.FAILED, isTimeout(failed.getCause())
					? WARM_UP + " took longer than " + shown(limit) : WARM_UP + " failed: " + describe(failed.getCause()));
		}
		catch (InterruptedException interrupted) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new RunEnd(IncidentStatus.FAILED, "the run was interrupted during " + WARM_UP);
		}
		log.info("incident={} event=model_warmed_up model={} latencyMs={}", run.incident.id(), route.model(),
				(System.nanoTime() - start) / 1_000_000);
		run.budgetStart = now();
	}

	/**
	 * The phase's tool calls. The model is offered the phase's tools only; each call it requests is checked in order
	 * (budget, then phase) and runs only when both allow it. The phase ends when the model answers without a tool call
	 * or nothing is left of the phase's allowance or of the incident's budget. The first answer also carries the
	 * phase's plan in its text: it is checked and recorded on its {@code ACT} step, and only a valid plan is shown in
	 * the phase's later calls; a missing or invalid one never ends the run, and is not asked for again. The next phase
	 * starts without it.
	 */
	private void act(Run run, Phase phase, List<ToolCallback> tools) {
		int phaseLeft = LoopSettings.ALLOWANCES.get(phase);
		// Each phase plans afresh from the evidence: re-showing the previous plan anchored the model on it (T5c8).
		run.plan = null;
		AssistantMessage lastAnswer = null;
		List<ToolResponse> lastResponses = List.of();
		while (phaseLeft > 0 && run.toolsLeft > 0) {
			boolean withPlan = lastAnswer == null;
			// What changes least comes first, so that Ollama reuses the prompt it has already read: the system
			// message, the evidence before the previous answer, which only grows, then the previous answer's calls
			// and what they gave, as the model expects tool results, and last the instruction with the budgets left.
			List<Message> messages = run.opening(lastResponses.size());
			if (lastAnswer != null) {
				messages.add(lastAnswer);
				messages.add(ToolResponseMessage.builder().responses(lastResponses).build());
			}
			messages.add(new UserMessage(Prompts.act(run.state(phase, phaseLeft), withPlan)));
			Answer answer = call(run, ModelTask.ACT, phase, withPlan, messages, tools, null);
			List<ToolCall> calls = answer.message().getToolCalls();
			String note = calls.isEmpty() ? "no tool call; the phase ends" : calls.size() + " tool call(s)";
			if (answer.cut()) {
				note += "; answer cut at " + answer.cap() + " tokens";
			}
			if (withPlan) {
				note += "; " + plan(run, phase, answer.text());
			}
			write(run, new StepRow(run.steps + 1, "ACT", phase.name(), answer.model(), answer.tokens(), null, null,
					null, null, note, actOutput(answer), answer.latencyMs(), now()));
			if (calls.isEmpty()) {
				return;
			}
			List<ToolResponse> responses = new ArrayList<>();
			boolean exhausted = false;
			for (ToolCall call : calls) {
				String summary;
				// A call without a name is refused like any tool the phase does not offer (List.contains(null) throws).
				boolean offered = call.name() != null && phase.tools().contains(call.name());
				Waste waste = offered ? waste(run, call) : null;
				if (exhausted || phaseLeft == 0 || run.toolsLeft == 0) {
					// Nothing left to consume: refused, and so is every later call of this answer.
					exhausted = true;
					summary = refuse(run, phase, call, answer.model(), "REFUSED_BUDGET",
							"no tool calls are left in this phase or in the incident");
				}
				else if (waste != null && run.uncountedLeft > 0) {
					// A placeholder or a repeat consumes nothing, up to a cap, so that a model that keeps sending one
					// still spends its budget and the phase ends (T5c9).
					run.uncountedLeft--;
					summary = refuse(run, phase, call, answer.model(), waste.outcome(), waste.why());
				}
				else {
					phaseLeft--;
					run.toolsLeft--;
					if (!offered) {
						summary = refuse(run, phase, call, answer.model(), "REFUSED_PHASE", label(call.name())
								+ " is not available in " + phase + "; this phase offers "
								+ String.join(", ", phase.tools()));
					}
					else if (waste != null) {
						summary = refuse(run, phase, call, answer.model(), waste.outcome(), waste.why()
								+ "; it counts against the budget, since the " + LoopSettings.UNCOUNTED_REFUSALS
								+ " uncounted refusals of this incident are used up");
					}
					else {
						summary = runTool(run, phase, call, answer.model());
					}
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

	/**
	 * Reads and checks the plan in the text of a phase's first answer; a valid one becomes the phase's plan.
	 * @return the verdict for the {@code ACT} step: {@code plan valid}, {@code plan missing} or {@code plan invalid: ...}
	 */
	private static String plan(Run run, Phase phase, String text) {
		JsonNode plan = Answers.embedded(text);
		if (plan == null) {
			return "plan missing";
		}
		List<String> errors = PlanCheck.errors(plan, phase);
		if (!errors.isEmpty()) {
			return "plan invalid: " + String.join("; ", errors);
		}
		run.plan = JSON.writeValueAsString(plan);
		return "plan valid";
	}

	/**
	 * Why an offered call is not worth running, or null: an argument that is a placeholder, not a value, or the same
	 * tool and arguments as a call that already ran in this incident, whose result the model already has (T5c9).
	 */
	private static Waste waste(Run run, ToolCall call) {
		String placeholder = ToolArgs.placeholder(call.arguments());
		if (placeholder != null) {
			return new Waste("REFUSED_PLACEHOLDER", "argument " + placeholder + " is a placeholder, not a value; use a "
					+ "real value from an earlier tool result, such as an orderId in a recent_events result");
		}
		for (Ran earlier : run.ran) {
			if (earlier.tool().equals(call.name()) && ToolArgs.same(earlier.arguments(), call.arguments())) {
				return new Waste("REFUSED_DUPLICATE", "the same tool with the same arguments was already called as "
						+ "toolCallId " + earlier.stepNo() + "; read that result");
			}
		}
		return null;
	}

	/** Runs one checked call through mcp-tools and records the full output with the summary the model will see. */
	private String runTool(Run run, Phase phase, ToolCall call, String model) {
		checkTime(run);
		ToolResult result = this.labTools.call(call.name(), call.arguments());
		int stepNo = run.steps + 1;
		run.ran.add(new Ran(call.name(), call.arguments(), stepNo));
		String summary = ToolSummaries.summarize(stepNo, result.tool(), result.outcome(), result.rawOutput());
		write(run, new StepRow(stepNo, "TOOL_CALL", phase.name(), model, null, result.tool(), result.args(),
				result.rawOutput(), result.outcome().name(), summary, null, (int) result.durationMs(), now()));
		run.toolSteps.put(stepNo, new ToolStep(stepNo, result.tool(), result.outcome().name(), result.rawOutput(),
				summary));
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
		run.toolSteps.put(stepNo, new ToolStep(stepNo, String.valueOf(call.name()), outcome, null, summary));
		run.evidence.add(summary);
		return summary;
	}

	/** The report: one call without tools, constrained by the report schema, checked in code, with one retry. */
	private String conclude(Run run) {
		// The report is written from the evidence, not from the last phase's plan (T5c8).
		run.plan = null;
		List<String> errors = List.of();
		for (int attempt = 1; attempt <= 2; attempt++) {
			List<Message> messages = run.opening(0);
			messages.add(new UserMessage(Prompts.report(run.state(Phase.CONCLUDE, 0), errors)));
			Answer answer = call(run, ModelTask.REPORT, Phase.CONCLUDE, false, messages, List.of(), Schemas.REPORT);
			JsonNode report = Answers.json(answer.text());
			errors = ReportCheck.errors(report, run.toolSteps);
			if (!errors.isEmpty() && answer.cut()) {
				// A report cut at its cap is invalid JSON: the retry is told why, so that it writes a shorter one.
				errors = new ArrayList<>(errors);
				errors.addFirst("the answer was cut at " + answer.cap()
						+ " tokens before it ended; write a shorter report, with a shorter summary and fewer or shorter "
						+ "evidence items");
			}
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
	 * One model call, within the model-call budget and the time budgets, its answer capped by the route's
	 * {@code num_predict} for the task (the larger cap when the answer carries a plan). A call never waits past its task's
	 * deadline ({@link CallTimeouts}) or the incident's time left, whichever ends first; one it stops waiting for is
	 * interrupted, which closes its HTTP connection, so that Ollama stops working on it.
	 */
	private Answer call(Run run, ModelTask task, Phase phase, boolean withPlan, List<Message> messages,
			List<ToolCallback> tools, String outputSchema) {
		checkTime(run);
		if (run.modelCalls >= this.settings.maxModelCalls()) {
			throw new RunEnd(IncidentStatus.BUDGET_EXCEEDED,
					"the model-call budget of " + this.settings.maxModelCalls() + " calls is used up");
		}
		run.modelCalls++;
		ModelRoute route = this.router.route(task);
		int cap = route.numPredict().of(task, withPlan);
		Prompt prompt = new Prompt(messages, route.options(cap, tools, outputSchema));
		Duration deadline = route.callTimeouts().of(task);
		Duration incidentLeft = this.settings.incidentTimeout().minus(elapsed(run));
		boolean incidentBound = incidentLeft.compareTo(deadline) < 0;
		Duration limit = incidentBound ? incidentLeft : deadline;
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
				throw callTimedOut(what, deadline);
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
		// Ollama's done_reason "length": the answer reached its cap and was cut.
		boolean cut = response.getResult().getMetadata() != null
				&& "length".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason());
		return new Answer(message, message.getText() == null ? "" : message.getText(),
				usage == null ? null : usage.getTotalTokens(), route.model(),
				(int) ((System.nanoTime() - start) / 1_000_000), cut, cap);
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
				reason == null ? "-" : reason, Duration.between(run.incident.startedAt(), now()).toMillis());
	}

	/** The time the incident's budget has used: from the end of the warm-up, or from {@code started_at} before it. */
	private Duration elapsed(Run run) {
		return Duration.between(run.budgetStart, this.clock.instant());
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

	/** A budget as the reasons write it: {@code 30 min}, {@code 180 s} or {@code 200 ms}. */
	static String shown(Duration duration) {
		long ms = duration.toMillis();
		if (ms % 60_000 == 0 && ms >= 600_000) {
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

		/** Placeholder or repeated calls that may still be refused without consuming a unit (T5c9). */
		int uncountedLeft = LoopSettings.UNCOUNTED_REFUSALS;

		/** The calls that ran, with their arguments as the model wrote them, so that a repeat is caught (T5c9). */
		final List<Ran> ran = new ArrayList<>();

		String plan;

		/** Where the incident's time budget starts: the end of the warm-up, so the model's load is not counted. */
		Instant budgetStart;

		Run(OpenIncident incident) {
			this.incident = incident;
			this.budgetStart = incident.startedAt();
			this.system = new SystemMessage(Prompts.system(incident.symptom(), incident.sinceMinutes()));
		}

		/** The parts of the state the instruction shows. */
		Prompts.State state(Phase phase, int phaseLeft) {
			return new Prompts.State(phase, phaseLeft, this.toolsLeft, this.plan);
		}

		/**
		 * The prompt's opening, which only grows from call to call: the system message, then the evidence message
		 * when there is any. The newest {@code inToolResponse} summaries are left out: they travel in the
		 * tool-response message after the previous answer.
		 */
		List<Message> opening(int inToolResponse) {
			List<Message> messages = new ArrayList<>();
			messages.add(this.system);
			String evidence = Prompts.evidence(this.evidence.subList(0, this.evidence.size() - inToolResponse));
			if (evidence != null) {
				messages.add(new UserMessage(evidence));
			}
			return messages;
		}

	}

	/** A call that ran: its tool, its arguments as the model wrote them, and its step. */
	private record Ran(String tool, String arguments, int stepNo) {
	}

	/** Why a call is refused without running: its {@code tool_outcome} and the reason the model is shown. */
	private record Waste(String outcome, String why) {
	}

	/** A model answer; {@code cut} when it reached its {@code cap} of tokens before it ended. */
	private record Answer(AssistantMessage message, String text, Integer tokens, String model, int latencyMs,
			boolean cut, int cap) {
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
