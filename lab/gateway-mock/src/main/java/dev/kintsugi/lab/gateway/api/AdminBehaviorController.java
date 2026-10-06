package dev.kintsugi.lab.gateway.api;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kintsugi.lab.gateway.behavior.BehaviorStore;
import dev.kintsugi.lab.gateway.behavior.GatewayBehavior;

/** Control point for the fault injector: read, change and reset the gateway's behavior. No auth: this is a lab. */
@RestController
@RequestMapping("/admin/behavior")
class AdminBehaviorController {

	private final BehaviorStore store;

	AdminBehaviorController(BehaviorStore store) {
		this.store = store;
	}

	@GetMapping
	GatewayBehavior get() {
		return store.get();
	}

	@PutMapping
	GatewayBehavior set(@Valid @RequestBody BehaviorRequest request) {
		return store.set(request.toBehavior());
	}

	@DeleteMapping
	GatewayBehavior reset() {
		return store.reset();
	}

}
