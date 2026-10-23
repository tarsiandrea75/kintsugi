package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.PlanCheck.addIfError;
import static dev.kintsugi.agent.loop.PlanCheck.unexpected;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.dossier.DossierRenderer;
import dev.kintsugi.agent.summary.ToolSummaries;

/**
 * Checks a report in code: its schema, then each evidence item against the recorded tool steps of this incident, or,
 * in dossier mode, against the rendered dossier (agent spec, "The report", "Evidence is checked"). The model proposes,
 * this code verifies.
 *
 * <p>
 * An evidence item cites a line by its number, in the summary of a step or in the dossier, and code fills in the
 * line's text as the item's {@code quote} (evidence-by-reference, ADR-013): a small model that had to copy the text
 * rebuilt it in its own format, while a number is an exact, checkable choice, and the stored quote is then verbatim by
 * construction.
 */
final class ReportCheck {

	/**
	 * The fields the model writes, in the order it writes them and the stored report keeps: the reasoning before the
	 * cause it leads to (report-field-order), since a model writes left to right.
	 */
	static final List<String> MODEL_FIELDS = List.of("summary", "evidence", "rootCauseService", "rootCauseCategory",
			"confidence");

	private static final Set<String> FIELDS = Set.copyOf(MODEL_FIELDS);

	/** The fields of an evidence item the model writes: no {@code quote}, which code fills in. */
	private static final Set<String> EVIDENCE_FIELDS = Set.of("toolCallId", "tool", "line", "why");

	/**
	 * The citation id of the dossier (T5b): an evidence item cites it with {@code toolCallId} and {@code tool} both
	 * {@code "dossier"}.
	 */
	static final String DOSSIER = "dossier";

	/**
	 * The most characters of a stored quote (T5c5, evidence-by-reference): the cited line's first 160 characters,
	 * counted as code points, as the schema counts them, so that a character is never split; nothing marks the cut, so
	 * the quote stays a prefix of the line the model saw.
	 */
	static final int MAX_QUOTE_LENGTH = 160;

	private static final JsonMapper JSON = JsonMapper.shared();

	private ReportCheck() {
	}

	/**
	 * What the check found.
	 * @param errors every rule the report breaks, each naming its field or evidence item; empty when it is valid
	 * @param dropped when the report is valid, the evidence items dropped because their line is not in their step's
	 * summary (T5c11, evidence-by-reference) or their tool is not their step's tool (T5c14), or because they cite a
	 * dossier the run has not got or a line it does not have (T5b), each named like an error; empty otherwise
	 * @param evidence when the report is valid, its verified evidence items, in order, each
	 * {@code {toolCallId, tool, line, quote, why}} with the quote code filled in; null otherwise
	 */
	record Result(List<String> errors, List<String> dropped, List<JsonNode> evidence) {
	}

	/**
	 * One evidence item, checked: exactly one of the two is set when the item breaks no fatal rule.
	 * @param dropped its only error, when it can be dropped
	 * @param verified the item as stored, its quote filled in, when it is verified
	 */
	private record Item(String dropped, JsonNode verified) {

		static final Item FATAL = new Item(null, null);

	}

	/** Every rule an explorer-mode {@code report} breaks; empty when the report is valid ({@link #check}). */
	static List<String> errors(JsonNode report, Map<Integer, ToolStep> toolSteps) {
		return check(report, toolSteps).errors();
	}

	/** Checks an explorer-mode {@code report}: it has no dossier to cite. */
	static Result check(JsonNode report, Map<Integer, ToolStep> toolSteps) {
		return check(report, toolSteps, AgentMode.EXPLORER, null);
	}

	/**
	 * Checks {@code report}. An evidence item citing a line its step's summary does not have is dropped rather than
	 * fatal (T5c11, evidence-by-reference): in the seventh live run two reports naming the right cause were rejected
	 * for one item each that did not hold up, while their other items did. So is an item whose tool
	 * is not the tool of the step it cites (T5c14): in the twelfth live run both reports of one incident named the
	 * right cause and lost it to one item citing a recent_events step as search_runbooks. The report stays valid
	 * while at least one verified item is left, or none is needed because both causes are UNKNOWN. Any other broken
	 * rule still rejects it, and then the errors name the dropped items too, so that the retry fixes them all.
	 * An item citing the dossier is dropped in the same way when the run has no dossier (explorer mode, or a gathering
	 * that gave none), when its tool is not {@code "dossier"}, or when the dossier has no such line (T5b).
	 * @param toolSteps this incident's tool steps by step number, refused calls included
	 * @param mode the run's mode: only dossier mode lets a {@code toolCallId} be {@code "dossier"}
	 * @param dossier the rendered dossier the model read, its lines numbered, without its introduction; null when
	 * there is none
	 */
	static Result check(JsonNode report, Map<Integer, ToolStep> toolSteps, AgentMode mode, String dossier) {
		if (report == null || !report.isObject()) {
			return invalid(List.of("the answer is not a JSON object"));
		}
		List<String> errors = new ArrayList<>();
		unexpected(report, FIELDS, "", errors);
		addIfError(errors, Causes.enumError("rootCauseService", report.get("rootCauseService"), Causes.SERVICES));
		addIfError(errors, Causes.enumError("rootCauseCategory", report.get("rootCauseCategory"), Causes.CATEGORIES));
		if (!Causes.string(report.get("summary"), 1, 500)) {
			errors.add("summary must be a string of 1 to 500 characters");
		}
		JsonNode confidence = report.get("confidence");
		if (confidence == null || !confidence.isNumber() || confidence.doubleValue() < 0
				|| confidence.doubleValue() > 1) {
			errors.add("confidence must be a number from 0 to 1");
		}
		JsonNode evidence = report.get("evidence");
		if (evidence == null || !evidence.isArray()) {
			errors.add("evidence must be an array of at most 5 items");
			return invalid(errors);
		}
		boolean unknown = Causes.UNKNOWN.equals(report.path("rootCauseService").asString(""))
				&& Causes.UNKNOWN.equals(report.path("rootCauseCategory").asString(""));
		if (evidence.size() > 5) {
			errors.add("evidence must hold at most 5 items");
		}
		else if (evidence.isEmpty() && !unknown) {
			errors.add("evidence must hold 1 to 5 items unless both causes are UNKNOWN");
		}
		boolean fatal = !errors.isEmpty();
		List<String> dropped = new ArrayList<>();
		List<JsonNode> verified = new ArrayList<>();
		for (int i = 0; i < evidence.size(); i++) {
			Item item = item(evidence.get(i), "evidence[" + i + "]", toolSteps, mode, dossier, errors);
			if (item.dropped() != null) {
				// Listed with the errors, in item order, for the retry when the report is rejected for another reason.
				errors.add(item.dropped());
				dropped.add(item.dropped());
			}
			else if (item.verified() == null) {
				fatal = true;
			}
			else {
				verified.add(item.verified());
			}
		}
		if (fatal || (verified.isEmpty() && !unknown)) {
			return invalid(errors);
		}
		return new Result(List.of(), List.copyOf(dropped), List.copyOf(verified));
	}

	private static Result invalid(List<String> errors) {
		return new Result(List.copyOf(errors), List.of(), null);
	}

	/**
	 * Checks one evidence item, adding to {@code errors} each rule it breaks.
	 * @return the dropped item, when it breaks no other rule but names another tool than its step's (T5c14) or cites
	 * a line its step's summary does not have (T5c11, evidence-by-reference), or is a dossier citation that does not
	 * verify (T5b); the verified item, its quote filled in; {@link Item#FATAL} when it broke a rule added to
	 * {@code errors}
	 */
	private static Item item(JsonNode item, String name, Map<Integer, ToolStep> toolSteps, AgentMode mode,
			String dossier, List<String> errors) {
		if (!item.isObject()) {
			errors.add(name + " must be an object {toolCallId, tool, line, why}");
			return Item.FATAL;
		}
		int before = errors.size();
		unexpected(item, EVIDENCE_FIELDS, name + ".", errors);
		JsonNode id = item.get("toolCallId");
		boolean citesDossier = id != null && id.isString() && DOSSIER.equals(id.asString());
		if (!citesDossier && (id == null || !id.isIntegralNumber() || !id.canConvertToInt())) {
			errors.add(name + ".toolCallId must be an integer" + (mode == AgentMode.DOSSIER ? " or \"dossier\"" : ""));
		}
		if (item.get("tool") == null || !item.get("tool").isString()) {
			errors.add(name + ".tool must be a string");
		}
		JsonNode line = item.get("line");
		if (line == null || !line.isIntegralNumber() || !line.canConvertToInt() || line.intValue() < 1) {
			errors.add(name + ".line must be an integer of at least 1");
		}
		if (!Causes.string(item.get("why"), 0, 200)) {
			errors.add(name + ".why must be a string of at most 200 characters");
		}
		if (errors.size() > before) {
			return Item.FATAL;
		}
		String tool = item.get("tool").asString();
		int n = line.intValue();
		if (citesDossier) {
			String dropped = dossierItem(name, tool, mode, dossier);
			if (dropped != null) {
				return new Item(dropped, null);
			}
			return DossierRenderer.line(dossier, n)
				.map(text -> verified(item, text))
				.orElseGet(() -> new Item(name + ": line " + n + " is not in the dossier", null));
		}
		int stepNo = id.intValue();
		ToolStep step = toolSteps.get(stepNo);
		if (step == null) {
			errors.add(name + ".toolCallId " + stepNo + " is not a tool call of this incident");
			return Item.FATAL;
		}
		if (step.refused()) {
			errors.add(name + ".toolCallId " + stepNo + " was refused (" + step.outcome() + ") and did not run");
			return Item.FATAL;
		}
		if (!step.tool().equals(tool)) {
			return new Item(name + ".tool is " + Causes.shown(item.get("tool")).replace("\"", "") + ", but step "
					+ stepNo + " is " + step.tool(), null);
		}
		// The summary is the text the model saw, numbered by code ("Summaries built in code").
		Optional<String> cited = ToolSummaries.line(step.summary(), n);
		return cited.map(text -> verified(item, text))
			.orElseGet(() -> new Item(name + ": line " + n + " is not in the output of step " + stepNo, null));
	}

	/**
	 * Checks what a citation of the dossier needs besides its line, once it passes every other rule (T5b); every
	 * failure is droppable, as a line that does not exist (T5c11) or a wrong tool (T5c14) is.
	 * @return the item's only error, or null when the run has a dossier and the item names it as its tool
	 */
	private static String dossierItem(String name, String tool, AgentMode mode, String dossier) {
		if (mode != AgentMode.DOSSIER) {
			return name + ".toolCallId dossier: this run has no dossier";
		}
		if (dossier == null) {
			return name + ".toolCallId dossier: the dossier was not gathered";
		}
		if (!DOSSIER.equals(tool)) {
			return name + ".tool is " + tool.replace("\"", "") + ", but toolCallId dossier is the dossier";
		}
		return null;
	}

	/**
	 * The verified item as stored, {@code {toolCallId, tool, line, quote, why}}: the model's four fields, and the
	 * {@code quote} code fills in, the cited line's text without its number, cut to its first
	 * {@value #MAX_QUOTE_LENGTH} characters.
	 */
	private static Item verified(JsonNode item, String text) {
		ObjectNode stored = JSON.createObjectNode();
		stored.set("toolCallId", item.get("toolCallId"));
		stored.set("tool", item.get("tool"));
		stored.set("line", item.get("line"));
		stored.put("quote", capped(text));
		stored.set("why", item.get("why"));
		return new Item(null, stored);
	}

	/** {@code text} cut to its first {@value #MAX_QUOTE_LENGTH} code points, so that no character is split. */
	static String capped(String text) {
		return text.codePointCount(0, text.length()) <= MAX_QUOTE_LENGTH ? text
				: text.substring(0, text.offsetByCodePoints(0, MAX_QUOTE_LENGTH));
	}

}
