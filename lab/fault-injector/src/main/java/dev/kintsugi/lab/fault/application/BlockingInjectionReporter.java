package dev.kintsugi.lab.fault.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.kintsugi.lab.fault.persistence.InjectionRepository;

/**
 * On startup, reports an injection left blocking (after a crash, or a failed restore) with one structured WARN line.
 * It never restores it: the operator decides, with {@code POST /faults/{id}/restore}. Until then new faults get 409.
 */
@Component
public class BlockingInjectionReporter {

	private static final Logger log = LoggerFactory.getLogger(BlockingInjectionReporter.class);

	private final InjectionRepository injections;

	public BlockingInjectionReporter(InjectionRepository injections) {
		this.injections = injections;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void reportBlockingInjection() {
		injections.findBlocking()
			.ifPresent(injection -> log.warn(
					"event=blocking_injection_at_startup injection_id={} fault={} status={} action=\"restore with POST /faults/{}/restore\"",
					injection.id(), injection.faultId(), injection.status(), injection.id()));
	}

}
