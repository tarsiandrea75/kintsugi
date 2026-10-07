package dev.kintsugi.lab.payment.domain;

/**
 * Outcome of a payment, as decided by the payment gateway. {@code FAILED} means the gateway rejected the request
 * itself (a 429, fault F2) rather than declining the payment.
 */
public enum PaymentStatus {

	AUTHORIZED, DECLINED, FAILED

}
