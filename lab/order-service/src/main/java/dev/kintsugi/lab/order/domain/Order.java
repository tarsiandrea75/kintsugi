package dev.kintsugi.lab.order.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Order aggregate root. It owns its items and guards its status transitions.
 *
 * <p>The id is assigned here, not by the database, so the {@code version} column is what tells Spring
 * Data JDBC whether to insert (null version) or update.
 */
@Table("orders")
public class Order {

	/** Longest customer id or SKU, in characters; matches the {@code VARCHAR(64)} columns. */
	public static final int MAX_ID_LENGTH = 64;

	/** Most items one order may hold. */
	public static final int MAX_ITEMS = 100;

	@Id
	private final UUID id;

	private final String customerId;

	@MappedCollection(idColumn = "order_id", keyColumn = "item_index")
	private final List<OrderItem> items;

	private OrderStatus status;

	@Version
	private Long version;

	private final Instant createdAt;

	private Instant updatedAt;

	@PersistenceCreator
	Order(UUID id, String customerId, List<OrderItem> items, OrderStatus status, Long version, Instant createdAt,
			Instant updatedAt) {
		this.id = id;
		this.customerId = customerId;
		this.items = List.copyOf(items);
		this.status = status;
		this.version = version;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	/** Creates a new order in {@link OrderStatus#CREATED}. */
	public static Order create(String customerId, List<OrderItem> items) {
		requireId(customerId, "Customer id");
		if (items == null || items.isEmpty()) {
			throw new IllegalArgumentException("An order needs at least one item");
		}
		if (items.size() > MAX_ITEMS) {
			throw new IllegalArgumentException("An order holds at most " + MAX_ITEMS + " items, got " + items.size());
		}
		if (items.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Order items must not be null");
		}
		Instant now = now();
		return new Order(UUID.randomUUID(), customerId, items, OrderStatus.CREATED, null, now, now);
	}

	/**
	 * Moves the order to {@code target}.
	 * @throws IllegalStateException if the transition is not allowed; the status is then unchanged
	 */
	public void transitionTo(OrderStatus target) {
		if (!status.canTransitionTo(target)) {
			throw new IllegalStateException("Order " + id + " cannot move from " + status + " to " + target);
		}
		status = target;
		updatedAt = now();
	}

	/**
	 * The single "blank" and length rule for customer ids and SKUs. {@code isBlank()} also catches Unicode
	 * whitespace such as U+2003; the API's {@code @NotBlank} (Hibernate Validator 9) uses the same predicate.
	 */
	static void requireId(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		if (value.length() > MAX_ID_LENGTH) {
			throw new IllegalArgumentException(name + " holds at most " + MAX_ID_LENGTH + " characters");
		}
	}

	// PostgreSQL stores microseconds, so a value read back equals the value written.
	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public UUID getId() {
		return id;
	}

	public String getCustomerId() {
		return customerId;
	}

	public List<OrderItem> getItems() {
		return items;
	}

	public OrderStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
