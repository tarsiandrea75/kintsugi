package dev.kintsugi.lab.payment.gateway;

import java.util.UUID;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

/**
 * Port to the external payment gateway that decides whether an order's payment is authorized.
 *
 * <p>The implementation is {@link HttpPaymentGateway}, which calls {@code gateway-mock}; that is where fault F1
 * (slow gateway) is injected.
 */
public interface PaymentGateway {

	/**
	 * Returns the gateway's outcome for the payment of {@code orderId}.
	 * @throws GatewayUnavailableException if the gateway gives no usable answer
	 */
	PaymentStatus authorize(UUID orderId);

}
