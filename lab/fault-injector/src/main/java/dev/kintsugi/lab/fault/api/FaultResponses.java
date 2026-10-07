package dev.kintsugi.lab.fault.api;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.kintsugi.lab.fault.domain.Injection;

/**
 * Every HTTP answer of the injector. None of them carries a target service, a parameter or any part of the true cause:
 * an agent that can call HTTP must not learn the answer (spec, "The truth stays invisible to the agent").
 */
final class FaultResponses {

	private FaultResponses() {
	}

	/** One entry of {@code GET /faults/catalog}. */
	record CatalogEntry(String fault) {
	}

	/** Body of {@code POST /faults}. */
	record ActivateRequest(String fault) {
	}

	/** 201 of {@code POST /faults}. */
	record Created(UUID id, String fault, Instant injectedAt) {

		static Created of(Injection injection) {
			return new Created(injection.id(), injection.faultId(), injection.injectedAt());
		}

	}

	/** 200 of {@code GET /faults/active}. */
	record Active(UUID id, String fault, String status, Instant injectedAt) {

		static Active of(Injection injection) {
			return new Active(injection.id(), injection.faultId(), injection.status().name(), injection.injectedAt());
		}

	}

	/** 200 of {@code POST /faults/{id}/restore}. */
	record Restored(UUID id, String fault, String status, Instant injectedAt, Instant restoredAt) {

		static Restored of(Injection injection) {
			return new Restored(injection.id(), injection.faultId(), injection.status().name(),
					injection.injectedAt(), injection.restoredAt());
		}

	}

	/** Every error answer: a fixed message, and the injection id when one was recorded. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Error(String error, UUID id) {

		Error(String error) {
			this(error, null);
		}

	}

}
