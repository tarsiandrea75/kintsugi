package dev.kintsugi.lab.order.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
		// A bound but non-listening socket holds the port, so the kernel refuses connections and no one else can take it.
		try (Socket portHolder = new Socket()) {
			portHolder.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
			PaymentClient unreachable = clientFor("http://127.0.0.1:" + portHolder.getLocalPort());

			assertThatExceptionOfType(PaymentUnavailableException.class).isThrownBy(() -> unreachable.pay(ORDER_ID))
				.withMessageContaining(ORDER_ID.toString());
		}
	}

	@Test
	void hangingConnectTimesOutAfterTheConnectTimeout() throws IOException {
		// The read timeout is long, so only the connect timeout can end the call quickly. The JDK client reports both as
		// HttpConnectTimeoutException while connecting, so the elapsed time is what proves the connect timeout applies.
		try (SaturatedListener listener = SaturatedListener.open()) {
			PaymentClient hanging = new PaymentClient(new PaymentProperties(
					URI.create("http://127.0.0.1:" + listener.port()), TIMEOUT, Duration.ofSeconds(10)));

			long start = System.nanoTime();
			assertThatExceptionOfType(PaymentUnavailableException.class).isThrownBy(() -> hanging.pay(ORDER_ID))
				.withMessageContaining(ORDER_ID.toString())
				.satisfies(ex -> assertThat(causesOf(ex)).hasAtLeastOneElementOfType(HttpConnectTimeoutException.class));
			Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

			assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT).isLessThan(Duration.ofSeconds(3));
		}
	}

	private static PaymentClient clientFor(String baseUrl) {
		return new PaymentClient(new PaymentProperties(URI.create(baseUrl), TIMEOUT, TIMEOUT));
	}

	private static List<Throwable> causesOf(Throwable ex) {
		List<Throwable> causes = new ArrayList<>();
		for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
			causes.add(cause);
		}
		return causes;
	}

	/**
	 * A loopback listener that never accepts, with its accept queue already full. Linux then drops new SYNs
	 * ({@code tcp_abort_on_overflow=0}, the default), so a client's TCP handshake hangs instead of being refused.
	 */
	private record SaturatedListener(ServerSocket server, List<Socket> queued) implements AutoCloseable {

		private static final int MAX_QUEUED = 16;

		static SaturatedListener open() throws IOException {
			ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			SaturatedListener listener = new SaturatedListener(server, new ArrayList<>());
			try {
				listener.fillAcceptQueue();
			}
			catch (IOException | RuntimeException ex) {
				listener.close();
				throw ex;
			}
			return listener;
		}

		/** Connects until one handshake hangs, which proves the queue is full. */
		private void fillAcceptQueue() throws IOException {
			InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), port());
			for (int i = 0; i < MAX_QUEUED; i++) {
				Socket socket = new Socket();
				try {
					socket.connect(address, 100);
				}
				catch (SocketTimeoutException ex) {
					socket.close();
					return;
				}
				queued.add(socket);
			}
			throw new IllegalStateException("Accept queue never filled after " + MAX_QUEUED + " connections");
		}

		int port() {
			return server.getLocalPort();
		}

		@Override
		public void close() throws IOException {
			for (Socket socket : queued) {
				socket.close();
			}
			server.close();
		}

	}

}
