package dev.kintsugi.lab.order.application;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.kintsugi.lab.order.domain.InsufficientStockException;
import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.inventory.InventoryClient;
import dev.kintsugi.lab.order.inventory.InventoryUnavailableException;
import dev.kintsugi.lab.order.inventory.ReservationOutcome;
import dev.kintsugi.lab.order.payment.PaymentClient;
import dev.kintsugi.lab.order.payment.PaymentOutcome;
import dev.kintsugi.lab.order.payment.PaymentUnavailableException;
import dev.kintsugi.lab.order.persistence.OrderRepository;

/**
 * Places an order: reserves its items on inventory-service, stores it as {@code PAYMENT_PENDING}, asks
 * payment-service to pay it, then stores the outcome.
 *
 * <p>Stock is reserved before anything is stored, so an order is only accepted once its stock exists. If inventory
 * is short the order is refused ({@link InsufficientStockException}); if inventory-service gives no usable answer
 * the order is refused too ({@link InventoryUnavailableException}) and a WARN line names the cause. Either way
 * nothing is stored. An unavailable inventory is how faults F4 (inventory pool exhausted) and F8 (inventory slow)
 * show on order-service.
 *
 * <p>The pending order is saved before the payment call, and no transaction spans either call, so a database
 * connection is never held while waiting for another service. If payment-service gives no usable answer the order stays
 * {@code PAYMENT_PENDING}: that is the visible symptom of fault F1 (slow gateway). A payment that failed because the
 * gateway rejected it with 429 moves the order to {@code PAYMENT_FAILED}: the symptom of fault F2.
 */
@Service
public class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	private final OrderRepository repository;

	private final InventoryClient inventoryClient;

	private final PaymentClient paymentClient;

	public OrderService(OrderRepository repository, InventoryClient inventoryClient, PaymentClient paymentClient) {
		this.repository = repository;
		this.inventoryClient = inventoryClient;
		this.paymentClient = paymentClient;
	}

	/**
	 * Takes a new order in {@code CREATED} through the inventory and payment steps and returns it in its final state:
	 * {@code PAID}, {@code PAYMENT_FAILED} (payment declined or failed), or {@code PAYMENT_PENDING} when payment-service
	 * is unavailable.
	 * @throws InsufficientStockException if inventory-service could not reserve every item; nothing is stored
	 * @throws InventoryUnavailableException if inventory-service gave no usable answer; nothing is stored
	 */
	public Order place(Order order) {
		reserveStock(order);
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
		// FAILED ends like DECLINED; the difference (rejected vs declined) stays in payment-service's record.
		pending.transitionTo(switch (outcome) {
			case AUTHORIZED -> OrderStatus.PAID;
			case DECLINED, FAILED -> OrderStatus.PAYMENT_FAILED;
		});
		return repository.save(pending);
	}

	private void reserveStock(Order order) {
		ReservationOutcome outcome;
		try {
			outcome = inventoryClient.reserve(order.getId(), order.getItems());
		}
		catch (InventoryUnavailableException ex) {
			log.warn("Inventory unavailable for order {}, order not stored: {}", order.getId(), ex.getMessage());
			throw ex;
		}
		// Exhaustive on purpose: a new outcome must be handled here before an order can be accepted with it.
		switch (outcome) {
			case RESERVED -> {
			}
			case INSUFFICIENT_STOCK -> throw new InsufficientStockException(order.getId());
		}
	}

	public Optional<Order> find(UUID id) {
		return repository.findById(id);
	}

}
