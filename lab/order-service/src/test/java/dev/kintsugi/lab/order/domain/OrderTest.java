package dev.kintsugi.lab.order.domain;

import static dev.kintsugi.lab.order.domain.OrderStatus.CREATED;
import static dev.kintsugi.lab.order.domain.OrderStatus.PAID;
import static dev.kintsugi.lab.order.domain.OrderStatus.PAYMENT_FAILED;
import static dev.kintsugi.lab.order.domain.OrderStatus.PAYMENT_PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderTest {

	private static final List<OrderItem> ONE_ITEM = List.of(new OrderItem("SKU-1", 2));

	/** The transition table of the spec; every pair not listed here must be rejected. */
	private static final Set<List<OrderStatus>> ALLOWED = Set.of(
			List.of(CREATED, PAYMENT_PENDING),
			List.of(PAYMENT_PENDING, PAID),
			List.of(PAYMENT_PENDING, PAYMENT_FAILED));

	@Test
	void newOrderStartsInCreatedWithIdAndTimestamps() {
		Order order = Order.create("customer-1", ONE_ITEM);

		assertThat(order.getId()).isNotNull();
		assertThat(order.getCustomerId()).isEqualTo("customer-1");
		assertThat(order.getItems()).containsExactly(new OrderItem("SKU-1", 2));
		assertThat(order.getStatus()).isEqualTo(CREATED);
		assertThat(order.getCreatedAt()).isNotNull();
		assertThat(order.getUpdatedAt()).isEqualTo(order.getCreatedAt());
	}

	@Test
	void allowedTransitionsReachPaid() {
		Order order = Order.create("customer-1", ONE_ITEM);

		order.transitionTo(PAYMENT_PENDING);
		order.transitionTo(PAID);

		assertThat(order.getStatus()).isEqualTo(PAID);
	}

	@Test
	void paymentPendingCanFail() {
		Order order = Order.create("customer-1", ONE_ITEM);
		order.transitionTo(PAYMENT_PENDING);

		order.transitionTo(PAYMENT_FAILED);

		assertThat(order.getStatus()).isEqualTo(PAYMENT_FAILED);
	}

	@Test
	void paidCannotGoBackToPaymentPending() {
		Order order = Order.create("customer-1", ONE_ITEM);
		order.transitionTo(PAYMENT_PENDING);
		order.transitionTo(PAID);

		assertThatIllegalStateException().isThrownBy(() -> order.transitionTo(PAYMENT_PENDING));
		assertThat(order.getStatus()).isEqualTo(PAID);
	}

	@Test
	void createdCannotSkipToPaid() {
		Order order = Order.create("customer-1", ONE_ITEM);

		assertThatIllegalStateException().isThrownBy(() -> order.transitionTo(PAID));
		assertThat(order.getStatus()).isEqualTo(CREATED);
	}

	@ParameterizedTest(name = "{0} -> {1}")
	@MethodSource("everyStatusPair")
	void onlyTheSpecTransitionTableIsAllowed(OrderStatus from, OrderStatus to) {
		assertThat(from.canTransitionTo(to)).isEqualTo(ALLOWED.contains(List.of(from, to)));
	}

	static Stream<Arguments> everyStatusPair() {
		return Stream.of(OrderStatus.values())
			.flatMap(from -> Stream.of(OrderStatus.values()).map(to -> Arguments.of(from, to)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankCustomerIdIsRejected(String customerId) {
		assertThatIllegalArgumentException().isThrownBy(() -> Order.create(customerId, ONE_ITEM));
	}

	@Test
	void emptyItemListIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> Order.create("customer-1", List.of()));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void nonPositiveQuantityIsRejected(int quantity) {
		assertThatIllegalArgumentException().isThrownBy(() -> new OrderItem("SKU-1", quantity));
	}

	@Test
	void blankSkuIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> new OrderItem(" ", 1));
	}

}
