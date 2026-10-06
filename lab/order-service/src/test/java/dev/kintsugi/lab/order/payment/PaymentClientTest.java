package dev.kintsugi.lab.order.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The payment client against a real HTTP server on loopback, with short timeouts so the failure cases stay fast. */
class PaymentClientTest {

	private static final UUID ORDER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

	private static final Duration TIMEOUT = Duration.ofMillis(200);

	private final FakePaymentServer server = FakePaymentServer.start();

	private final PaymentClient client = clientFor(server.baseUrl());

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void authorizedPaymentIsAuthorized() {
		server.replyWith(201, "AUTHORIZED");

		assertThat(client.pay(ORDER_ID)).isEqualTo(PaymentOutcome.AUTHORIZED);
	}

	@Test
	void declinedPaymentIsDeclined() {
		server.replyWith(201, "DECLINED");

		assertThat(client.pay(ORDER_ID)).isEqualTo(PaymentOutcome.DECLINED);
	}

	@Test
	void requestPostsTheOrderIdAsJson() {
		client.pay(ORDER_ID);

		FakePaymentServer.RecordedRequest request = server.lastRequest();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/payments");
		assertThat(request.contentType()).startsWith("application/json");
		assertThat(request.body()).contains("\"orderId\"").contains(ORDER_ID.toString());
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 500, 503 })
	void nonSuccessResponseIsUnavailable(int code) {
		server.replyWithBody(code, "{}");

		assertThatExceptionOfType(PaymentUnavailableException.class).isThrownBy(() -> client.pay(ORDER_ID))
			.withMessageContaining(ORDER_ID.toString());
	}

	@Test
	void unknownPaymentStatusIsUnavailable() {
		server.replyWith(201, "PENDING");

		assertThatExceptionOfType(PaymentUnavailableException.class).isThrownBy(() -> client.pay(ORDER_ID));
	}

	@Test
	void missingPaymentStatusIsUnavailable() {
		server.replyWithBody(201, "{}");

		assertThatExceptionOfType(PaymentUnavailableException.class).isThrownBy(() -> client.pay(ORDER_ID));
	}

	@Test
	void slowResponseTimesOutAfterTheReadTimeout() {
		server.replyAfter(Duration.ofSeconds(2));

		long start = System.nanoTime();
		assertThatExceptionOfType(PaymentUnavailableException.class).isThrownBy(() -> client.pay(ORDER_ID))
			.withMessageContaining(ORDER_ID.toString());
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void unreachableServiceIsUnavailable() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		PaymentClient unreachable = clientFor("http://localhost:" + closedPort);

		assertThatExceptionOfType(PaymentUnavailableException.class).isThrownBy(() -> unreachable.pay(ORDER_ID))
			.withMessageContaining(ORDER_ID.toString());
	}

	private static PaymentClient clientFor(String baseUrl) {
		return new PaymentClient(new PaymentProperties(URI.create(baseUrl), TIMEOUT, TIMEOUT));
	}

}
