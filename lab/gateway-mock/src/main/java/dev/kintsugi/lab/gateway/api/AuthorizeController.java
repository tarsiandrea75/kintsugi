package dev.kintsugi.lab.gateway.api;

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kintsugi.lab.gateway.behavior.BehaviorStore;

/** The gateway API that payment-service calls: authorizes every payment after the configured latency. */
@RestController
class AuthorizeController {

	/** Gauge with the number of {@code /authorize} requests currently waiting on the simulated latency. */
	static final String INFLIGHT_GAUGE = "gateway.authorize.inflight";

	private final BehaviorStore store;

	private final AtomicInteger inflight = new AtomicInteger();

	AuthorizeController(BehaviorStore store, MeterRegistry registry) {
		this.store = store;
		// Requests piling up here is what fault F1 looks like; the gauge also lets tests observe real in-flight load.
		Gauge.builder(INFLIGHT_GAUGE, inflight, AtomicInteger::get)
			.description("POST /authorize requests currently waiting on the simulated latency")
			.register(registry);
	}

	/**
	 * Sleeps on the request thread on purpose: that is what a slow upstream looks like to the caller, and it is how
	 * fault F1 reaches payment-service.
	 */
	@PostMapping("/authorize")
	AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
		inflight.incrementAndGet();
		try {
			Thread.sleep(store.get().latencyMs());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while simulating latency",
					ex);
		}
		finally {
			inflight.decrementAndGet();
		}
		return AuthorizeResponse.AUTHORIZED;
	}

}
