package dev.kintsugi.lab.gateway.api;

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

	private final BehaviorStore store;

	AuthorizeController(BehaviorStore store) {
		this.store = store;
	}

	/**
	 * Sleeps on the request thread on purpose: that is what a slow upstream looks like to the caller, and it is how
	 * fault F1 reaches payment-service.
	 */
	@PostMapping("/authorize")
	AuthorizeResponse authorize(@Valid @RequestBody AuthorizeRequest request) {
		try {
			Thread.sleep(store.get().latencyMs());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while simulating latency",
					ex);
		}
		return AuthorizeResponse.AUTHORIZED;
	}

}
