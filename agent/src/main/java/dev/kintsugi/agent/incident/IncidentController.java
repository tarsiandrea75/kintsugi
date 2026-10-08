package dev.kintsugi.agent.incident;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.incident.IncidentViews.Error;
import dev.kintsugi.agent.incident.IncidentViews.Incident;
import dev.kintsugi.agent.incident.IncidentViews.Opened;

/** The perception endpoint (agent spec, "Open an incident" and "Read an incident"; ADR-010, "Perception"). */
@RestController
@RequestMapping("/incidents")
@ConditionalOnWebApplication
public class IncidentController {

	static final String ALREADY_RUNNING = "an incident is already running";

	/** The 500 message and the {@code failure_reason} of an incident whose runner threw on start. */
	static final String RUNNER_FAILED = "the loop could not be started";

	/** The first insert, and one more when the running incident ended before its id could be read. */
	private static final int MAX_INSERT_ATTEMPTS = 2;

	private static final Logger log = LoggerFactory.getLogger(IncidentController.class);

	private final IncidentRepository incidents;

	private final IncidentRunner runner;

	private final JsonMapper json;

	private final Clock clock;

	IncidentController(IncidentRepository incidents, IncidentRunner runner, JsonMapper json, Clock clock) {
		this.incidents = incidents;
		this.runner = runner;
		this.json = json;
		this.clock = clock;
	}

	/**
	 * Validates the body, then inserts the incident as {@code RUNNING} without checking first: the database's
	 * one-{@code RUNNING} index decides between concurrent requests, and a unique violation is the 409. The id of the
	 * running incident is read after the failed insert; when that incident has ended in between, the slot is free, so
	 * the insert is tried once more. A runner that fails to start ends the incident {@code FAILED} at once, so it
	 * never holds the slot.
	 */
	@PostMapping
	ResponseEntity<?> open(@RequestBody(required = false) String body) {
		IncidentSignal signal = IncidentSignal.from(parse(body));
		UUID id = UUID.randomUUID();
		for (int attempt = 1;; attempt++) {
			try {
				incidents.insertRunning(id, signal, clock.instant());
				break;
			}
			catch (DuplicateKeyException running) {
				Optional<UUID> runningId = incidents.findRunningId();
				if (runningId.isPresent() || attempt == MAX_INSERT_ATTEMPTS) {
					log.info("event=incident_rejected reason=already_running running={}", runningId.orElse(null));
					return ResponseEntity.status(HttpStatus.CONFLICT)
						.body(new Error(ALREADY_RUNNING, null, runningId.orElse(null)));
				}
			}
		}
		log.info("incident={} event=incident_opened sinceMinutes={}", id, signal.sinceMinutes());
		try {
			runner.start(id);
		}
		catch (RuntimeException failed) {
			log.error("incident={} event=runner_failed reason=\"{}\"", id, RUNNER_FAILED, failed);
			incidents.fail(id, RUNNER_FAILED, clock.instant());
			return ResponseEntity.internalServerError().body(new Error(RUNNER_FAILED, null, id));
		}
		return ResponseEntity.accepted()
			.location(URI.create("/incidents/" + id))
			.body(new Opened(id, IncidentStatus.RUNNING));
	}

	/** The incident with its steps: summaries by default, the full tool outputs and model answers with {@code full}. */
	@GetMapping("/{id}")
	ResponseEntity<?> read(@PathVariable String id, @RequestParam(defaultValue = "false") boolean full) {
		UUID uuid;
		try {
			uuid = UUID.fromString(id);
		}
		catch (IllegalArgumentException malformed) {
			return notFound();
		}
		return incidents.find(uuid)
			.<ResponseEntity<?>>map(incident -> ResponseEntity.ok(full ? incident : brief(incident)))
			.orElseGet(IncidentController::notFound);
	}

	@ExceptionHandler(InvalidIncidentException.class)
	ResponseEntity<Error> invalid(InvalidIncidentException invalid) {
		return ResponseEntity.badRequest().body(new Error(invalid.getMessage(), invalid.field(), null));
	}

	private JsonNode parse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return json.readTree(body);
		}
		catch (JacksonException unreadable) {
			return null;
		}
	}

	private static Incident brief(Incident incident) {
		return incident.withSteps(incident.steps().stream().map(IncidentViews.Step::withoutFullTexts).toList());
	}

	private static ResponseEntity<?> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new Error("incident not found", null, null));
	}

}
