package dev.kintsugi.lab.order.application;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.lab.order.domain.InsufficientStockException;
import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderEvent;
import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.inventory.InventoryClient;
import dev.kintsugi.lab.order.inventory.InventoryUnavailableException;
import dev.kintsugi.lab.order.inventory.ReservationOutcome;
import dev.kintsugi.lab.order.payment.PaymentClient;
import dev.kintsugi.lab.order.payment.PaymentOutcome;
import dev.kintsugi.lab.order.payment.PaymentUnavailableException;
import dev.kintsugi.lab.order.persistence.OrderEventRepository;
import dev.kintsugi.lab.order.persistence.OrderRepository;

/**
 * Places an order and records every step of it: stores it as {@code CREATED}, reserves its items on
 * inventory-service, asks payment-service to pay it, and stores each answer.
 *
 * <p>Every step leaves an {@code order_events} row, written in one transaction with the status it leads to, so a
 * status never exists without the event that explains it: {@code ORDER_RECEIVED} with {@code CREATED}; then
 * {@code INVENTORY_RESERVED} with {@code PAYMENT_PENDING}, {@code INVENTORY_REJECTED} with
 * {@code REJECTED_OUT_OF_STOCK} ({@link InsufficientStockException}, a 409), or {@code INVENTORY_UNAVAILABLE} with
 * {@code REJECTED_INVENTORY_UNAVAILABLE} ({@link InventoryUnavailableException}, a 503); then
 * {@code PAYMENT_AUTHORIZED}, {@code PAYMENT_DECLINED} or {@code PAYMENT_FAILED} with the payment's outcome. An
 * unavailable payment-service changes no status, so its {@code PAYMENT_UNAVAILABLE} event is written alone and the
 * order stays {@code PAYMENT_PENDING}: the visible symptom of fault F1. An unavailable inventory is how faults F4 and F8
 * show on order-service.
 *
 * <p>The calls to the other services run outside any transaction, so a database connection is never held while waiting
 * for another service. When a status and its event cannot be stored, both roll back and the request fails; the event
 * is logged as one structured WARN line first. When an event without a status change cannot be stored, it is logged
 * the same way and the answer is unchanged. Nothing is dropped silently.
 */
@Service
public class OrderService {

	private static final Logger log = LoggerFactory.getLogger(OrderService.class);

	private final OrderRepository repository;

	private final OrderEventRepository events;

	private final InventoryClient inventoryClient;

	private final PaymentClient paymentClient;

	private final TransactionTemplate transaction;

	public OrderService(OrderRepository repository, OrderEventRepository events, InventoryClient inventoryClient,
			PaymentClient paymentClient, PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.events = events;
		this.inventoryClient = inventoryClient;
		this.paymentClient = paymentClient;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Takes a new order in {@code CREATED} through the inventory and payment steps and returns it in its final state:
	 * {@code PAID}, {@code PAYMENT_FAILED} (payment declined or failed), or {@code PAYMENT_PENDING} when payment-service
	 * is unavailable.
	 * @throws InsufficientStockException if inventory-service could not reserve every item; the order is stored as
	 * {@code REJECTED_OUT_OF_STOCK}
	 * @throws InventoryUnavailableException if inventory-service gave no usable answer; the order is stored as
	 * {@code REJECTED_INVENTORY_UNAVAILABLE}
	 */
	public Order place(Order order) {
		Order created = store(order, event(order, OrderEvent.ORDER_RECEIVED, received(order), null));
		Order pending = reserveStock(created);
		long start = System.nanoTime();
		PaymentOutcome outcome;
		try {
			outcome = paymentClient.pay(pending.getId());
		}
		catch (PaymentUnavailableException ex) {
			OrderEvent event = event(pending, OrderEvent.PAYMENT_UNAVAILABLE, unavailableDetail(ex, ex.getUrl()),
					millisSince(start));
			log.warn("Payment of order {} unavailable, leaving it in PAYMENT_PENDING: {} url={} exception={} cause={}",
					pending.getId(), logFields(event), LogFmt.quote(ex.getUrl()), exceptionClass(ex),
					LogFmt.quote(ex.getMessage()));
			recordAlone(event);
			return pending;
		}
		// FAILED ends like DECLINED; the event and payment-service's record keep the difference (rejected vs declined).
		pending.transitionTo(switch (outcome) {
			case AUTHORIZED -> OrderStatus.PAID;
			case DECLINED, FAILED -> OrderStatus.PAYMENT_FAILED;
		});
		String type = switch (outcome) {
			case AUTHORIZED -> OrderEvent.PAYMENT_AUTHORIZED;
			case DECLINED -> OrderEvent.PAYMENT_DECLINED;
			case FAILED -> OrderEvent.PAYMENT_FAILED;
		};
		return store(pending, event(pending, type, null, millisSince(start)));
	}

	public Optional<Order> find(UUID id) {
		return repository.findById(id);
	}

	/** Reserves the stock of the stored {@code order} and stores the answer; returns it in {@code PAYMENT_PENDING}. */
	private Order reserveStock(Order order) {
		long start = System.nanoTime();
		ReservationOutcome outcome;
		try {
			outcome = inventoryClient.reserve(order.getId(), order.getItems());
		}
		catch (InventoryUnavailableException ex) {
			order.transitionTo(OrderStatus.REJECTED_INVENTORY_UNAVAILABLE);
			OrderEvent event = event(order, OrderEvent.INVENTORY_UNAVAILABLE, unavailableDetail(ex, ex.getUrl()),
					millisSince(start));
			log.warn("Inventory unavailable for order {}, order rejected: {} url={} exception={} cause={}",
					order.getId(), logFields(event), LogFmt.quote(ex.getUrl()), exceptionClass(ex),
					LogFmt.quote(ex.getMessage()));
			store(order, event);
			throw ex;
		}
		// A switch expression, so the compiler rejects a new outcome until it is handled here.
		return switch (outcome) {
			case RESERVED -> {
				order.transitionTo(OrderStatus.PAYMENT_PENDING);
				yield store(order, event(order, OrderEvent.INVENTORY_RESERVED, null, millisSince(start)));
			}
			case INSUFFICIENT_STOCK -> {
				order.transitionTo(OrderStatus.REJECTED_OUT_OF_STOCK);
				store(order, event(order, OrderEvent.INVENTORY_REJECTED, null, millisSince(start)));
				throw new InsufficientStockException(order.getId());
			}
		};
	}

	/**
	 * Saves {@code order} and inserts {@code event} in one transaction. If either fails both roll back: the event is
	 * logged and the failure propagates, so the request fails.
	 */
	private Order store(Order order, OrderEvent event) {
		try {
			return transaction.execute(status -> {
				Order saved = repository.save(order);
				events.insert(event);
				return saved;
			});
		}
		catch (RuntimeException ex) {
			logNotStored(event, ex);
			throw ex;
		}
	}

	/** Inserts an event that changes no status, in a transaction of its own; when that fails, logs it instead. */
	private void recordAlone(OrderEvent event) {
		try {
			transaction.executeWithoutResult(status -> events.insert(event));
		}
		catch (RuntimeException ex) {
			logNotStored(event, ex);
		}
	}

	/** An event of {@code order}, whose outcome is the order's status now. */
	private static OrderEvent event(Order order, String type, String detail, Long durationMs) {
		return new OrderEvent(UUID.randomUUID(), order.getId(), Instant.now().truncatedTo(ChronoUnit.MICROS), type,
				order.getStatus().name(), detail, durationMs);
	}

	private static String received(Order order) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("customerId", order.getCustomerId());
		detail.put("items", order.getItems());
		return JsonMapper.shared().writeValueAsString(detail);
	}

	/** The most specific cause's class (for example {@code java.net.http.HttpTimeoutException}), URL and message. */
	private static String unavailableDetail(RuntimeException ex, String url) {
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("exception", exceptionClass(ex));
		detail.put("url", url);
		detail.put("message", ex.getMessage());
		return JsonMapper.shared().writeValueAsString(detail);
	}

	private static String exceptionClass(RuntimeException ex) {
		return NestedExceptionUtils.getMostSpecificCause(ex).getClass().getName();
	}

	private static long millisSince(long startNanos) {
		return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	private static String logFields(OrderEvent event) {
		return "event=" + event.type() + " order=" + event.orderId() + " outcome=" + event.outcome() + " duration_ms="
				+ event.durationMs();
	}

	private static void logNotStored(OrderEvent event, RuntimeException ex) {
		log.warn("Order event not stored: {} detail={} record_failure={} cause={}", logFields(event),
				LogFmt.quote(event.detail()), ex.getClass().getName(),
				LogFmt.quote(NestedExceptionUtils.getMostSpecificCause(ex).getMessage()));
	}

}
