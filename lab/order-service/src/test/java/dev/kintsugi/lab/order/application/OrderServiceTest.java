package dev.kintsugi.lab.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import dev.kintsugi.lab.order.domain.InsufficientStockException;
import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderItem;
import dev.kintsugi.lab.order.domain.OrderStatus;
import dev.kintsugi.lab.order.inventory.InventoryClient;
import dev.kintsugi.lab.order.inventory.InventoryUnavailableException;
import dev.kintsugi.lab.order.inventory.ReservationOutcome;
import dev.kintsugi.lab.order.payment.PaymentClient;
import dev.kintsugi.lab.order.payment.PaymentOutcome;
import dev.kintsugi.lab.order.payment.PaymentUnavailableException;
import dev.kintsugi.lab.order.persistence.OrderRepository;

@ExtendWith(OutputCaptureExtension.class)
class OrderServiceTest {

	private final OrderRepository repository = mock(OrderRepository.class);

	private final InventoryClient inventoryClient = mock(InventoryClient.class);

	private final PaymentClient paymentClient = mock(PaymentClient.class);

	private final OrderService service = new OrderService(repository, inventoryClient, paymentClient);

	/** The status of the order at each save, since the same instance is saved twice. */
	private final List<OrderStatus> savedStatuses = new ArrayList<>();

	private final Order order = Order.create("customer-1", List.of(new OrderItem("SKU-1", 1)));

	@BeforeEach
	void repositoryEchoesTheSavedOrderAndStockIsReserved() {
		given(repository.save(any(Order.class))).willAnswer(invocation -> {
			Order saved = invocation.getArgument(0);
			savedStatuses.add(saved.getStatus());
			return saved;
		});
		given(inventoryClient.reserve(order.getId(), order.getItems())).willReturn(ReservationOutcome.RESERVED);
	}

	@Test
	void stockIsReservedBeforeTheOrderIsSavedAndPaid() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.AUTHORIZED);

		service.place(order);

		InOrder calls = inOrder(inventoryClient, repository, paymentClient);
		calls.verify(inventoryClient).reserve(order.getId(), order.getItems());
		calls.verify(repository).save(order);
		calls.verify(paymentClient).pay(order.getId());
	}

	@Test
	void insufficientStockStoresNothingAndDoesNotPay() {
		given(inventoryClient.reserve(order.getId(), order.getItems()))
			.willReturn(ReservationOutcome.INSUFFICIENT_STOCK);

		assertThatExceptionOfType(InsufficientStockException.class).isThrownBy(() -> service.place(order))
			.withMessageContaining(order.getId().toString());

		verify(repository, never()).save(any());
		verify(paymentClient, never()).pay(any());
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
	}

	@Test
	void unavailableInventoryStoresNothingDoesNotPayAndLogsAWarning(CapturedOutput output) {
		InventoryUnavailableException unavailable = new InventoryUnavailableException(
				"HttpTimeoutException calling POST http://inventory-service:8083/reservations", null);
		given(inventoryClient.reserve(order.getId(), order.getItems())).willThrow(unavailable);

		assertThatExceptionOfType(InventoryUnavailableException.class).isThrownBy(() -> service.place(order))
			.isSameAs(unavailable);

		verify(repository, never()).save(any());
		verify(paymentClient, never()).pay(any());
		assertThat(output.getAll().lines()
			.filter(line -> line.contains(" WARN ") && line.contains(order.getId().toString())
					&& line.contains("HttpTimeoutException calling POST http://inventory-service:8083/reservations")))
			.hasSize(1);
	}

	@Test
	void authorizedOrderIsPaid() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.AUTHORIZED);

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAID);
		assertThat(savedStatuses).containsExactly(OrderStatus.PAYMENT_PENDING, OrderStatus.PAID);
	}

	@Test
	void declinedOrderFailsPayment() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.DECLINED);

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
		assertThat(savedStatuses).containsExactly(OrderStatus.PAYMENT_PENDING, OrderStatus.PAYMENT_FAILED);
	}

	@Test
	void failedPaymentFailsTheOrderLikeADecline() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.FAILED);

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
		assertThat(savedStatuses).containsExactly(OrderStatus.PAYMENT_PENDING, OrderStatus.PAYMENT_FAILED);
	}

	@Test
	void pendingOrderIsSavedBeforePaymentIsCalled() {
		given(paymentClient.pay(order.getId())).willReturn(PaymentOutcome.AUTHORIZED);

		service.place(order);

		InOrder calls = inOrder(repository, paymentClient);
		calls.verify(repository).save(order);
		calls.verify(paymentClient).pay(order.getId());
		calls.verify(repository).save(order);
	}

	@Test
	void unavailablePaymentLeavesOrderPendingAndLogsAWarning(CapturedOutput output) {
		given(paymentClient.pay(order.getId()))
			.willThrow(new PaymentUnavailableException("read timed out", new RuntimeException()));

		Order placed = service.place(order);

		assertThat(placed.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
		verify(repository, times(1)).save(any());
		assertThat(savedStatuses).containsExactly(OrderStatus.PAYMENT_PENDING);
		assertThat(output).contains("WARN").contains(order.getId().toString()).contains("read timed out");
	}

	@Test
	void existingOrderIsFound() {
		given(repository.findById(order.getId())).willReturn(Optional.of(order));

		assertThat(service.find(order.getId())).containsSame(order);
		verify(paymentClient, never()).pay(any());
	}

}
