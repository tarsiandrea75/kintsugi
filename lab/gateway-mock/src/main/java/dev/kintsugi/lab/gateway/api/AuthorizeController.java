package dev.kintsugi.lab.gateway.api;

import java.util.concurrent.atomic.AtomicInteger;

import java.time.Duration;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import dev.kintsugi.lab.gateway.behavior.BehaviorStore;
import dev.kintsugi.lab.gateway.behavior.GatewayBehavior;

/**
 * The gateway API that payment-service calls: after the configured latency it authorizes the payment, or answers with
 * the configured error status.
 *
 * <p>Every call that reaches it logs exactly one structured line with {@code key=value} pairs, readable by Loki's
 * {@code logfmt} parser: {@code INFO} with the answer, or {@code WARN} when the latency was interrupted. The gateway has
 * no database by design, so this line is its record of each call.
 */
@RestController
class AuthorizeController {

	private static final Logger log = LoggerFactory.getLogger(AuthorizeController.class);

	/** Gauge with the number of {@code /authorize} requests currently waiting on the simulated latency. */
	static final String INFLIGHT_GAUGE = "gateway.authorize.inflight";

	private final BehaviorStore store;

	private final AtomicInteger inflight = new AtomicInteger();

	AuthorizeController(BehaviorStore store, MeterRegistry registry) {
		this.store = store;
		// Requests piling up here is what fault F1 looks like; the gauge also lets tests observe real in-flight load.
		Gauge.builder(INFLIGHT_GAUGE, inflight, AtomicInteger::get)
			.description("POST /authorize requests currently waiting on the simulated latency")
			// Gauges hold a weak reference by default; keep the counter alive even if this controller is not a bean.
			.strongReference(true)
			.register(registry);
	}

	/**
	 * Sleeps on the request thread on purpose: that is what a slow upstream looks like to the caller, and it is how
	 * fault F1 reaches payment-service. A configured error status is answered only after the latency, as a real gateway
	 * still takes time to reject; a 429 is how fault F2 reaches payment-service.
	 */
	@PostMapping("/authorize")
	ResponseEntity<?> authorize(@Valid @RequestBody AuthorizeRequest request) {
		// Read once, so the latency and the error status come from the same behavior.
		GatewayBehavior behavior = store.get();
		long start = System.nanoTime();
		inflight.incrementAndGet();
		try {
			Thread.sleep(behavior.latencyMs());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			log.warn("Authorize interrupted: {} interrupted=true",
					fields(request, behavior, HttpStatus.SERVICE_UNAVAILABLE.value(), start));
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while simulating latency",
					ex);
		}
		finally {
			inflight.decrementAndGet();
		}
		Integer errorStatus = behavior.errorStatus();
		int answered = errorStatus != null ? errorStatus : HttpStatus.OK.value();
		log.info("Authorize answered: {}", fields(request, behavior, answered, start));
		if (errorStatus != null) {
			return ResponseEntity.status(errorStatus).body(GatewayErrorResponse.forStatus(errorStatus));
		}
		return ResponseEntity.ok(AuthorizeResponse.AUTHORIZED);
	}

	/** The configured latency, the status answered and the actual duration, measured from before the latency. */
	private static String fields(AuthorizeRequest request, GatewayBehavior behavior, int answered, long startNanos) {
		return "event=AUTHORIZE order=" + request.orderId() + " latency_ms=" + behavior.latencyMs() + " answered="
				+ answered + " duration_ms=" + Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

}
