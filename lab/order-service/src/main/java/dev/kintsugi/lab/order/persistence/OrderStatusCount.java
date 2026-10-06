package dev.kintsugi.lab.order.persistence;

import dev.kintsugi.lab.order.domain.OrderStatus;

/** Number of stored orders in one status; a row of {@link OrderStatusCounts#countOrdersPerStatus()}. */
public record OrderStatusCount(OrderStatus status, long count) {

}
