package dev.kintsugi.lab.payment.gateway;

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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

/** The HTTP gateway client against a real HTTP server on loopback, with short timeouts so failures stay fast. */
@ExtendWith(OutputCaptureExtension.class)
class HttpPaymentGatewayTest {

	// Clients connect to the IPv4 literal 127.0.0.1, so fixtures must bind the same address family.
	private static final InetAddress IPV4_LOOPBACK = ipv4Loopback();

	private static final UUID ORDER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

	private static final Duration TIMEOUT = Duration.ofMillis(200);

	private final FakeGatewayServer server = FakeGatewayServer.start();

	private final HttpPaymentGateway gateway = gatewayFor(server.baseUrl());

	@AfterEach
	void stopServer() {
		server.close();
	}

	@ParameterizedTest
	@EnumSource(names = { "AUTHORIZED", "DECLINED" })
	void outcomeIsTheGatewayStatus(PaymentStatus status) {
		server.replyWith(200, status.name());

		assertThat(gateway.authorize(ORDER_ID)).isEqualTo(status);
	}

	@Test
	void requestPostsTheOrderIdAsJson() {
		gateway.authorize(ORDER_ID);

		FakeGatewayServer.RecordedRequest request = server.lastRequest();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/authorize");
		assertThat(request.contentType()).startsWith("application/json");
		assertThat(request.body()).contains("\"orderId\"").contains(ORDER_ID.toString());
	}

	@Test
	void rateLimitedResponseIsAFailedPayment(CapturedOutput output) {
		server.replyWithBody(429, "{\"error\": \"rate limited\"}");

		assertThat(gateway.authorize(ORDER_ID)).isEqualTo(PaymentStatus.FAILED);
		assertThat(output.getOut()).containsSubsequence("WARN", ORDER_ID.toString(), "429");
	}

	// FAILED is how this service reads a 429; a gateway that answers it in a 200 body sent an unknown status.
	@Test
	void failedStatusInASuccessBodyIsUnavailable() {
		server.replyWith(200, "FAILED");

		assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> gateway.authorize(ORDER_ID));
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 500, 502, 503 })
	void nonSuccessResponseIsUnavailable(int code) {
		server.replyWithBody(code, "{}");

		assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> gateway.authorize(ORDER_ID))
			.withMessageContaining(ORDER_ID.toString());
	}

	@Test
	void unknownStatusIsUnavailable() {
		server.replyWith(200, "PENDING");

		assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> gateway.authorize(ORDER_ID));
	}

	@Test
	void missingStatusIsUnavailable() {
		server.replyWithBody(200, "{}");

		assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> gateway.authorize(ORDER_ID));
	}

	@Test
	void slowResponseTimesOutAfterTheReadTimeout() {
		server.replyAfter(Duration.ofSeconds(2));

		long start = System.nanoTime();
		assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> gateway.authorize(ORDER_ID))
			.withMessageContaining(ORDER_ID.toString());
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT).isLessThan(Duration.ofSeconds(2));
	}

	@Test
	void unreachableGatewayIsUnavailable() throws IOException {
		// A bound but non-listening socket holds the port, so the kernel refuses connections and no one else can take it.
		try (Socket portHolder = new Socket()) {
			portHolder.bind(new InetSocketAddress(IPV4_LOOPBACK, 0));
			HttpPaymentGateway unreachable = gatewayFor("http://127.0.0.1:" + portHolder.getLocalPort());

			assertThatExceptionOfType(GatewayUnavailableException.class)
				.isThrownBy(() -> unreachable.authorize(ORDER_ID))
				.withMessageContaining(ORDER_ID.toString());
		}
	}

	// Relies on Linux dropping SYNs when the accept queue is full; other OSes may refuse instead.
	@Test
	@EnabledOnOs(OS.LINUX)
	void hangingConnectTimesOutAfterTheConnectTimeout() throws IOException {
		// The read timeout is long, so only the connect timeout can end the call quickly. The JDK client reports both as
		// HttpConnectTimeoutException while connecting, so the elapsed time is what proves the connect timeout applies.
		try (SaturatedListener listener = SaturatedListener.open()) {
			HttpPaymentGateway hanging = new HttpPaymentGateway(new GatewayProperties(
					URI.create("http://127.0.0.1:" + listener.port()), TIMEOUT, Duration.ofSeconds(10)));

			long start = System.nanoTime();
			assertThatExceptionOfType(GatewayUnavailableException.class).isThrownBy(() -> hanging.authorize(ORDER_ID))
				.withMessageContaining(ORDER_ID.toString())
				.satisfies(ex -> assertThat(causesOf(ex)).hasAtLeastOneElementOfType(HttpConnectTimeoutException.class));
			Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

			assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT).isLessThan(Duration.ofSeconds(3));
		}
	}

	private static HttpPaymentGateway gatewayFor(String baseUrl) {
		return new HttpPaymentGateway(new GatewayProperties(URI.create(baseUrl), TIMEOUT, TIMEOUT));
	}

	private static InetAddress ipv4Loopback() {
		try {
			return InetAddress.getByName("127.0.0.1");
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
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
			ServerSocket server = new ServerSocket(0, 1, IPV4_LOOPBACK);
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
			InetSocketAddress address = new InetSocketAddress(IPV4_LOOPBACK, port());
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
