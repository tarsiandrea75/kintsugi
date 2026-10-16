package dev.kintsugi.agent.incident;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

/** The bodies of the incident API. A null field is left out, so an absent value is absent, not {@code null}. */
final class IncidentViews {

	private IncidentViews() {
	}

	/** 202 of {@code POST /incidents}. */
	record Opened(UUID id, IncidentStatus status) {
	}

	/** 200 of {@code GET /incidents/{id}}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Incident(UUID id, String symptom, Integer sinceMinutes, String mode, IncidentStatus status,
			String failureReason, Instant startedAt, Instant endedAt, JsonNode report, List<Step> steps) {

		Incident withSteps(List<Step> steps) {
			return new Incident(id, symptom, sinceMinutes, mode, status, failureReason, startedAt, endedAt, report,
					steps);
		}

	}

	/**
	 * One step row. {@code toolOutput} and {@code modelOutput} are the full texts, sent only with {@code ?full=true}:
	 * by default a reader gets the summaries, which are what the model saw.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Step(int stepNo, String kind, String phase, String model, Integer tokens, String toolName,
			JsonNode toolArgs, String toolOutcome, String summary, String toolOutput, String modelOutput,
			Integer latencyMs, Instant createdAt) {

		Step withoutFullTexts() {
			return new Step(stepNo, kind, phase, model, tokens, toolName, toolArgs, toolOutcome, summary, null, null,
					latencyMs, createdAt);
		}

	}

	/**
	 * Every error answer: a fixed rule or message, the offending field for a 400, and the incident concerned: the
	 * running one for a 409 (absent when it ended before it could be read), the one that failed for a 500.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Error(String error, String field, UUID id) {
	}

}
