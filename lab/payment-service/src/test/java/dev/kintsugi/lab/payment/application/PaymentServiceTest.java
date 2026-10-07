package dev.kintsugi.lab.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

	/**
	 * An unexpected exception is not a gateway answer: no payment is stored, the attempt is recorded as {@code ERROR}
	 * with the exception class and message, and the exception propagates (a 500).
	 */
	@Test
	void unexpectedFailureStoresNoPaymentRecordsAnErrorAndPropagates() {
		IllegalStateException failure = new IllegalStateException("gateway down");
		given(gateway.authorize(ORDER_ID)).willThrow(failure);

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isSameAs(failure);

		verify(repository, never()).save(any());
		PaymentEvent event = recordedEvent();
		assertThat(event.orderId()).isEqualTo(ORDER_ID);
		assertThat(event.outcome()).isEqualTo(PaymentEvent.ERROR);
		assertThat(event.detail())
			.isEqualTo("{\"exception\":\"java.lang.IllegalStateException\",\"message\":\"gateway down\"}");
		verify(transactionManager).commit(any());
	}

	/**
	 * The event shares the payment's transaction, so failing to store it rolls the payment back and the request fails.
	 * It is logged, and the failure itself is recorded as {@code ERROR} in a transaction of its own.
	 */
	@Test
	void failureStoringThePaymentEventIsLoggedRecordedAndPropagates(CapturedOutput output) {
		given(gateway.authorize(ORDER_ID)).willReturn(PaymentStatus.AUTHORIZED);
		IllegalStateException failure = new IllegalStateException("boom");
		willThrow(failure).willAnswer(invocation -> null).given(events).insert(any());

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isSameAs(failure);

		verify(transactionManager).rollback(any());
		ArgumentCaptor<PaymentEvent> inserted = ArgumentCaptor.forClass(PaymentEvent.class);
		verify(events, times(2)).insert(inserted.capture());
		assertThat(inserted.getAllValues()).extracting(PaymentEvent::outcome)
			.containsExactly("AUTHORIZED", PaymentEvent.ERROR);
		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("Payment event not stored: ")
				.contains("order=" + ORDER_ID)
				.contains("outcome=AUTHORIZED")
				.contains("record_failure=java.lang.IllegalStateException"));
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
	void unavailableGatewayIsOneStructuredWarnLine(CapturedOutput output) {
		given(gateway.authorize(ORDER_ID)).willThrow(timedOut());

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isInstanceOf(GatewayUnavailableException.class);

		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("Payment gateway unavailable: ")
				.contains("event=PAYMENT_ATTEMPT")
				.contains("order=" + ORDER_ID)
				.contains("outcome=GATEWAY_UNAVAILABLE")
				.contains("duration_ms=")
				.contains("url=\"" + AUTHORIZE_URL + "\"")
				.contains("exception=java.net.http.HttpTimeoutException")
				.contains("cause=\"gateway timed out\""));
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

		assertThat(notStoredLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("event=PAYMENT_ATTEMPT")
				.contains("order=" + ORDER_ID)
				.contains("outcome=GATEWAY_UNAVAILABLE")
				.contains("record_failure=org.springframework.transaction.CannotCreateTransactionException")
				.contains("cause=\"HikariPool-1 - Connection is not available\""));
	}

	/** Any failure to record the attempt, not only a missing connection, keeps the 503 and is logged. */
	@Test
	void unexpectedFailureRecordingAnUnavailableGatewayKeepsTheUnavailableAnswer(CapturedOutput output) {
		GatewayUnavailableException unavailable = timedOut();
		given(gateway.authorize(ORDER_ID)).willThrow(unavailable);
		willThrow(new IllegalStateException("boom")).given(events).insert(any());

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isSameAs(unavailable);

		assertThat(notStoredLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("order=" + ORDER_ID)
				.contains("outcome=GATEWAY_UNAVAILABLE")
				.contains("record_failure=java.lang.IllegalStateException"));
	}

	/** An unexpected failure whose ERROR event cannot be stored still propagates unchanged, and is logged. */
	@Test
	void unexpectedFailureWhoseEventCannotBeStoredStillPropagates(CapturedOutput output) {
		IllegalStateException failure = new IllegalStateException("gateway down");
		given(gateway.authorize(ORDER_ID)).willThrow(failure);
		willThrow(new IllegalArgumentException("bad row")).given(events).insert(any());

		assertThatThrownBy(() -> service.pay(ORDER_ID)).isSameAs(failure);

		assertThat(notStoredLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("outcome=ERROR")
				.contains("record_failure=java.lang.IllegalArgumentException"));
	}

	private static List<String> warnLines(CapturedOutput output) {
		return output.getAll().lines().filter(line -> line.contains(" WARN ")).toList();
	}

	private static List<String> notStoredLines(CapturedOutput output) {
		return warnLines(output).stream().filter(line -> line.contains("Payment event not stored: ")).toList();
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
