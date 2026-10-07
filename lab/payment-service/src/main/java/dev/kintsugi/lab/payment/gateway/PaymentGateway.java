package dev.kintsugi.lab.payment.gateway;

import java.util.UUID;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

/**
 * Port to the external payment gateway that decides whether an order's payment is authorized.
 *
 * <p>The implementation is {@link HttpPaymentGateway}, which calls {@code gateway-mock}; that is where fault F1
 * (slow gateway) and fault F2 (gateway rejecting with 429) are injected.
 */
public interface PaymentGateway {

	/**
	 * Returns the gateway's outcome for the payment of {@code orderId}: {@code AUTHORIZED} or {@code DECLINED} as the
	 * gateway decided, or {@code FAILED} when the gateway rejected the request with a 429.
	 * @throws GatewayUnavailableException if the gateway gives no usable answer
	 */
	PaymentStatus authorize(UUID orderId);

}
