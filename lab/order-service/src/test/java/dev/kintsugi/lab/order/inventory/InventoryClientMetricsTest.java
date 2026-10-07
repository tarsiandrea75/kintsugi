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
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.kintsugi.lab.order.domain.OrderItem;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * The inventory client records {@code http.client.requests} tagged {@code client.name=inventory-service}. Under fault
 * F4 the evidence on order-service is these calls ending in {@code status="500"}; under F8 it is read timeouts.
 */
class InventoryClientMetricsTest {

	private static final UUID ORDER_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

	private static final List<OrderItem> ITEMS = List.of(new OrderItem("SKU-1", 1));

	private static final Duration TIMEOUT = Duration.ofMillis(200);

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final ObservationRegistry observations = observationRegistryFor(meters);

	private final FakeInventoryServer server = FakeInventoryServer.start();

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void reservedCallIsRecordedWithItsStatus() {
		server.replyWith(201, "RESERVED");

		clientFor(server.baseUrl()).reserve(ORDER_ID, ITEMS);

		assertThat(onlyTimerTags()).containsEntry("client.name", "inventory-service")
			.containsEntry("method", "POST")
			.containsEntry("uri", "/reservations")
			.containsEntry("status", "201")
			.containsEntry("outcome", "SUCCESS")
			.containsEntry("exception", "none");
	}

	@Test
	void insufficientStockIsRecordedAsAClientError() {
		server.replyWith(409, "INSUFFICIENT_STOCK");

		clientFor(server.baseUrl()).reserve(ORDER_ID, ITEMS);

		assertThat(onlyTimerTags()).containsEntry("client.name", "inventory-service")
			.containsEntry("status", "409")
			.containsEntry("outcome", "CLIENT_ERROR");
	}

	// Fault F4: inventory-service answers 500 because its connection pool is exhausted.
	@Test
	void serverErrorIsRecordedWithItsStatus() {
		server.replyWithBody(500, "{}");

		assertThatExceptionOfType(InventoryUnavailableException.class)
			.isThrownBy(() -> clientFor(server.baseUrl()).reserve(ORDER_ID, ITEMS));

		assertThat(onlyTimerTags()).containsEntry("client.name", "inventory-service")
			.containsEntry("status", "500")
			.containsEntry("outcome", "SERVER_ERROR");
	}

	@Test
	void refusedConnectionIsRecordedAsConnectException() throws IOException {
		try (Socket portHolder = new Socket()) {
			portHolder.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
			InventoryClient unreachable = clientFor("http://127.0.0.1:" + portHolder.getLocalPort());

			assertThatExceptionOfType(InventoryUnavailableException.class)
				.isThrownBy(() -> unreachable.reserve(ORDER_ID, ITEMS));
		}

		assertThat(onlyTimerTags()).containsEntry("client.name", "inventory-service")
			.containsEntry("uri", "/reservations")
			.containsEntry("status", "CLIENT_ERROR")
			.containsEntry("outcome", "UNKNOWN")
			.containsEntry("exception", "ConnectException")
			.containsEntry("error", "ResourceAccessException");
	}

	// Fault F8 (later): inventory-service answers too slowly, so the read timeout ends the call.
	@Test
	void readTimeoutIsRecordedAsTimeout() {
		server.replyAfter(Duration.ofSeconds(2));

		assertThatExceptionOfType(InventoryUnavailableException.class)
			.isThrownBy(() -> clientFor(server.baseUrl()).reserve(ORDER_ID, ITEMS));

		assertThat(onlyTimerTags()).containsEntry("client.name", "inventory-service")
			.containsEntry("status", "CLIENT_ERROR")
			.containsEntry("outcome", "UNKNOWN")
			.containsEntry("exception", "HttpTimeoutException")
			.containsEntry("error", "ResourceAccessException");
	}

	private InventoryClient clientFor(String baseUrl) {
		return new InventoryClient(new InventoryProperties(URI.create(baseUrl), TIMEOUT, TIMEOUT), observations);
	}

	/** The tags of the single {@code http.client.requests} timer, which must have counted exactly one call. */
	private Map<String, String> onlyTimerTags() {
		Timer timer = meters.get("http.client.requests").timer();
		assertThat(timer.count()).isEqualTo(1);
		Meter.Id id = timer.getId();
		Map<String, String> tags = new TreeMap<>();
		for (Tag tag : id.getTags()) {
			tags.put(tag.getKey(), tag.getValue());
		}
		return tags;
	}

	private static ObservationRegistry observationRegistryFor(SimpleMeterRegistry meters) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
		return registry;
	}

}
