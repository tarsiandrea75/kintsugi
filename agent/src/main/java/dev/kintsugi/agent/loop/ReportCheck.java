package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.PlanCheck.addIfError;
import static dev.kintsugi.agent.loop.PlanCheck.unexpected;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.dossier.Change;
import dev.kintsugi.agent.dossier.DossierRenderer;
import dev.kintsugi.agent.dossier.LineFact;
import dev.kintsugi.agent.dossier.Section;
import dev.kintsugi.agent.dossier.ServiceSheets;
import dev.kintsugi.agent.dossier.Topology;
import dev.kintsugi.agent.loop.Verification.Check;
import dev.kintsugi.agent.rules.RulesCandidate;
import dev.kintsugi.agent.summary.ToolSummaries;

/**
 * Checks a report in code: its schema, then each evidence item against the recorded tool steps of this incident, or,
 * in dossier and decision mode, against the rendered dossier (agent spec, "The report", "Evidence is checked"). The
 * model proposes, this code verifies.
 *
 * <p>
 * An evidence item cites a line by its number, in the summary of a step or in the dossier, and code fills in the
 * line's text as the item's {@code quote} (evidence-by-reference, ADR-013): a small model that had to copy the text
 * rebuilt it in its own format, while a number is an exact, checkable choice, and the stored quote is then verbatim by
 * construction.
 *
 * <p>
 * Decision mode may ask for the assessed report instead (service-assessment T2, {@link #checkAssessed}): the role of
 * each lab service, each citing a line of its own sheet, which code checks against the call graph and from which it
 * derives the evidence.
 */
final class ReportCheck {

	/**
	 * The fields the model writes in the plain report, in the order it writes them and the stored report keeps: the
	 * reasoning before the cause it leads to (report-field-order), since a model writes left to right, and the
	 * alternatives after the cause, ranked below it (ranked-report T2; ADR-025).
	 */
	static final List<String> MODEL_FIELDS = List.of("summary", "evidence", "rootCauseService", "rootCauseCategory",
			"alternatives", "confidence");

	private static final Set<String> FIELDS = Set.copyOf(MODEL_FIELDS);

	/**
	 * The fields of the hybrid report (hybrid T3; ADR-019), the plain report's before the alternatives: ADR-025 leaves
	 * it unchanged, a closed choice of one candidate. An assessed report is stored with them too, after its assessments.
	 */
	static final List<String> HYBRID_FIELDS = List.of("summary", "evidence", "rootCauseService", "rootCauseCategory",
			"confidence");

	private static final Set<String> HYBRID_FIELD_SET = Set.copyOf(HYBRID_FIELDS);

	/** The most alternatives a plain report ranks after its cause (ADR-025): five causes in all. */
	static final int MAX_ALTERNATIVES = 4;

	/** The fields of one alternative, in the order the model writes them. */
	static final List<String> ALTERNATIVE_FIELDS = List.of("rootCauseService", "rootCauseCategory");

	private static final Set<String> ALTERNATIVE_FIELD_SET = Set.copyOf(ALTERNATIVE_FIELDS);

	private static final String ALTERNATIVES_SHAPE = "alternatives must be an array of 0 to " + MAX_ALTERNATIVES
			+ " items {rootCauseService, rootCauseCategory}";

	/**
	 * The fields of the assessed report (service-assessment T2), in the order the model writes them: the assessments
	 * first, then the plain report's fields but the evidence, which code derives from the assessments.
	 */
	static final List<String> ASSESSED_FIELDS = List.of("assessments", "summary", "rootCauseService",
			"rootCauseCategory", "confidence");

	/** The fields of one service's assessment, in the order the model writes them. */
	static final List<String> ASSESSMENT_FIELDS = List.of("role", "line", "why");

	private static final String NOT_AN_OBJECT = "the answer is not a JSON object";

	/** The sections whose changed line of a direct callee fails {@link Verification#NO_CHANGED_CALLEE}. */
	private static final Set<Section> CALLEE_SECTIONS = Set.of(Section.LATENCY, Section.RESOURCES);

	/** Why an abstention's L2 and L3 checks do not apply (ADR-028, Decision 6). */
	static final String ABSTAINED = "both causes are UNKNOWN: the report names no cause to check";

	/** The longest summary stored; a longer one is cut to it, the last character an ellipsis (report-field-order). */
	static final int MAX_SUMMARY_LENGTH = 500;

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
	 * dossier the run has not got or a line it does not have (T5b), each named like an error, and then a summary
	 * code cut (report-field-order); empty otherwise
	 * @param evidence when the report is valid, its verified evidence items, in order, each
	 * {@code {toolCallId, tool, line, quote, why}} with the quote code filled in; null otherwise
	 * @param summary when the report is valid, its summary as stored: cut to {@value #MAX_SUMMARY_LENGTH} characters
	 * when it was longer; null otherwise
	 * @param verification when the report is valid, its level and the checks that decide it (ADR-028), an annotation
	 * that never adds to {@code errors}; null otherwise
	 */
	record Result(List<String> errors, List<String> dropped, List<JsonNode> evidence, String summary,
			Verification verification) {
	}

	/**
	 * One evidence item, checked: exactly one of the two is set when the item breaks no fatal rule.
	 * @param dropped its only error, when it can be dropped
	 * @param verified the item as stored, its quote filled in, when it is verified
	 */
	private record Item(String dropped, JsonNode verified) {

		static final Item FATAL = new Item(null, null);

	}

	/** A service's role in an assessed report (service-assessment T2), as the report task defines it. */
	enum Role {

		/** The service where the problem starts: its own facts show it, and the services it calls do not. */
		ORIGIN,

		/** A service that shows the problem because a service it calls, an ORIGIN or AFFECTED one, does. */
		AFFECTED,

		/** A service whose own facts show no problem. */
		UNAFFECTED;

		/** The values the schema and the check allow, in this order. */
		static final List<String> VALUES = Arrays.stream(values()).map(Enum::name).toList();

	}

	/** One service's assessment, once its schema holds: its role, the line it cites and why. */
	private record Assessment(String service, Role role, int line, JsonNode why) {
	}

	/** Every rule an explorer-mode {@code report} breaks; empty when the report is valid ({@link #check}). */
	static List<String> errors(JsonNode report, Map<Integer, ToolStep> toolSteps, Topology topology) {
		return check(report, toolSteps, topology).errors();
	}

	/** Checks an explorer-mode {@code report}: it has no dossier to cite. */
	static Result check(JsonNode report, Map<Integer, ToolStep> toolSteps, Topology topology) {
		return check(report, toolSteps, AgentMode.EXPLORER, null, topology);
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
	 * @param mode the run's mode: only a mode that gathers the dossier, dossier or decision mode, lets a
	 * {@code toolCallId} be {@code "dossier"}
	 * @param dossier the rendered dossier the model read, its lines numbered, without its introduction; null when
	 * there is none
	 * @param topology the system whose causes ({@link Topology#causes()}) {@code rootCauseService} must name
	 */
	static Result check(JsonNode report, Map<Integer, ToolStep> toolSteps, AgentMode mode, String dossier,
			Topology topology) {
		return check(report, toolSteps, mode, dossier, List.of(), topology);
	}

	/**
	 * Checks {@code report} as {@link #check(JsonNode, Map, AgentMode, String, Topology)} does, and annotates a valid
	 * one with its verification level (ADR-028), read from the fact of each cited dossier line.
	 * @param facts the fact of each numbered line of {@code dossier}, {@code facts.get(n - 1)} line {@code D<n>}'s;
	 * empty when the dossier indexes none, and then no dossier line has a fact
	 */
	static Result check(JsonNode report, Map<Integer, ToolStep> toolSteps, AgentMode mode, String dossier,
			List<LineFact> facts, Topology topology) {
		return check(report, toolSteps, mode, dossier, facts, topology, true);
	}

	/**
	 * Checks {@code report} as {@link #check} does; {@code ranked} says whether it is the plain report, with its
	 * alternatives (ADR-025), or the hybrid one, which has none.
	 */
	private static Result check(JsonNode report, Map<Integer, ToolStep> toolSteps, AgentMode mode, String dossier,
			List<LineFact> facts, Topology topology, boolean ranked) {
		if (report == null || !report.isObject()) {
			return invalid(List.of(NOT_AN_OBJECT));
		}
		List<String> errors = new ArrayList<>();
		unexpected(report, ranked ? FIELDS : HYBRID_FIELD_SET, "", errors);
		causesAndConfidence(report, topology, errors);
		boolean unknown = Causes.UNKNOWN.equals(report.path("rootCauseService").asString(""))
				&& Causes.UNKNOWN.equals(report.path("rootCauseCategory").asString(""));
		if (ranked) {
			alternatives(report, topology, errors);
		}
		JsonNode evidence = report.get("evidence");
		if (evidence == null || !evidence.isArray()) {
			errors.add("evidence must be an array of at most 5 items");
			return invalid(errors);
		}
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
		// L1 reads the dropped items only: the summary cut storedSummary records next is not one.
		Verification verification = verification(report, verified, dropped, facts, topology, List.of());
		String text = storedSummary(report, dropped);
		return new Result(List.of(), List.copyOf(dropped), List.copyOf(verified), text, verification);
	}

	/**
	 * Checks a hybrid report (hybrid T3; ADR-019): every rule of {@link #check}, then one more, that
	 * {@code rootCauseService|rootCauseCategory} is the pair of one of {@code candidates} or {@code UNKNOWN|UNKNOWN}. A
	 * pair outside the list is an error like the others, naming the allowed pairs, so it gets the same one retry.
	 * @param candidates the rules' candidates the report task listed, at least one
	 * @param topology the system whose causes the report must name, as for {@link #check}
	 */
	static Result checkHybrid(JsonNode report, Map<Integer, ToolStep> toolSteps, AgentMode mode, String dossier,
			List<RulesCandidate> candidates, Topology topology) {
		return checkHybrid(report, toolSteps, mode, dossier, List.of(), candidates, topology);
	}

	/** Checks a hybrid report as {@link #checkHybrid} does, annotating a valid one as {@link #check} does. */
	static Result checkHybrid(JsonNode report, Map<Integer, ToolStep> toolSteps, AgentMode mode, String dossier,
			List<LineFact> facts, List<RulesCandidate> candidates, Topology topology) {
		Result checked = check(report, toolSteps, mode, dossier, facts, topology, false);
		if (report == null || !report.isObject()) {
			return checked;
		}
		String named = report.path("rootCauseService").asString("") + "|"
				+ report.path("rootCauseCategory").asString("");
		List<String> pairs = candidates.stream().map(ReportCheck::pair).toList();
		if (pairs.contains(named) || named.equals(Causes.UNKNOWN + "|" + Causes.UNKNOWN)) {
			return checked;
		}
		List<String> errors = new ArrayList<>(checked.errors());
		errors.add("rootCauseService|rootCauseCategory " + named + " is not a listed candidate: name one of "
				+ String.join(", ", pairs) + ", or " + Causes.UNKNOWN + "|" + Causes.UNKNOWN);
		return invalid(errors);
	}

	/** A candidate's {@code service|category}, as the report task lists it and a hybrid report must name it. */
	static String pair(RulesCandidate candidate) {
		return candidate.service() + "|" + candidate.category();
	}

	/**
	 * Checks an assessed report (service-assessment T2; agent spec, "Evidence is checked", the assessed report): its
	 * schema, then the rules its derived evidence needs. Every broken rule rejects the report, for the one retry;
	 * nothing is dropped but a summary too long, which is cut as the plain report's is. The rules, in order:
	 * <b>Coverage</b>, every lab service is assessed once and no other name; <b>Citation</b>, each assessment's line is
	 * a line of the sheets its own service owns, in its sheet or in its part of the last section. A valid report's
	 * evidence is derived by code: the lines of the ORIGIN and AFFECTED services, in sheet order, each an item citing
	 * the dossier with its quote filled in, as a plain report's verified dossier item is, so that storage and scoring
	 * read it as they read a plain report. When every service is UNAFFECTED, the report has no evidence.
	 *
	 * <p>
	 * The graph rules, which check the model's roles against the call graph, are L2 checks of the valid report
	 * (ADR-028, Decision 2), never errors, so they never cause the retry: <b>One origin</b>, at most one ORIGIN, and
	 * none only when every service is UNAFFECTED and both causes are UNKNOWN; <b>Graph</b>, an AFFECTED service calls
	 * an ORIGIN or AFFECTED service ({@link Topology#calls}); <b>Answer</b>, {@code rootCauseService} is the ORIGIN.
	 * A report breaking one keeps the model's cause, which is what is scored.
	 * @param sheets the sheets the model read, with the service that owns each numbered line
	 * @param topology the services to assess, leaf first, and the call graph the Graph rule reads
	 */
	static Result checkAssessed(JsonNode report, ServiceSheets.Rendered sheets, Topology topology) {
		List<String> services = topology.leafFirst();
		if (report == null || !report.isObject()) {
			return invalid(List.of(NOT_AN_OBJECT));
		}
		List<String> errors = new ArrayList<>();
		unexpected(report, Set.copyOf(ASSESSED_FIELDS), "", errors);
		Map<String, Assessment> assessments = assessments(report.get("assessments"), services, errors);
		causesAndConfidence(report, topology, errors);
		// Each cited line's text, by service, for the derived evidence: a line that is owned but not in the text is a
		// Citation error, never a lookup that fails later.
		Map<String, String> quotes = new LinkedHashMap<>();
		assessments.values()
			.forEach(assessment -> cited(assessment, sheets, errors)
				.ifPresent(quote -> quotes.put(assessment.service(), quote)));
		if (!errors.isEmpty()) {
			return invalid(errors);
		}
		List<JsonNode> evidence = new ArrayList<>();
		for (Assessment assessment : assessments.values()) {
			if (assessment.role() != Role.UNAFFECTED) {
				evidence.add(dossierItem(assessment.line(), quotes.get(assessment.service()), assessment.why()));
			}
		}
		// Valid, so every service is assessed: the graph rules read every role.
		Verification verification = verification(report, evidence, List.of(), sheets.facts(), topology,
				graphRules(assessments, report, topology));
		List<String> dropped = new ArrayList<>();
		String text = storedSummary(report, dropped);
		return new Result(List.of(), List.copyOf(dropped), List.copyOf(evidence), text, verification);
	}

	/**
	 * The assessments of {@code node} whose schema holds, by service in sheet order, adding to {@code errors} each rule
	 * the object breaks: it holds every lab service once and no other name (Coverage), each {@code {role, line, why}}.
	 */
	private static Map<String, Assessment> assessments(JsonNode node, List<String> services, List<String> errors) {
		Map<String, Assessment> assessments = new LinkedHashMap<>();
		if (node == null || !node.isObject()) {
			errors.add("assessments must be an object with one {role, line, why} per service: "
					+ String.join(", ", services));
			return assessments;
		}
		unexpected(node, Set.copyOf(services), "assessments.", errors);
		for (String service : services) {
			JsonNode assessment = node.get(service);
			if (assessment == null) {
				errors.add("assessments." + service + " is missing: assess every service once");
			}
			else {
				assessment(service, assessment, errors).ifPresent(checked -> assessments.put(service, checked));
			}
		}
		return assessments;
	}

	/** One service's assessment, when its schema holds; else empty, with each rule it breaks added to {@code errors}. */
	private static Optional<Assessment> assessment(String service, JsonNode assessment, List<String> errors) {
		String name = "assessments." + service;
		if (!assessment.isObject()) {
			errors.add(name + " must be an object {role, line, why}");
			return Optional.empty();
		}
		int before = errors.size();
		unexpected(assessment, Set.copyOf(ASSESSMENT_FIELDS), name + ".", errors);
		addIfError(errors, Causes.enumError(name + ".role", assessment.get("role"), Role.VALUES));
		JsonNode line = assessment.get("line");
		if (line == null || !line.isIntegralNumber() || !line.canConvertToInt() || line.intValue() < 1) {
			errors.add(name + ".line must be an integer of at least 1");
		}
		if (!Causes.string(assessment.get("why"), 0, 200)) {
			errors.add(name + ".why must be a string of at most 200 characters");
		}
		if (errors.size() > before) {
			return Optional.empty();
		}
		return Optional.of(new Assessment(service, Role.valueOf(assessment.get("role").asString()), line.intValue(),
				assessment.get("why")));
	}

	/**
	 * Citation: the text of the assessment's line, without its number, when it is a line its service owns in
	 * {@code sheets}; else empty, with an error added. An owned line whose text the sheets do not hold is refused as
	 * well (service-assessment T2r): its evidence could not be quoted, so the report goes to the retry instead of
	 * failing the run.
	 */
	private static Optional<String> cited(Assessment assessment, ServiceSheets.Rendered sheets, List<String> errors) {
		Optional<String> owner = sheets.owner(assessment.line());
		String found;
		if (owner.isPresent() && owner.get().equals(assessment.service())) {
			Optional<String> quote = DossierRenderer.line(sheets.text(), assessment.line());
			if (quote.isPresent()) {
				return quote;
			}
			found = "is a line of " + assessment.service() + " whose text is not in the sheets; cite another line";
		}
		else {
			found = owner.map(name -> "is a line of " + (ServiceSheets.HEADER.equals(name) ? "the header" : name)
					+ ", not of " + assessment.service())
				.orElse("is not in the sheets") + "; cite a line";
		}
		errors.add("assessments." + assessment.service() + ".line " + assessment.line() + " " + found
				+ " of its own sheet or of its part of \"Seen only in the previous window\"");
		return Optional.empty();
	}

	/**
	 * One origin, Graph and Answer, over the assessments of every service, in sheet order: each the L2 check
	 * ({@link Check#of}) whose detail names the claims that break it. They check the report against its own roles and
	 * the call graph, never against a cause.
	 */
	private static List<Check> graphRules(Map<String, Assessment> assessments, JsonNode report, Topology topology) {
		List<String> origins = withRole(assessments, Role.ORIGIN);
		List<String> affected = withRole(assessments, Role.AFFECTED);
		String service = report.path("rootCauseService").asString("");
		String category = report.path("rootCauseCategory").asString("");
		String oneOrigin = null;
		if (origins.size() > 1) {
			oneOrigin = "at most one service is the ORIGIN; got " + origins.size() + ": " + String.join(", ", origins);
		}
		else if (origins.isEmpty() && !affected.isEmpty()) {
			oneOrigin = "no service is the ORIGIN, so every service must be UNAFFECTED; got AFFECTED: "
					+ String.join(", ", affected);
		}
		else if (origins.isEmpty() && !(Causes.UNKNOWN.equals(service) && Causes.UNKNOWN.equals(category))) {
			oneOrigin = "no service is the ORIGIN, so rootCauseService and rootCauseCategory must both be UNKNOWN; got \""
					+ service + "\" and \"" + category + "\"";
		}
		List<String> graph = new ArrayList<>();
		for (String name : affected) {
			List<String> calls = topology.calls(name);
			if (calls.stream().noneMatch(callee -> assessments.get(callee).role() != Role.UNAFFECTED)) {
				String called = calls.isEmpty() ? "calls no service"
						: "calls no ORIGIN or AFFECTED service (it calls " + String.join(", ", calls.stream()
							.map(callee -> callee + ": " + assessments.get(callee).role())
							.toList()) + ")";
				graph.add("assessments." + name + " is AFFECTED but " + called
						+ "; a service is AFFECTED only through a service it calls that is ORIGIN or AFFECTED");
			}
		}
		// With no single ORIGIN there is none for the cause to be: One origin fails and names why.
		String answer = origins.size() == 1 && !origins.getFirst().equals(service)
				? "rootCauseService must be the ORIGIN, " + origins.getFirst() + "; got \"" + service + "\"" : null;
		return List.of(Check.of(2, Verification.ONE_ORIGIN, oneOrigin),
				Check.of(2, Verification.GRAPH, graph.isEmpty() ? null : String.join("; ", graph)),
				Check.of(2, Verification.ANSWER, answer));
	}

	/**
	 * The verification of an accepted report (ADR-028): L0 its schema, passed, since the report is accepted; L1 no
	 * evidence item was dropped; L2 every stored item's line is owned by the cause or a service on its call path, then
	 * {@code graphRules}, the assessed report's; L3 at least one stored item cites a line of the cause whose fact
	 * changed, and no service the cause calls directly has a changed latency or resources line anywhere in the dossier
	 * (Decision 5, amended 2026-11-05). A report naming {@code UNKNOWN|UNKNOWN} claims no cause, so its L2 and L3 checks do not apply ({@code pass}
	 * null), and a graph rule its roles break keeps that in its detail. The alternatives being causes of the topology is
	 * an L0 rule of the plain report already ({@link #alternatives}), so L2 does not check it again.
	 * @param evidence the stored evidence items, each {@code {toolCallId, tool, line, quote, why}}
	 * @param dropped the evidence items dropped, as {@link Result#dropped} names them, without a summary cut
	 * @param facts the fact of each numbered dossier line, {@code facts.get(n - 1)} line {@code D<n>}'s; empty when
	 * the dossier indexes none
	 */
	private static Verification verification(JsonNode report, List<JsonNode> evidence, List<String> dropped,
			List<LineFact> facts, Topology topology, List<Check> graphRules) {
		List<Check> checks = new ArrayList<>();
		checks.add(Check.passed(0, Verification.SCHEMA));
		checks.add(Check.of(1, Verification.CITATIONS, dropped.isEmpty() ? null : String.join("; ", dropped)));
		String cause = report.path("rootCauseService").asString("");
		if (Causes.UNKNOWN.equals(cause) && Causes.UNKNOWN.equals(report.path("rootCauseCategory").asString(""))) {
			checks.add(Check.notApplicable(2, Verification.OWNERS_ON_PATH, ABSTAINED));
			graphRules.forEach(rule -> checks.add(Check.notApplicable(2, rule.name(),
					rule.detail() == null ? ABSTAINED : ABSTAINED + "; the roles break it: " + rule.detail())));
			checks.add(Check.notApplicable(3, Verification.CAUSE_FACT_CHANGED, ABSTAINED));
			checks.add(Check.notApplicable(3, Verification.NO_CHANGED_CALLEE, ABSTAINED));
			return Verification.of(checks);
		}
		Set<String> path = callPath(cause, topology);
		List<String> offPath = new ArrayList<>();
		List<String> causeLines = new ArrayList<>();
		boolean changed = false;
		for (JsonNode item : evidence) {
			int line = item.get("line").intValue();
			if (!DOSSIER.equals(item.get("toolCallId").asString(""))) {
				offPath.add("step " + item.get("toolCallId").intValue() + " line " + line
						+ " is a tool step's output, which has no line fact");
				continue;
			}
			Optional<LineFact> fact = fact(facts, line);
			if (fact.isEmpty()) {
				offPath.add("D" + line + " has no line fact");
				continue;
			}
			String owner = fact.get().owner();
			if (!path.contains(owner)) {
				offPath.add("D" + line + " is a line of " + (LineFact.HEADER.equals(owner) ? "the header" : owner)
						+ ", not of " + cause + " or a service on its call path");
			}
			if (owner.equals(cause)) {
				causeLines.add("D" + line + " " + fact.get().changed());
				changed |= fact.get().changed() == Change.YES;
			}
		}
		checks.add(Check.of(2, Verification.OWNERS_ON_PATH, offPath.isEmpty() ? null : String.join("; ", offPath)));
		checks.addAll(graphRules);
		checks.add(Check.of(3, Verification.CAUSE_FACT_CHANGED, changed ? null
				: "no cited line is a line of " + cause + " whose fact changed between the windows; "
						+ (causeLines.isEmpty() ? "none of its lines is cited"
								: "its cited lines: " + String.join(", ", causeLines))));
		checks.add(Check.of(3, Verification.NO_CHANGED_CALLEE, changedCallees(cause, facts, topology)));
		return Verification.of(checks);
	}

	/**
	 * The L3 check {@link Verification#NO_CHANGED_CALLEE} (ADR-028, Decision 5, amended 2026-11-05): every line of the
	 * dossier, cited or not, that a service {@code cause} calls directly owns, of section {@code latency} or
	 * {@code resources}, whose fact changed. A caller's latency rises with its callee's, so such a line says the problem
	 * may start below the cause. Transitive callees and the other sections do not count.
	 * @return the failure detail naming each such line, or null when there is none
	 */
	private static String changedCallees(String cause, List<LineFact> facts, Topology topology) {
		Set<String> callees = Set.copyOf(topology.calls(cause));
		List<String> changed = new ArrayList<>();
		for (int i = 0; i < facts.size(); i++) {
			LineFact fact = facts.get(i);
			if (callees.contains(fact.owner()) && CALLEE_SECTIONS.contains(fact.section())
					&& fact.changed() == Change.YES) {
				changed.add("D" + (i + 1) + " is a changed " + fact.section().name().toLowerCase(Locale.ROOT)
						+ " line of " + fact.owner() + ", which " + cause + " calls");
			}
		}
		return changed.isEmpty() ? null : String.join("; ", changed);
	}

	/**
	 * The verification of {@code report} as code wrote it, with no evidence item: a hybrid run whose rules proposed no
	 * candidate stores such a report, both causes UNKNOWN, so it is at L1.
	 */
	static Verification withoutEvidence(JsonNode report, Topology topology) {
		return verification(report, List.of(), List.of(), List.of(), topology, List.of());
	}

	/**
	 * The cause and every service on its call path (ADR-028, Decision 4): its transitive callers ({@link Topology#calledBy})
	 * and its transitive callees ({@link Topology#calls}), each direction followed on its own, so that a service on
	 * another branch, reached only through a common caller, is not on it.
	 */
	private static Set<String> callPath(String cause, Topology topology) {
		Set<String> path = new HashSet<>();
		path.add(cause);
		path.addAll(reachable(cause, topology::calls));
		path.addAll(reachable(cause, topology::calledBy));
		return path;
	}

	/** Every name reachable from {@code start} over {@code next}, {@code start} excluded unless on a cycle. */
	private static Set<String> reachable(String start, Function<String, List<String>> next) {
		Set<String> seen = new HashSet<>();
		Deque<String> queue = new ArrayDeque<>(next.apply(start));
		while (!queue.isEmpty()) {
			String name = queue.removeFirst();
			if (seen.add(name)) {
				queue.addAll(next.apply(name));
			}
		}
		return seen;
	}

	/** The fact of line {@code D<n>} in {@code facts}; empty when it has none. */
	private static Optional<LineFact> fact(List<LineFact> facts, int n) {
		return n < 1 || n > facts.size() ? Optional.empty() : Optional.of(facts.get(n - 1));
	}

	/** The services whose role is {@code role}, in sheet order. */
	private static List<String> withRole(Map<String, Assessment> assessments, Role role) {
		return assessments.values().stream().filter(a -> a.role() == role).map(Assessment::service).toList();
	}

	/**
	 * The summary as stored: cut to {@value #MAX_SUMMARY_LENGTH} characters when it is longer, the last one an
	 * ellipsis, which {@code dropped} then records (report-field-order).
	 */
	private static String storedSummary(JsonNode report, List<String> dropped) {
		String text = report.get("summary").asString();
		int length = text.codePointCount(0, text.length());
		if (length > MAX_SUMMARY_LENGTH) {
			// report-field-order: the summary is the model's reasoning, not evidence, and with the reasoning first the
			// model overran the limit by 1-25% even when told by how much; a cut keeps the report and its checked
			// fields, and the verdict says so.
			text = text.substring(0, text.offsetByCodePoints(0, MAX_SUMMARY_LENGTH - 1)) + "…";
			dropped.add("summary cut from " + length + " to " + MAX_SUMMARY_LENGTH + " characters");
		}
		return text;
	}

	/**
	 * Adds to {@code errors} each rule the cause, the summary and the confidence break, as both reports write them;
	 * {@code rootCauseService} is one of {@code topology}'s causes.
	 */
	private static void causesAndConfidence(JsonNode report, Topology topology, List<String> errors) {
		addIfError(errors,
				Causes.enumError("rootCauseService", report.get("rootCauseService"), topology.causes()));
		addIfError(errors, Causes.enumError("rootCauseCategory", report.get("rootCauseCategory"), Causes.CATEGORIES));
		if (!Causes.string(report.get("summary"), 1, Integer.MAX_VALUE)) {
			errors.add("summary must be a string of 1 to " + MAX_SUMMARY_LENGTH + " characters");
		}
		JsonNode confidence = report.get("confidence");
		if (confidence == null || !confidence.isNumber() || confidence.doubleValue() < 0
				|| confidence.doubleValue() > 1) {
			errors.add("confidence must be a number from 0 to 1");
		}
	}

	/**
	 * Adds to {@code errors} each rule the plain report's alternatives break (ranked-report T2; ADR-025): an array of
	 * at most {@value #MAX_ALTERNATIVES} items {@code {rootCauseService, rootCauseCategory}}, from the cause's enums,
	 * none UNKNOWN in either field, each service named once, the first cause's included. When both causes are UNKNOWN
	 * the same rules hold, and the list is the causes the model suspects but cannot support (ADR-025, amendment
	 * 2026-11-03): an UNKNOWN first cause names no service, so it blocks none. Every breach rejects the report, for the
	 * one retry: no alternative is dropped, since the list is the model's ranking and a list code had cut would be a
	 * ranking nobody made.
	 */
	private static void alternatives(JsonNode report, Topology topology, List<String> errors) {
		JsonNode alternatives = report.get("alternatives");
		if (alternatives == null || !alternatives.isArray()) {
			errors.add(ALTERNATIVES_SHAPE);
			return;
		}
		if (alternatives.size() > MAX_ALTERNATIVES) {
			errors.add("alternatives must hold at most " + MAX_ALTERNATIVES + " items");
		}
		Set<String> named = new HashSet<>();
		named.add(report.path("rootCauseService").asString(""));
		for (int i = 0; i < alternatives.size(); i++) {
			JsonNode item = alternatives.get(i);
			String at = "alternatives[" + i + "]";
			if (!item.isObject()) {
				errors.add(at + " must be an object {rootCauseService, rootCauseCategory}");
				continue;
			}
			unexpected(item, ALTERNATIVE_FIELD_SET, at + ".", errors);
			String service = Causes.enumError(at + ".rootCauseService", item.get("rootCauseService"),
					topology.causes());
			String category = Causes.enumError(at + ".rootCauseCategory", item.get("rootCauseCategory"),
					Causes.CATEGORIES);
			addIfError(errors, service);
			addIfError(errors, category);
			if (service != null || category != null) {
				continue;
			}
			if (Causes.UNKNOWN.equals(item.get("rootCauseService").asString())
					|| Causes.UNKNOWN.equals(item.get("rootCauseCategory").asString())) {
				errors.add(at + " must not be UNKNOWN: leave out a cause you cannot name");
			}
			else if (!named.add(item.get("rootCauseService").asString())) {
				errors.add(at + ".rootCauseService " + Causes.shown(item.get("rootCauseService"))
						+ " is already named; name each service once, rootCauseService included");
			}
		}
	}

	private static Result invalid(List<String> errors) {
		return new Result(List.copyOf(errors), List.of(), null, null, null);
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
			errors.add(name + ".toolCallId must be an integer" + (mode.gathersDossier() ? " or \"dossier\"" : ""));
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
		if (!mode.gathersDossier()) {
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

	/**
	 * An evidence item citing line {@code D<line>} of the dossier, as {@link #verified} stores one:
	 * {@code {toolCallId, tool, line, quote, why}}, with {@code "dossier"} as its id and tool and {@code text}, the
	 * line's text, as its quote.
	 */
	private static JsonNode dossierItem(int line, String text, JsonNode why) {
		ObjectNode stored = JSON.createObjectNode();
		stored.put("toolCallId", DOSSIER);
		stored.put("tool", DOSSIER);
		stored.put("line", line);
		stored.put("quote", capped(text));
		stored.set("why", why);
		return stored;
	}

	/** {@code text} cut to its first {@value #MAX_QUOTE_LENGTH} code points, so that no character is split. */
	static String capped(String text) {
		return text.codePointCount(0, text.length()) <= MAX_QUOTE_LENGTH ? text
				: text.substring(0, text.offsetByCodePoints(0, MAX_QUOTE_LENGTH));
	}

}
