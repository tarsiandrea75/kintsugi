package dev.kintsugi.lab.order.payment;

/** What payment-service decided for an order; mirrors its {@code PaymentStatus}. */
public enum PaymentOutcome {

	AUTHORIZED, DECLINED

}
