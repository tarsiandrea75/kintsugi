package dev.kintsugi.lab.order.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.kintsugi.lab.order.domain.OrderItem;
import io.micrometer.observation.ObservationRegistry;

/** The inventory client against a real HTTP server on loopback, with short timeouts so the failure cases stay fast. */
class InventoryClientTest {

	private static final UUID ORDER_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

	private static final List<OrderItem> ITEMS = List.of(new OrderItem("SKU-1", 2), new OrderItem("SKU-2", 1));

	private static final Duration TIMEOUT = Duration.ofMillis(200);

	private final FakeInventoryServer server = FakeInventoryServer.start();

	private final InventoryClient client = clientFor(server.baseUrl());

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void createdReservationIsReserved() {
		server.replyWith(201, "RESERVED");

		assertThat(client.reserve(ORDER_ID, ITEMS)).isEqualTo(ReservationOutcome.RESERVED);
	}

	@Test
	void conflictIsInsufficientStock() {
		server.replyWith(409, "INSUFFICIENT_STOCK");

		assertThat(client.reserve(ORDER_ID, ITEMS)).isEqualTo(ReservationOutcome.INSUFFICIENT_STOCK);
	}

	@Test
	void requestPostsTheOrderIdAndItemsAsJson() {
		client.reserve(ORDER_ID, ITEMS);

		FakeInventoryServer.RecordedRequest request = server.lastRequest();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/reservations");
		assertThat(request.contentType()).startsWith("application/json");
		assertThat(request.body().replace(" ", "")).contains("\"orderId\":\"" + ORDER_ID + "\"")
			.contains("\"items\":[{\"sku\":\"SKU-1\",\"quantity\":2},{\"sku\":\"SKU-2\",\"quantity\":1}]");
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 404, 500, 503 })
	void otherStatusIsUnavailable(int code) {
		server.replyWithBody(code, "{}");

		assertThatExceptionOfType(InventoryUnavailableException.class).isThrownBy(() -> client.reserve(ORDER_ID, ITEMS))
			.withMessageContaining(ORDER_ID.toString());
	}

	@Test
	void unknownReservationStatusIsUnavailable() {
		server.replyWith(201, "MAYBE");

		assertThatExceptionOfType(InventoryUnavailableException.class).isThrownBy(() -> client.reserve(ORDER_ID, ITEMS));
	}

	@Test
	void missingReservationStatusIsUnavailable() {
		server.replyWithBody(201, "{}");

		assertThatExceptionOfType(InventoryUnavailableException.class).isThrownBy(() -> client.reserve(ORDER_ID, ITEMS));
	}

	/** A status that contradicts the HTTP code is not a usable answer: the stock may or may not be reserved. */
	@Test
	void statusThatContradictsTheCodeIsUnavailable() {
		server.replyWith(201, "INSUFFICIENT_STOCK");
		assertThatExceptionOfType(InventoryUnavailableException.class).isThrownBy(() -> client.reserve(ORDER_ID, ITEMS));

		server.replyWith(409, "RESERVED");
		assertThatExceptionOfType(InventoryUnavailableException.class).isThrownBy(() -> client.reserve(ORDER_ID, ITEMS));
	}

	@Test
	void slowResponseTimesOutAfterTheReadTimeout() {
		server.replyAfter(Duration.ofSeconds(2));

		long start = System.nanoTime();
		assertThatExceptionOfType(InventoryUnavailableException.class).isThrownBy(() -> client.reserve(ORDER_ID, ITEMS))
			.withMessageContaining(ORDER_ID.toString())
			.withMessageContaining("HttpTimeoutException")
			.withMessageContaining(server.baseUrl() + "/reservations");
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(elapsed).isGreaterThanOrEqualTo(TIMEOUT).isLessThan(Duration.ofSeconds(2));
	}

	// The WARN line is the evidence, so it must name the I/O failure and the URL that was called. The JDK client's
	// ConnectException has no message, which Spring's default text would show as "null".
	@Test
	void refusedConnectionIsUnavailableAndNamesTheFailureAndTheUrl() throws IOException {
		// A bound but non-listening socket holds the port, so the kernel refuses connections and no one else can take it.
		try (Socket portHolder = new Socket()) {
			portHolder.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
			String baseUrl = "http://127.0.0.1:" + portHolder.getLocalPort();
			InventoryClient unreachable = clientFor(baseUrl);

			assertThatExceptionOfType(InventoryUnavailableException.class)
				.isThrownBy(() -> unreachable.reserve(ORDER_ID, ITEMS))
				.withMessageContaining(ORDER_ID.toString())
				.withMessageContaining("ConnectException")
				.withMessageContaining(baseUrl + "/reservations")
				.withMessageNotContaining("null");
		}
	}

	private static InventoryClient clientFor(String baseUrl) {
		return new InventoryClient(new InventoryProperties(URI.create(baseUrl), TIMEOUT, TIMEOUT),
				ObservationRegistry.NOOP);
	}

}
