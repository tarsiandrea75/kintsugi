package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.PlanCheck.addIfError;
import static dev.kintsugi.agent.loop.PlanCheck.unexpected;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.summary.ToolSummaries;

/**
 * Checks a report in code: its schema, then each evidence item against the recorded tool steps of this incident, or,
 * in dossier mode, against the rendered dossier (agent spec, "The report", "Evidence is checked"). The model proposes,
 * this code verifies.
 */
final class ReportCheck {

	/** The fields the model writes, in the stored report's order. */
	static final List<String> MODEL_FIELDS = List.of("rootCauseService", "rootCauseCategory", "summary",
			"confidence", "evidence");

	private static final Set<String> FIELDS = Set.copyOf(MODEL_FIELDS);

	private static final Set<String> EVIDENCE_FIELDS = Set.of("toolCallId", "tool", "quote", "why");

	/**
	 * The citation id of the dossier (T5b): an evidence item cites it with {@code toolCallId} and {@code tool} both
	 * {@code "dossier"}.
	 */
	static final String DOSSIER = "dossier";

	/** The fewest characters of an evidence quote. */
	static final int MIN_QUOTE_LENGTH = 8;

	/**
	 * The most characters of an evidence quote (T5c5): a distinctive fragment, not a whole line. In the third live run
	 * the model copied whole escaped log lines, about 200 tokens per quote, and the 512-token report never closed.
	 */
	static final int MAX_QUOTE_LENGTH = 160;

	private static final String TRUNCATED_SUFFIX = "\n[truncated]";

	private static final JsonMapper JSON = JsonMapper.shared();

	/** One escape a summary or a JSON text may hold: a backslash and one of {@code n t " \}. */
	private static final Pattern ESCAPE = Pattern.compile("\\\\([nt\"\\\\])");

	private ReportCheck() {
	}

	/**
	 * What the check found.
	 * @param errors every rule the report breaks, each naming its field or evidence item; empty when it is valid
	 * @param dropped when the report is valid, the evidence items dropped because their quote is not in their step
	 * (T5c11) or their tool is not their step's tool (T5c14), or because they cite a dossier the run has not got or
	 * quote text that is not in it (T5b), each named like an error; empty otherwise
	 * @param evidence when the report is valid, its verified evidence items, in order; null otherwise
	 */
	record Result(List<String> errors, List<String> dropped, List<JsonNode> evidence) {
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
	 * Checks {@code report}. An evidence item whose quote is not in its step's output, nor in the summary lines that
	 * copy it, is dropped rather than fatal (T5c11): in the seventh live run two reports naming the right cause were
	 * rejected for one item each whose quote was not verbatim, while their other items were. So is an item whose tool
	 * is not the tool of the step it cites (T5c14): in the twelfth live run both reports of one incident named the
	 * right cause and lost it to one item citing a recent_events step as search_runbooks. The report stays valid
	 * while at least one verified item is left, or none is needed because both causes are UNKNOWN. Any other broken
	 * rule still rejects it, and then the errors name the dropped items too, so that the retry fixes them all.
	 * An item citing the dossier is dropped in the same way when the run has no dossier (explorer mode, or a gathering
	 * that gave none), when its tool is not {@code "dossier"}, or when its quote is not in the dossier (T5b).
	 * @param toolSteps this incident's tool steps by step number, refused calls included
	 * @param mode the run's mode: only dossier mode lets a {@code toolCallId} be {@code "dossier"}
	 * @param dossier the rendered dossier the model read, without its introduction; null when there is none
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
			int before = errors.size();
			String droppable = item(evidence.get(i), "evidence[" + i + "]", toolSteps, mode, dossier, errors);
			if (droppable != null) {
				// Listed with the errors, in item order, for the retry when the report is rejected for another reason.
				errors.add(droppable);
				dropped.add(droppable);
			}
			else if (errors.size() > before) {
				fatal = true;
			}
			else {
				verified.add(evidence.get(i));
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
	 * @return the item's only error when it breaks no other rule but names another tool than its step's (T5c14) or
	 * quotes text that is not in its step (T5c11), or a dossier citation that does not verify (T5b), so that it can be
	 * dropped; null otherwise
	 */
	private static String item(JsonNode item, String name, Map<Integer, ToolStep> toolSteps, AgentMode mode,
			String dossier, List<String> errors) {
		if (!item.isObject()) {
			errors.add(name + " must be an object {toolCallId, tool, quote, why}");
			return null;
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
		JsonNode quote = item.get("quote");
		if (quote != null && quote.isString() && length(quote.asString()) > MAX_QUOTE_LENGTH) {
			errors.add(name + ".quote is longer than " + MAX_QUOTE_LENGTH + " characters; quote only its distinctive "
					+ "fragment, such as an exception name, a status or an error message, not the whole line");
		}
		else if (!Causes.string(quote, MIN_QUOTE_LENGTH, MAX_QUOTE_LENGTH)) {
			errors.add(name + ".quote must be a string of " + MIN_QUOTE_LENGTH + " to " + MAX_QUOTE_LENGTH
					+ " characters");
		}
		if (!Causes.string(item.get("why"), 0, 200)) {
			errors.add(name + ".why must be a string of at most 200 characters");
		}
		if (errors.size() > before) {
			return null;
		}
		String tool = item.get("tool").asString();
		if (citesDossier) {
			return dossierItem(name, tool, quote.asString(), mode, dossier);
		}
		int stepNo = id.intValue();
		ToolStep step = toolSteps.get(stepNo);
		if (step == null) {
			errors.add(name + ".toolCallId " + stepNo + " is not a tool call of this incident");
		}
		else if (step.refused()) {
			errors.add(name + ".toolCallId " + stepNo + " was refused (" + step.outcome() + ") and did not run");
		}
		else if (!step.tool().equals(tool)) {
			return name + ".tool is " + Causes.shown(item.get("tool")).replace("\"", "") + ", but step " + stepNo + " is "
					+ step.tool();
		}
		else if (!quoted(quote.asString(), step.output()) && !inSummary(normalized(quote.asString()), step.summary())) {
			return name + ".quote is not in the output of step " + stepNo;
		}
		return null;
	}

	/**
	 * Checks an item that cites the dossier, which passes every other rule (T5b). Its quote is matched against the
	 * rendered dossier as a quote of a tool output is ({@link #quoted}); every failure is droppable, as an unverified
	 * quote (T5c11) or a wrong tool (T5c14) is.
	 * @return the item's only error, or null when it is verified
	 */
	private static String dossierItem(String name, String tool, String quote, AgentMode mode, String dossier) {
		if (mode != AgentMode.DOSSIER) {
			return name + ".toolCallId dossier: this run has no dossier";
		}
		if (dossier == null) {
			return name + ".toolCallId dossier: the dossier was not gathered";
		}
		if (!DOSSIER.equals(tool)) {
			return name + ".tool is " + tool.replace("\"", "") + ", but toolCallId dossier is the dossier";
		}
		if (!quoted(quote, dossier)) {
			return name + ".quote is not in the dossier";
		}
		return null;
	}

	/**
	 * Whether {@code quote} is in {@code output}, both {@link #normalized normalized}: in the raw text, or in one of the
	 * string values of its JSON, decoded, so that escaped quotes and newlines compare as text. The evidence check also
	 * tries the lines of the step's summary that copy the output, the text the model saw (T5c6, T5c8).
	 */
	static boolean quoted(String quote, String output) {
		if (output == null) {
			return false;
		}
		String wanted = normalized(quote);
		if (wanted.isEmpty()) {
			return false;
		}
		if (inText(wanted, output)) {
			return true;
		}
		String json = output.endsWith(TRUNCATED_SUFFIX)
				? output.substring(0, output.length() - TRUNCATED_SUFFIX.length()) : output;
		try {
			return inStrings(JSON.readTree(json), wanted);
		}
		catch (JacksonException notJson) {
			return false;
		}
	}

	private static boolean inStrings(JsonNode node, String wanted) {
		if (node == null) {
			return false;
		}
		if (node.isString()) {
			return normalized(node.asString()).contains(wanted);
		}
		for (JsonNode child : node) {
			if (inStrings(child, wanted)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether {@code wanted}, normalized, is in one line of the summary that copies the tool output (T5c8, the T5c6
	 * review): not in the header, a computed count or aggregate, an outcome sentence, the cut note or a {@code (cut)}
	 * marker, which code wrote and the tool did not return ({@link ToolSummaries#quotableLines}).
	 */
	private static boolean inSummary(String wanted, String summary) {
		return ToolSummaries.quotableLines(summary).stream().anyMatch(line -> inText(wanted, line));
	}

	/** Characters as the schema counts them: code points. */
	private static int length(String text) {
		return text.codePointCount(0, text.length());
	}

	private static boolean inText(String wanted, String text) {
		return text != null && !wanted.isEmpty() && normalized(text).contains(wanted);
	}

	/**
	 * The form a quote and the text it is looked up in are compared in (T5c6): the escapes {@code \n}, {@code \t},
	 * {@code \"} and {@code \\} decoded, every run of whitespace, line breaks included, one space, trimmed; case kept.
	 * A summary shows untrusted text as an escaped literal, and the model copies it with real line breaks.
	 */
	static String normalized(String text) {
		return ESCAPE.matcher(text)
			.replaceAll(match -> Matcher.quoteReplacement(switch (match.group(1)) {
				case "n" -> "\n";
				case "t" -> "\t";
				default -> match.group(1);
			}))
			.strip()
			.replaceAll("\\s+", " ");
	}

}
