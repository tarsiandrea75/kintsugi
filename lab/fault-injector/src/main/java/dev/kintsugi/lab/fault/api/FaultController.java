package dev.kintsugi.lab.fault.api;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.kintsugi.lab.fault.api.FaultResponses.Active;
import dev.kintsugi.lab.fault.api.FaultResponses.ActivateRequest;
import dev.kintsugi.lab.fault.api.FaultResponses.CatalogEntry;
import dev.kintsugi.lab.fault.api.FaultResponses.Created;
import dev.kintsugi.lab.fault.api.FaultResponses.Restored;
import dev.kintsugi.lab.fault.application.FaultInjectionService;
import dev.kintsugi.lab.fault.catalog.Fault;

/** The injector's REST API (openspec/specs/fault-injector/spec.md). Errors are mapped by {@link FaultErrorHandler}. */
@RestController
@RequestMapping("/faults")
class FaultController {

	private static final List<CatalogEntry> CATALOG = Arrays.stream(Fault.values())
		.map(fault -> new CatalogEntry(fault.id()))
		.toList();

	private final FaultInjectionService service;

	FaultController(FaultInjectionService service) {
		this.service = service;
	}

	@GetMapping("/catalog")
	List<CatalogEntry> catalog() {
		return CATALOG;
	}

	@PostMapping
	ResponseEntity<Created> activate(@RequestBody ActivateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(Created.of(service.activate(request.fault())));
	}

	@GetMapping("/active")
	ResponseEntity<Active> active() {
		return service.blocking()
			.map(injection -> ResponseEntity.ok(Active.of(injection)))
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/{id}/restore")
	Restored restore(@PathVariable UUID id) {
		return Restored.of(service.restore(id));
	}

}
