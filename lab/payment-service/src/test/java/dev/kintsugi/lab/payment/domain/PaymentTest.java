package dev.kintsugi.lab.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentTest {

	private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@ParameterizedTest
	@EnumSource(PaymentStatus.class)
	void newPaymentKeepsOrderIdAndOutcome(PaymentStatus outcome) {
		Payment payment = Payment.create(ORDER_ID, outcome);

		assertThat(payment.getId()).isNotNull();
		assertThat(payment.getOrderId()).isEqualTo(ORDER_ID);
		assertThat(payment.getStatus()).isEqualTo(outcome);
	}

	@Test
	void eachPaymentGetsItsOwnId() {
		assertThat(Payment.create(ORDER_ID, PaymentStatus.AUTHORIZED).getId())
			.isNotEqualTo(Payment.create(ORDER_ID, PaymentStatus.AUTHORIZED).getId());
	}

	@Test
	void createdAtIsCutToMicroseconds() {
		Payment payment = Payment.create(ORDER_ID, PaymentStatus.AUTHORIZED);

		assertThat(payment.getCreatedAt()).isNotNull()
			.isEqualTo(payment.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
	}

	@Test
	void nullOrderIdIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> Payment.create(null, PaymentStatus.AUTHORIZED));
	}

	@Test
	void nullStatusIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> Payment.create(ORDER_ID, null));
	}

}
