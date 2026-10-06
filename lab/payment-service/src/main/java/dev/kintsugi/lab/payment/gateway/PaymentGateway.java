package dev.kintsugi.lab.payment.gateway;

import java.util.UUID;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

/**
 * Port to the external payment gateway that decides whether an order's payment is authorized.
 *
 * <p>Today the only implementation is {@link AlwaysAuthorizeGateway}. The week-1 Thursday task replaces it with
 * an HTTP client to {@code gateway-mock}, which is where fault F1 (slow gateway) is injected.
 */
public interface PaymentGateway {

	/** Returns the gateway's outcome for the payment of {@code orderId}. */
	PaymentStatus authorize(UUID orderId);

}
