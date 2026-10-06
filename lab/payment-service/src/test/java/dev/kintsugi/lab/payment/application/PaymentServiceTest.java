package dev.kintsugi.lab.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.gateway.PaymentGateway;
import dev.kintsugi.lab.payment.persistence.PaymentRepository;

class PaymentServiceTest {

	private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private final PaymentGateway gateway = mock(PaymentGateway.class);

	private final PaymentRepository repository = mock(PaymentRepository.class);

	private final PaymentService service = new PaymentService(gateway, repository);

	@ParameterizedTest
	@EnumSource(PaymentStatus.class)
	void storedStatusIsTheGatewayOutcome(PaymentStatus outcome) {
		given(gateway.authorize(ORDER_ID)).willReturn(outcome);
		given(repository.save(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));

		Payment payment = service.pay(ORDER_ID);

		ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getOrderId()).isEqualTo(ORDER_ID);
		assertThat(saved.getValue().getStatus()).isEqualTo(outcome);
		assertThat(payment).isSameAs(saved.getValue());
	}

	@Test
	void gatewayFailurePropagatesAndStoresNothing() {
		IllegalStateException failure = new IllegalStateException("gateway down");
		given(gateway.authorize(ORDER_ID)).willThrow(failure);

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isSameAs(failure);
		verify(repository, never()).save(any());
	}

}
