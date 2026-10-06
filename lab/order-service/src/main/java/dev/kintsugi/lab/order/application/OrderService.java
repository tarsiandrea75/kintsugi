package dev.kintsugi.lab.order.application;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.payment.PaymentClient;
import dev.kintsugi.lab.order.payment.PaymentOutcome;
import dev.kintsugi.lab.order.payment.PaymentUnavailableException;
import dev.kintsugi.lab.order.persistence.OrderRepository;

/**
 * Places an order: stores it as {@code PAYMENT_PENDING}, asks payment-service to pay it, then stores the outcome.
 *
 * <p>The pending order is saved before the call, and no transaction spans the call, so a database connection is
 * never held while waiting for payment-service. If payment-service gives no usable answer the order stays
 * {@code PAYMENT_PENDING}: that is the visible symptom of fault F1 (slow gateway).
 */
@Service
public class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	private final OrderRepository repository;

	private final PaymentClient paymentClient;

	public OrderService(OrderRepository repository, PaymentClient paymentClient) {
		this.repository = repository;
		this.paymentClient = paymentClient;
	}

	/**
	 * Takes a new order in {@code CREATED} through the payment step and returns it in its final state:
	 * {@code PAID}, {@code PAYMENT_FAILED}, or {@code PAYMENT_PENDING} when payment-service is unavailable.
	 */
	public Order place(Order order) {
		order.transitionTo(OrderStatus.PAYMENT_PENDING);
		Order pending = repository.save(order);
		PaymentOutcome outcome;
		try {
			outcome = paymentClient.pay(pending.getId());
		}
		catch (PaymentUnavailableException ex) {
			log.warn("Payment of order {} unavailable, leaving it in PAYMENT_PENDING: {}", pending.getId(),
					ex.getMessage());
			return pending;
		}
		pending.transitionTo(outcome == PaymentOutcome.AUTHORIZED ? OrderStatus.PAID : OrderStatus.PAYMENT_FAILED);
		return repository.save(pending);
	}

	public Optional<Order> find(UUID id) {
		return repository.findById(id);
	}

}
