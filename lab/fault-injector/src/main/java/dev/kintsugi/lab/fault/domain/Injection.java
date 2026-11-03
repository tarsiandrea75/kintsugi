package dev.kintsugi.lab.fault.domain;

import java.time.Instant;
import java.util.UUID;

import dev.kintsugi.lab.fault.catalog.Fault;

/**
 * One row of {@code injections}: an accepted activation and its true cause. {@code parametersJson} is the parameters
 * actually applied, as a JSON object, and {@code rootCause} the true-cause sentence for them. Never serialized to an HTTP answer: the API maps it to id-only responses.
 */
public record Injection(UUID id, String faultId, String targetService, String rootCauseService,
		String rootCauseCategory, String rootCause, String parametersJson, Instant injectedAt, Instant restoredAt,
		InjectionStatus status) {

	/** A new {@code ACTIVE} injection of {@code fault}, with the truth copied from the catalog. */
	public static Injection start(Fault fault, String parametersJson, Instant injectedAt) {
		return start(fault, fault.rootCause(), parametersJson, injectedAt);
	}

	/** As {@link #start(Fault, String, Instant)}, with the true-cause sentence of the applied parameters. */
	public static Injection start(Fault fault, String rootCause, String parametersJson, Instant injectedAt) {
		return new Injection(UUID.randomUUID(), fault.id(), fault.targetService(), fault.rootCauseService(),
				fault.rootCauseCategory().name(), rootCause, parametersJson, injectedAt, null, InjectionStatus.ACTIVE);
	}

}
