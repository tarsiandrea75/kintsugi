package dev.kintsugi.lab.fault.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.control.LabControl;
import dev.kintsugi.lab.fault.domain.Injection;
import dev.kintsugi.lab.fault.domain.InjectionEventType;
import dev.kintsugi.lab.fault.domain.InjectionStatus;
import dev.kintsugi.lab.fault.persistence.InjectionEventRepository;
import dev.kintsugi.lab.fault.persistence.InjectionRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Activates and restores faults, recording the truth before the lab is touched (spec: "Refuse to inject without
 * recording the truth") and every step after it in {@code injection_events}.
 *
 * <p>
 * Failure messages go to the event details and the log, never to the caller: a message may name the target service,
 * which is part of the answer.
 */
@Service
public class FaultInjectionService {

	private static final Logger log = LoggerFactory.getLogger(FaultInjectionService.class);

	private final InjectionRepository injections;

	private final InjectionEventRepository events;

	private final LabControl lab;

	private final TransactionTemplate transaction;

	private final JsonMapper json;

	public FaultInjectionService(InjectionRepository injections, InjectionEventRepository events, LabControl lab,
			TransactionTemplate transaction, JsonMapper json) {
		this.injections = injections;
		this.events = events;
		this.lab = lab;
		this.transaction = transaction;
		this.json = json;
	}

	/**
	 * Records the truth and an {@code ACTIVATION_STARTED} event in one transaction, then activates the lab and records
	 * {@code ACTIVATED}. A failed activation is compensated by a restore and recorded.
	 * @throws UnknownFaultException {@code faultId} is not in the catalog
	 * @throws FaultAlreadyActiveException another injection is blocking (also on a concurrent insert)
	 * @throws TruthUnavailableException the truth could not be written; the lab was not touched
	 * @throws ActivationFailedException an activation step failed; the injection is {@code FAILED}, or
	 * {@code RESTORE_PENDING} when the compensating restore failed too
	 */
	public Injection activate(String faultId) {
		Fault fault = Fault.byId(faultId).orElseThrow(UnknownFaultException::new);
		Injection injection = Injection.start(fault, json.writeValueAsString(fault.parameters()), now());
		try {
			transaction.executeWithoutResult(status -> {
				injections.insert(injection);
				events.append(injection.id(), InjectionEventType.ACTIVATION_STARTED, injection.injectedAt(), null);
			});
		}
		catch (DuplicateKeyException blocking) {
			throw new FaultAlreadyActiveException();
		}
		catch (DataAccessException | TransactionException unreachable) {
			log.error("event=truth_not_recorded fault={} error={}", fault.id(), unreachable.getClass().getName());
			throw new TruthUnavailableException(unreachable);
		}

		try {
			lab.activate(fault);
		}
		catch (RuntimeException activationFailure) {
			compensate(injection, fault, activationFailure);
			throw new ActivationFailedException(injection.id(), activationFailure);
		}

		try {
			events.append(injection.id(), InjectionEventType.ACTIVATED, now(), null);
		}
		catch (DataAccessException unrecorded) {
			// The fault is applied and its truth row is ACTIVE, so the injection is scorable and blocking. Only the
			// ACTIVATED step is missing from the log; answering an error would hide a fault that is really active.
			log.error("event=activated_event_not_recorded injection_id={} fault={} error={}", injection.id(), fault.id(),
					unrecorded.getClass().getName());
		}
		log.info("event=fault_activated injection_id={} fault={}", injection.id(), fault.id());
		return injection;
	}

	/**
	 * Restores a blocking injection. One that no longer blocks ({@code RESTORED}, or {@code FAILED} after its
	 * compensating restore) is returned as it is, with no step run and no event added.
	 * @throws InjectionNotFoundException no injection has this id
	 * @throws RestoreFailedException a restore step failed; the injection is {@code RESTORE_PENDING}
	 */
	public Injection restore(UUID id) {
		Injection injection = injections.findById(id).orElseThrow(() -> new InjectionNotFoundException(id));
		if (!injection.status().isBlocking()) {
			return injection;
		}
		Fault fault = Fault.byId(injection.faultId()).orElseThrow();
		try {
			lab.restore(fault);
		}
		catch (RuntimeException restoreFailure) {
			// A concurrent restore may have succeeded since the row was read. The conditional update moves the row to
			// RESTORE_PENDING only while it is still blocking, so a late failure never turns RESTORED back into
			// blocking: it is then recorded as an event only.
			Boolean stillBlocking = transaction.execute(status -> {
				events.append(id, InjectionEventType.RESTORE_FAILED, now(), failure(restoreFailure, null));
				return injections.updateStatus(id, InjectionStatus.RESTORE_PENDING, null);
			});
			log.warn("event=restore_failed injection_id={} fault={} status={}", id, fault.id(),
					Boolean.TRUE.equals(stillBlocking) ? InjectionStatus.RESTORE_PENDING : "unchanged_not_blocking");
			throw new RestoreFailedException(id, restoreFailure);
		}
		Instant restoredAt = now();
		transaction.executeWithoutResult(status -> {
			if (injections.updateStatus(id, InjectionStatus.RESTORED, restoredAt)) {
				events.append(id, InjectionEventType.RESTORED, restoredAt, null);
			}
		});
		log.info("event=fault_restored injection_id={} fault={}", id, fault.id());
		return injections.findById(id).orElseThrow();
	}

	/** The blocking injection, if any. */
	public Optional<Injection> blocking() {
		return injections.findBlocking();
	}

	/**
	 * After a failed activation: tries the restore steps, then records {@code ACTIVATION_FAILED} and either
	 * {@code FAILED} (restore succeeded) or {@code RESTORE_FAILED} and {@code RESTORE_PENDING} (it failed too). If even
	 * that cannot be recorded, for any reason, the error is logged and the row stays {@code ACTIVE}, which still blocks
	 * new faults.
	 */
	private void compensate(Injection injection, Fault fault, RuntimeException activationFailure) {
		RuntimeException restoreFailure = null;
		try {
			lab.restore(fault);
		}
		catch (RuntimeException failure) {
			restoreFailure = failure;
		}
		RuntimeException restoreOutcome = restoreFailure;
		Instant at = now();
		try {
			transaction.executeWithoutResult(status -> {
				events.append(injection.id(), InjectionEventType.ACTIVATION_FAILED, at,
						failure(activationFailure, restoreOutcome == null ? "succeeded" : "failed"));
				if (restoreOutcome == null) {
					injections.updateStatus(injection.id(), InjectionStatus.FAILED, at);
				}
				else {
					events.append(injection.id(), InjectionEventType.RESTORE_FAILED, at, failure(restoreOutcome, null));
					injections.updateStatus(injection.id(), InjectionStatus.RESTORE_PENDING, null);
				}
			});
		}
		catch (RuntimeException unrecorded) {
			// Any failure here (database, transaction, serialization of the detail) must not replace the activation
			// failure the caller is about to receive. The row stays ACTIVE, which still blocks new faults.
			log.error("event=activation_failure_not_recorded injection_id={} fault={}", injection.id(), fault.id(),
					unrecorded);
		}
		log.warn("event=activation_failed injection_id={} fault={} restore={}", injection.id(), fault.id(),
				restoreOutcome == null ? "succeeded" : "failed");
	}

	/** Compact JSON detail of a failed step: exception class and message, plus the compensating restore outcome. */
	private String failure(RuntimeException failure, String restore) {
		Map<String, String> detail = new LinkedHashMap<>();
		detail.put("exception", failure.getClass().getName());
		detail.put("message", String.valueOf(failure.getMessage()));
		if (restore != null) {
			detail.put("restore", restore);
		}
		return json.writeValueAsString(detail);
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

}
