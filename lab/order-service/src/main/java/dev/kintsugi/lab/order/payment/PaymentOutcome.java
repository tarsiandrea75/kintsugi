package dev.kintsugi.lab.order.payment;

/**
 * What payment-service decided for an order; mirrors its {@code PaymentStatus}. {@code FAILED} means the gateway
 * rejected the payment with a 429 (fault F2), as opposed to declining it.
 */
public enum PaymentOutcome {

	AUTHORIZED, DECLINED, FAILED

}
