package dev.kintsugi.lab.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.ResourceAccessException;

import dev.kintsugi.lab.order.domain.InsufficientStockException;
import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderEvent;
import dev.kintsugi.lab.order.domain.OrderItem;
import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.inventory.InventoryClient;
import dev.kintsugi.lab.order.inventory.InventoryUnavailableException;
import dev.kintsugi.lab.order.inventory.ReservationOutcome;
import dev.kintsugi.lab.order.payment.PaymentClient;
import dev.kintsugi.lab.order.payment.PaymentOutcome;
import dev.kintsugi.lab.order.payment.PaymentUnavailableException;
import dev.kintsugi.lab.order.persistence.OrderEventRepository;
import dev.kintsugi.lab.order.persistence.OrderRepository;

@ExtendWith(OutputCaptureExtension.class)
class OrderServiceTest {

	private static final String RESERVATIONS_URL = "http://inventory-service:8083/reservations";

	private static final String PAYMENTS_URL = "http://payment-service:8081/payments";

	private final OrderRepository repository = mock(OrderRepository.class);

	private final OrderEventRepository events = mock(OrderEventRepository.class);

	private final InventoryClient inventoryClient = mock(InventoryClient.class);

	private final PaymentClient paymentClient = mock(PaymentClient.class);

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private final OrderService service = new OrderService(repository, events, inventoryClient, paymentClient,
			transactionManager);

	/** The status of the order at each save, since the same instance is saved several times. */
	private final List<OrderStatus> savedStatuses = new ArrayList<>();

	/** Every event inserted, in order. */
	private final List<OrderEvent> insertedEvents = new ArrayList<>();

	private final Order order = Order.create("customer-1", List.of(new OrderItem("SKU-1", 1)));

	@BeforeEach
	void repositoriesRecordAndStockIsReserved() {
		given(transactionManager.getTransaction(any())).willAnswer(invocation -> new SimpleTransactionStatus());
		given(repository.save(any(Order.class))).willAnswer(invocation -> {
			Order saved = invocation.getArgument(0);
			savedStatuses.add(saved.getStatus());
			return saved;
		});
		org.mockito.BDDMockito.willAnswer(invocation -> insertedEvents.add(invocation.getArgument(0)))
			.given(events)
			.insert(any());
		given(inventoryClient.reserve(order.getId(), order.getItems())).willReturn(ReservationOutcome.RESERVED);
	}

	@Test
	void happyPathStoresEveryStepWithItsEvent() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.AUTHORIZED);

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(savedStatuses).containsExactly(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING, OrderStatus.PAID);
		assertThat(insertedEvents).extracting(OrderEvent::type)
			.containsExactly(OrderEvent.ORDER_RECEIVED, OrderEvent.INVENTORY_RESERVED, OrderEvent.PAYMENT_AUTHORIZED);
		assertThat(insertedEvents).extracting(OrderEvent::outcome)
			.containsExactly("CREATED", "PAYMENT_PENDING", "PAID");
		assertThat(insertedEvents).allSatisfy(event -> assertThat(event.orderId()).isEqualTo(order.getId()));
		assertThat(insertedEvents.getFirst().detail()).contains("\"customerId\":\"customer-1\"").contains("SKU-1");
		assertThat(insertedEvents.get(1).durationMs()).isNotNegative();
		assertThat(insertedEvents.get(2).durationMs()).isNotNegative();
	}

	/** Each status and its event are written in one transaction; the calls to other services run outside any. */
	@Test
	void eachStatusAndItsEventShareATransactionAndCallsRunOutsideThem() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.AUTHORIZED);

		service.place(order);

		InOrder calls = inOrder(transactionManager, repository, events, inventoryClient, paymentClient);
		calls.verify(transactionManager).getTransaction(any());
		calls.verify(repository).save(order);
		calls.verify(events).insert(argThat(event -> event.type().equals(OrderEvent.ORDER_RECEIVED)));
		calls.verify(transactionManager).commit(any());
		calls.verify(inventoryClient).reserve(order.getId(), order.getItems());
		calls.verify(transactionManager).getTransaction(any());
		calls.verify(repository).save(order);
		calls.verify(events).insert(argThat(event -> event.type().equals(OrderEvent.INVENTORY_RESERVED)));
		calls.verify(transactionManager).commit(any());
		calls.verify(paymentClient).pay(order.getId());
		calls.verify(transactionManager).getTransaction(any());
		calls.verify(repository).save(order);
		calls.verify(events).insert(argThat(event -> event.type().equals(OrderEvent.PAYMENT_AUTHORIZED)));
		calls.verify(transactionManager).commit(any());
	}

	@Test
	void insufficientStockStoresARejectedOrderAndDoesNotPay() {
		given(inventoryClient.reserve(order.getId(), order.getItems()))
			.willReturn(ReservationOutcome.INSUFFICIENT_STOCK);

		assertThatExceptionOfType(InsufficientStockException.class).isThrownBy(() -> service.place(order))
			.satisfies(ex -> assertThat(ex.getOrderId()).isEqualTo(order.getId()));

		verify(paymentClient, never()).pay(any());
		assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED_OUT_OF_STOCK);
		assertThat(savedStatuses).containsExactly(OrderStatus.CREATED, OrderStatus.REJECTED_OUT_OF_STOCK);
		assertThat(insertedEvents).extracting(OrderEvent::type)
			.containsExactly(OrderEvent.ORDER_RECEIVED, OrderEvent.INVENTORY_REJECTED);
		assertThat(insertedEvents.get(1).outcome()).isEqualTo("REJECTED_OUT_OF_STOCK");
	}

	@Test
	void unavailableInventoryStoresARejectedOrderWithTheCauseAndLogsAWarning(CapturedOutput output) {
		InventoryUnavailableException unavailable = new InventoryUnavailableException(order.getId(), RESERVATIONS_URL,
				"HttpTimeoutException calling POST " + RESERVATIONS_URL,
				new ResourceAccessException("I/O error", new java.net.http.HttpTimeoutException("request timed out")));
		given(inventoryClient.reserve(order.getId(), order.getItems())).willThrow(unavailable);

		assertThatExceptionOfType(InventoryUnavailableException.class).isThrownBy(() -> service.place(order))
			.isSameAs(unavailable);

		verify(paymentClient, never()).pay(any());
		assertThat(savedStatuses).containsExactly(OrderStatus.CREATED, OrderStatus.REJECTED_INVENTORY_UNAVAILABLE);
		assertThat(insertedEvents).extracting(OrderEvent::type)
			.containsExactly(OrderEvent.ORDER_RECEIVED, OrderEvent.INVENTORY_UNAVAILABLE);
		OrderEvent event = insertedEvents.get(1);
		assertThat(event.outcome()).isEqualTo("REJECTED_INVENTORY_UNAVAILABLE");
		assertThat(event.detail()).contains("\"exception\":\"java.net.http.HttpTimeoutException\"")
			.contains("\"url\":\"" + RESERVATIONS_URL + "\"");
		assertThat(event.durationMs()).isNotNegative();
		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("Inventory unavailable for order " + order.getId())
				.contains("event=INVENTORY_UNAVAILABLE")
				.contains("order=" + order.getId())
				.contains("outcome=REJECTED_INVENTORY_UNAVAILABLE")
				.contains("url=\"" + RESERVATIONS_URL + "\"")
				.contains("exception=java.net.http.HttpTimeoutException")
				.contains("HttpTimeoutException calling POST " + RESERVATIONS_URL));
	}

	@Test
	void declinedOrderFailsPayment() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.DECLINED);

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
		assertThat(savedStatuses).endsWith(OrderStatus.PAYMENT_FAILED);
		assertThat(insertedEvents.getLast().type()).isEqualTo(OrderEvent.PAYMENT_DECLINED);
	}

	@Test
	void failedPaymentFailsTheOrderLikeADecline() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.FAILED);

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
		assertThat(savedStatuses).endsWith(OrderStatus.PAYMENT_FAILED);
		assertThat(insertedEvents.getLast().type()).isEqualTo(OrderEvent.PAYMENT_FAILED);
	}

	@Test
	void unavailablePaymentLeavesOrderPendingRecordsItAndLogsAWarning(CapturedOutput output) {
		given(paymentClient.pay(order.getId())).willThrow(new PaymentUnavailableException(order.getId(), PAYMENTS_URL,
				"read timed out", new ResourceAccessException("I/O error",
						new java.net.http.HttpTimeoutException("request timed out"))));

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
		assertThat(savedStatuses).containsExactly(OrderStatus.CREATED, OrderStatus.PAYMENT_PENDING);
		assertThat(insertedEvents).extracting(OrderEvent::type)
			.containsExactly(OrderEvent.ORDER_RECEIVED, OrderEvent.INVENTORY_RESERVED,
					OrderEvent.PAYMENT_UNAVAILABLE);
		OrderEvent event = insertedEvents.getLast();
		assertThat(event.outcome()).isEqualTo("PAYMENT_PENDING");
		assertThat(event.detail()).contains("\"exception\":\"java.net.http.HttpTimeoutException\"")
			.contains("\"url\":\"" + PAYMENTS_URL + "\"");
		assertThat(event.durationMs()).isNotNegative();
		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line)
				.contains("Payment of order " + order.getId() + " unavailable, leaving it in PAYMENT_PENDING: ")
				.contains("event=PAYMENT_UNAVAILABLE")
				.contains("url=\"" + PAYMENTS_URL + "\"")
				.contains("cause=\"read timed out\""));
	}

	/** The unavailable-payment event changes no status, so failing to store it never masks the 201. */
	@Test
	void unavailablePaymentWhoseEventCannotBeStoredStillAnswersPending(CapturedOutput output) {
		given(paymentClient.pay(order.getId()))
			.willThrow(new PaymentUnavailableException(order.getId(), PAYMENTS_URL, "read timed out", null));
		willThrow(new IllegalStateException("boom")).given(events)
			.insert(argThat(event -> event.type().equals(OrderEvent.PAYMENT_UNAVAILABLE)));

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
		assertThat(warnLines(output)).filteredOn(line -> line.contains("Order event not stored: "))
			.singleElement()
			.satisfies(line -> assertThat(line).contains("event=PAYMENT_UNAVAILABLE")
				.contains("order=" + order.getId())
				.contains("record_failure=java.lang.IllegalStateException"));
	}

	/**
	 * A status and its event share one transaction, so failing to store the event rolls the status back and the request
	 * fails. It is still logged with the order id.
	 */
	@Test
	void eventThatSharesATransactionFailsTheRequestAndIsLogged(CapturedOutput output) {
		IllegalStateException failure = new IllegalStateException("boom");
		willThrow(failure).given(events)
			.insert(argThat(event -> event.type().equals(OrderEvent.INVENTORY_RESERVED)));

		assertThatThrownBy(() -> service.place(order)).isSameAs(failure);

		verify(transactionManager).rollback(any());
		verify(paymentClient, never()).pay(any());
		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("Order event not stored: ")
				.contains("event=INVENTORY_RESERVED")
				.contains("order=" + order.getId())
				.contains("outcome=PAYMENT_PENDING")
				.contains("record_failure=java.lang.IllegalStateException"));
	}

	@Test
	void existingOrderIsFound() {
		given(repository.findById(order.getId())).willReturn(Optional.of(order));

		assertThat(service.find(order.getId())).containsSame(order);
		verify(paymentClient, never()).pay(any());
	}

	private static List<String> warnLines(CapturedOutput output) {
		return output.getAll().lines().filter(line -> line.contains(" WARN ")).toList();
	}

}
