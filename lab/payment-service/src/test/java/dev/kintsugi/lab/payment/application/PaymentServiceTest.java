package dev.kintsugi.lab.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.ResourceAccessException;

import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentEvent;
import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.gateway.GatewayUnavailableException;
import dev.kintsugi.lab.payment.gateway.PaymentGateway;
import dev.kintsugi.lab.payment.persistence.PaymentEventRepository;
import dev.kintsugi.lab.payment.persistence.PaymentRepository;

@ExtendWith(OutputCaptureExtension.class)
class PaymentServiceTest {

	private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final URI AUTHORIZE_URL = URI.create("http://gateway-mock:8082/authorize");

	private final PaymentGateway gateway = mock(PaymentGateway.class);

	private final PaymentRepository repository = mock(PaymentRepository.class);

	private final PaymentEventRepository events = mock(PaymentEventRepository.class);

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private final PaymentService service = new PaymentService(gateway, repository, events, transactionManager);

	@BeforeEach
	void transactionsOpen() {
		given(transactionManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
	}

	@ParameterizedTest
	@EnumSource(PaymentStatus.class)
	void storedStatusIsTheGatewayOutcomeAndOneEventRecordsIt(PaymentStatus outcome) {
		given(gateway.authorize(ORDER_ID)).willReturn(outcome);
		given(repository.save(any(Payment.class))).willAnswer(invocation -> invocation.getArgument(0));

		Payment payment = service.pay(ORDER_ID);

		ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getOrderId()).isEqualTo(ORDER_ID);
		assertThat(saved.getValue().getStatus()).isEqualTo(outcome);
		assertThat(payment).isSameAs(saved.getValue());
		PaymentEvent event = recordedEvent();
		assertThat(event.orderId()).isEqualTo(ORDER_ID);
		assertThat(event.type()).isEqualTo(PaymentEvent.PAYMENT_ATTEMPT);
		assertThat(event.outcome()).isEqualTo(outcome.name());
		assertThat(event.detail()).isEqualTo("{\"paymentId\":\"" + payment.getId() + "\"}");
		assertThat(event.durationMs()).isNotNegative();
	}

	@Test
	void gatewayFailurePropagatesAndStoresNothing() {
		IllegalStateException failure = new IllegalStateException("gateway down");
		given(gateway.authorize(ORDER_ID)).willThrow(failure);

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isSameAs(failure);
		verify(repository, never()).save(any());
	}

	@Test
	void unavailableGatewayStoresNoPaymentButRecordsTheAttempt() {
		GatewayUnavailableException unavailable = timedOut();
		given(gateway.authorize(ORDER_ID)).willThrow(unavailable);

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isSameAs(unavailable);

		verify(repository, never()).save(any());
		PaymentEvent event = recordedEvent();
		assertThat(event.orderId()).isEqualTo(ORDER_ID);
		assertThat(event.outcome()).isEqualTo(PaymentEvent.GATEWAY_UNAVAILABLE);
		assertThat(event.detail()).contains("\"exception\":\"java.net.http.HttpTimeoutException\"")
			.contains("\"url\":\"" + AUTHORIZE_URL + "\"");
		assertThat(event.durationMs()).isNotNegative();
	}

	@Test
	void unavailableGatewayWhoseEventCannotBeStoredIsLoggedAndStillUnavailable(CapturedOutput output) {
		GatewayUnavailableException unavailable = timedOut();
		given(gateway.authorize(ORDER_ID)).willThrow(unavailable);
		willThrow(new CannotCreateTransactionException("Could not open JDBC Connection",
				new SQLTransientConnectionException("HikariPool-1 - Connection is not available")))
			.given(transactionManager)
			.getTransaction(any());

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isSameAs(unavailable);

		List<String> warnLines = output.getAll().lines().filter(line -> line.contains(" WARN ")).toList();
		assertThat(warnLines).singleElement()
			.satisfies(line -> assertThat(line).contains("Payment event not stored: ")
				.contains("event=PAYMENT_ATTEMPT")
				.contains("order=" + ORDER_ID)
				.contains("outcome=GATEWAY_UNAVAILABLE")
				.contains("cause=\"HikariPool-1 - Connection is not available\""));
	}

	private PaymentEvent recordedEvent() {
		ArgumentCaptor<PaymentEvent> event = ArgumentCaptor.forClass(PaymentEvent.class);
		verify(events).insert(event.capture());
		return event.getValue();
	}

	private static GatewayUnavailableException timedOut() {
		return new GatewayUnavailableException("gateway timed out", AUTHORIZE_URL,
				new ResourceAccessException("I/O error", new HttpTimeoutException("request timed out")));
	}

}
