package dev.kintsugi.lab.payment.api;

import java.time.Instant;
import java.util.UUID;

import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentStatus;

/** Payment as returned by the API; keeps persistence details such as the version out of the contract. */
public record PaymentResponse(UUID id, UUID orderId, PaymentStatus status, Instant createdAt) {

	static PaymentResponse from(Payment payment) {
		return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getStatus(),
				payment.getCreatedAt());
	}

}
