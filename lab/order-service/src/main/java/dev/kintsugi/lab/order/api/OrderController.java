package dev.kintsugi.lab.order.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.kintsugi.lab.order.application.OrderService;
import dev.kintsugi.lab.order.domain.InsufficientStockException;
import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderItem;
import dev.kintsugi.lab.order.inventory.InventoryUnavailableException;

@RestController
@RequestMapping("/orders")
class OrderController {

	private final OrderService service;

	OrderController(OrderService service) {
		this.service = service;
	}

	/**
	 * 201 once the order is stored: the body carries the payment outcome as the order's final status. 409 when the
	 * stock is short and 503 when inventory-service is unavailable; nothing is stored then (see the handlers below).
	 */
	@PostMapping
	ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
		Order order = service.place(toOrder(request));
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(order.getId())
			.toUri();
		return ResponseEntity.created(location).body(OrderResponse.from(order));
	}

	/**
	 * Safety net: validation should already reject anything the domain rejects, but if the two ever drift, a
	 * domain rule violation is still the client's fault (400), not a server error (500).
	 */
	private static Order toOrder(CreateOrderRequest request) {
		try {
			List<OrderItem> items = request.items()
				.stream()
				.map(item -> new OrderItem(item.sku(), item.quantity()))
				.toList();
			return Order.create(request.customerId(), items);
		}
		catch (IllegalArgumentException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
		}
	}

	/** The stock for the order could not be reserved, so the order was refused. Local to this controller. */
	@ExceptionHandler(InsufficientStockException.class)
	ResponseEntity<ProblemDetail> insufficientStock(InsufficientStockException ex) {
		return problem(HttpStatus.CONFLICT, "Insufficient stock");
	}

	/**
	 * Inventory-service gave no usable answer, so the order was refused; the service already logged the WARN line
	 * with the order id and the cause. 503 because the client may retry later. Local to this controller.
	 */
	@ExceptionHandler(InventoryUnavailableException.class)
	ResponseEntity<ProblemDetail> inventoryUnavailable(InventoryUnavailableException ex) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "Inventory unavailable");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
		return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
	}

	@GetMapping("/{id}")
	ResponseEntity<OrderResponse> get(@PathVariable UUID id) {
		return ResponseEntity.of(service.find(id).map(OrderResponse::from));
	}

}
