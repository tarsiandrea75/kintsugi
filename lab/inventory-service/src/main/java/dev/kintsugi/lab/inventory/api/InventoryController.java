package dev.kintsugi.lab.inventory.api;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.kintsugi.lab.inventory.application.InventoryService;
import dev.kintsugi.lab.inventory.domain.ReservationStatus;

@RestController
class InventoryController {

	private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

	private final InventoryService service;

	InventoryController(InventoryService service) {
		this.service = service;
	}

	/** 201 when every line was reserved, 409 when none was because a line was short or its SKU unknown. */
	@PostMapping("/reservations")
	ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody CreateReservationRequest request) {
		ReservationStatus status = service.reserve(request.toReservation());
		HttpStatus code = status == ReservationStatus.RESERVED ? HttpStatus.CREATED : HttpStatus.CONFLICT;
		return ResponseEntity.status(code).body(new ReservationResponse(request.orderId(), status));
	}

	@GetMapping("/stock/{sku}")
	ResponseEntity<StockResponse> stock(@PathVariable String sku) {
		return ResponseEntity.of(service.stock(sku).map(StockResponse::from));
	}

	/**
	 * No database connection within the pool's connection timeout: the pool is exhausted (fault F4) or the database
	 * is unreachable. A transaction that cannot start throws {@link CannotCreateTransactionException}; a query outside
	 * one throws {@link CannotGetJdbcConnectionException}. Spring already answers both with 500; this handler keeps
	 * the 500 and replaces the stack trace with one WARN line that carries the pool's own message (for example
	 * "Connection is not available, request timed out after 2000ms"). Local to this controller.
	 */
	@ExceptionHandler({ CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class })
	ResponseEntity<ProblemDetail> noDatabaseConnection(RuntimeException ex) {
		log.warn("No database connection available: {}", NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "No database connection available"));
	}

}
