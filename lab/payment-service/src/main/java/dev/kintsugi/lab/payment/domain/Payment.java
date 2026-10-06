package dev.kintsugi.lab.payment.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Payment of one order. Its status is the gateway's outcome and never changes after creation.
 *
 * <p>The id is assigned here, not by the database, so the {@code version} column is what tells Spring Data JDBC
 * whether to insert (null version) or update.
 */
@Table("payments")
public class Payment {

	@Id
	private final UUID id;

	private final UUID orderId;

	private final PaymentStatus status;

	@Version
	private Long version;

	private final Instant createdAt;

	@PersistenceCreator
	Payment(UUID id, UUID orderId, PaymentStatus status, Long version, Instant createdAt) {
		this.id = id;
		this.orderId = orderId;
		this.status = status;
		this.version = version;
		this.createdAt = createdAt;
	}

	/** Creates a new payment for {@code orderId} with the gateway's {@code outcome}. */
	public static Payment create(UUID orderId, PaymentStatus outcome) {
		if (orderId == null) {
			throw new IllegalArgumentException("Order id must not be null");
		}
		if (outcome == null) {
			throw new IllegalArgumentException("Payment outcome must not be null");
		}
		// PostgreSQL stores microseconds, so a value read back equals the value written.
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		return new Payment(UUID.randomUUID(), orderId, outcome, null, now);
	}

	public UUID getId() {
		return id;
	}

	public UUID getOrderId() {
		return orderId;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
