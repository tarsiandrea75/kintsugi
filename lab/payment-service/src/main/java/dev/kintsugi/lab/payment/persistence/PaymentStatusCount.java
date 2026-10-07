package dev.kintsugi.lab.payment.persistence;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

/** Number of stored payments in one status; a row of {@link PaymentStatusCounts#countPaymentsPerStatus()}. */
public record PaymentStatusCount(PaymentStatus status, long count) {

}
