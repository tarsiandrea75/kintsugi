package dev.kintsugi.lab.order.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderItem;
import dev.kintsugi.lab.order.persistence.OrderRepository;

@RestController
@RequestMapping("/orders")
class OrderController {

	private final OrderRepository repository;

	OrderController(OrderRepository repository) {
		this.repository = repository;
	}

	@PostMapping
	ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
		Order order = repository.save(toOrder(request));
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

	@GetMapping("/{id}")
	ResponseEntity<OrderResponse> get(@PathVariable UUID id) {
		return ResponseEntity.of(repository.findById(id).map(OrderResponse::from));
	}

}
