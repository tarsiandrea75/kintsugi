package dev.kintsugi.agent.loop;

import java.util.List;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import dev.kintsugi.agent.dossier.Topology;

/**
 * The JSON schema of the report, sent with its call through {@code OllamaChatOptions.outputSchema} (agent spec, "The
 * report"). Ollama constrains the answer with it, when the model honours it; code checks every answer against the same
 * rules anyway ({@link ReportCheck}). The plan has no schema sent: it travels in the text of a call that offers tools,
 * and Ollama cannot constrain an answer by a schema and offer tools in the same call ({@link PlanCheck} checks it).
 * Decision mode may ask for the assessed report instead, with its own schema ({@link #assessedReport}).
 */
final class Schemas {

	private static final JsonMapper JSON = JsonMapper.shared();

	private Schemas() {
	}

	/**
	 * The schema of the assessed report (service-assessment T2), decision mode's with the sheets when
	 * {@code kintsugi.agent.decision.report} is {@code assessed}: first the assessments, one per service of
	 * {@code topology} in sheet order, leaf first ({@link Topology#leafFirst()}), each {@code {role, line, why}}; then
	 * the summary, the cause and the confidence, as in the plain report, and no evidence, which code derives from the
	 * assessments. A {@code line} and a {@code why} follow an evidence item's rules. The local route's grammar writes
	 * the properties in this order.
	 */
	static String assessedReport(Topology topology) {
		return JSON.writeValueAsString(assessedReportNode(topology.leafFirst(), topology.causes()));
	}

	/**
	 * The report schema of {@code mode}, whose {@code rootCauseService} is one of {@code topology}'s causes
	 * ({@link Topology#causes()}). In explorer mode a {@code toolCallId} is a step number; in the modes that gather
	 * the dossier, dossier (T5b) and decision mode, it is a step number or {@code "dossier"}; the rest is the same.
	 */
	static String report(AgentMode mode, Topology topology) {
		return JSON.writeValueAsString(report(mode.gathersDossier(), topology.causes(), true));
	}

	/**
	 * The hybrid report's schema (hybrid T3; ADR-019): the plain report's of {@code mode} without the alternatives,
	 * which ADR-025 leaves out of the hybrid report's closed choice.
	 */
	static String hybridReport(AgentMode mode, Topology topology) {
		return JSON.writeValueAsString(report(mode.gathersDossier(), topology.causes(), false));
	}

	private static ObjectNode report(boolean dossier, List<String> causes, boolean ranked) {
		// Evidence-by-reference (ADR-013): an item cites a line by its number, and code fills in its text.
		ObjectNode item = object(List.of("toolCallId", "tool", "line", "why"));
		ObjectNode stepNo = JSON.createObjectNode().put("type", "integer").put("minimum", 1);
		if (dossier) {
			ObjectNode either = JSON.createObjectNode();
			ArrayNode anyOf = either.putArray("anyOf");
			anyOf.add(stepNo);
			anyOf.addObject().put("type", "string").putArray("enum").add(ReportCheck.DOSSIER);
			item.withObject("properties").set("toolCallId", either);
		}
		else {
			item.withObject("properties").set("toolCallId", stepNo);
		}
		item.withObject("properties").set("tool", JSON.createObjectNode().put("type", "string"));
		item.withObject("properties").set("line", JSON.createObjectNode().put("type", "integer").put("minimum", 1));
		item.withObject("properties").set("why", string(0, 200));
		// The properties in ReportCheck.MODEL_FIELDS' order, the reasoning before the cause (report-field-order).
		ObjectNode report = object(ranked ? ReportCheck.MODEL_FIELDS : ReportCheck.HYBRID_FIELDS);
		ObjectNode properties = report.withObject("properties");
		properties.set("summary", string(1, 500));
		properties.set("evidence", array(item, 0, 5));
		properties.set("rootCauseService", enumOf(causes));
		properties.set("rootCauseCategory", enumOf(Causes.CATEGORIES));
		if (ranked) {
			// ranked-report T2 (ADR-025): the causes below the first, from the same enums but UNKNOWN, which a ranked
			// cause never is; the cloud route drops maxItems, and ReportCheck enforces it on both routes.
			ObjectNode alternative = object(ReportCheck.ALTERNATIVE_FIELDS);
			alternative.withObject("properties").set("rootCauseService", enumOf(named(causes)));
			alternative.withObject("properties").set("rootCauseCategory", enumOf(named(Causes.CATEGORIES)));
			properties.set("alternatives", array(alternative, 0, ReportCheck.MAX_ALTERNATIVES));
		}
		properties.set("confidence", JSON.createObjectNode().put("type", "number").put("minimum", 0).put("maximum", 1));
		return report;
	}

	private static ObjectNode assessedReportNode(List<String> services, List<String> causes) {
		ObjectNode assessments = object(services);
		for (String service : services) {
			ObjectNode assessment = object(ReportCheck.ASSESSMENT_FIELDS);
			ObjectNode fields = assessment.withObject("properties");
			fields.set("role", enumOf(ReportCheck.Role.VALUES));
			fields.set("line", JSON.createObjectNode().put("type", "integer").put("minimum", 1));
			fields.set("why", string(0, 200));
			assessments.withObject("properties").set(service, assessment);
		}
		// The properties in ReportCheck.ASSESSED_FIELDS' order: the assessments before the cause they lead to.
		ObjectNode report = object(ReportCheck.ASSESSED_FIELDS);
		ObjectNode properties = report.withObject("properties");
		properties.set("assessments", assessments);
		properties.set("summary", string(1, 500));
		properties.set("rootCauseService", enumOf(causes));
		properties.set("rootCauseCategory", enumOf(Causes.CATEGORIES));
		properties.set("confidence", JSON.createObjectNode().put("type", "number").put("minimum", 0).put("maximum", 1));
		return report;
	}

	/** An object with exactly {@code fields}, all required. */
	private static ObjectNode object(List<String> fields) {
		ObjectNode object = JSON.createObjectNode().put("type", "object");
		object.putObject("properties");
		ArrayNode required = object.putArray("required");
		fields.forEach(required::add);
		object.put("additionalProperties", false);
		return object;
	}

	/** {@code values} without UNKNOWN. */
	private static List<String> named(List<String> values) {
		return values.stream().filter(value -> !Causes.UNKNOWN.equals(value)).toList();
	}

	private static ObjectNode enumOf(List<String> values) {
		ObjectNode node = JSON.createObjectNode().put("type", "string");
		ArrayNode allowed = node.putArray("enum");
		values.forEach(allowed::add);
		return node;
	}

	private static ObjectNode string(int min, int max) {
		return JSON.createObjectNode().put("type", "string").put("minLength", min).put("maxLength", max);
	}

	private static ObjectNode array(ObjectNode items, int min, int max) {
		ObjectNode array = JSON.createObjectNode().put("type", "array").put("minItems", min).put("maxItems", max);
		array.set("items", items);
		return array;
	}

}
