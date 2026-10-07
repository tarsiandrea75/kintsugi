package dev.kintsugi.lab.order.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * The payment client records {@code http.client.requests} through the observation registry it is given. The tags are
 * the evidence that tells fault F3 (connection refused) apart from fault F1 (read timeout) in Prometheus.
 */
class PaymentClientMetricsTest {

	private static final UUID ORDER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

	private static final Duration TIMEOUT = Duration.ofMillis(200);

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final ObservationRegistry observations = observationRegistryFor(meters);

	private final FakePaymentServer server = FakePaymentServer.start();

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void successfulCallIsRecordedWithItsStatus() {
		server.replyWith(201, "AUTHORIZED");

		clientFor(server.baseUrl()).pay(ORDER_ID);

		assertThat(onlyTimerTags()).containsEntry("client.name", "payment-service")
			.containsEntry("method", "POST")
			.containsEntry("uri", "/payments")
			.containsEntry("status", "201")
			.containsEntry("outcome", "SUCCESS")
			.containsEntry("exception", "none");
	}

	@Test
	void serverErrorIsRecordedWithItsStatus() {
		server.replyWithBody(503, "{}");

		assertThatExceptionOfType(PaymentUnavailableException.class)
			.isThrownBy(() -> clientFor(server.baseUrl()).pay(ORDER_ID));

		assertThat(onlyTimerTags()).containsEntry("status", "503").containsEntry("outcome", "SERVER_ERROR");
	}

	// Fault F3: a wrong payment URL points at a port where nothing listens.
	@Test
	void refusedConnectionIsRecordedAsConnectException() throws IOException {
		try (Socket portHolder = new Socket()) {
			portHolder.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
			PaymentClient unreachable = clientFor("http://127.0.0.1:" + portHolder.getLocalPort());

			assertThatExceptionOfType(PaymentUnavailableException.class).isThrownBy(() -> unreachable.pay(ORDER_ID));
		}

		assertThat(onlyTimerTags()).containsEntry("client.name", "payment-service")
			.containsEntry("uri", "/payments")
			.containsEntry("status", "CLIENT_ERROR")
			.containsEntry("outcome", "UNKNOWN")
			.containsEntry("exception", "ConnectException")
			.containsEntry("error", "ResourceAccessException");
	}

	// Fault F1: payment-service answers too slowly, so the read timeout ends the call.
	@Test
	void readTimeoutIsRecordedAsTimeout() {
		server.replyAfter(Duration.ofSeconds(2));

		assertThatExceptionOfType(PaymentUnavailableException.class)
			.isThrownBy(() -> clientFor(server.baseUrl()).pay(ORDER_ID));

		assertThat(onlyTimerTags()).containsEntry("client.name", "payment-service")
			.containsEntry("status", "CLIENT_ERROR")
			.containsEntry("outcome", "UNKNOWN")
			.containsEntry("exception", "HttpTimeoutException")
			.containsEntry("error", "ResourceAccessException");
	}

	private PaymentClient clientFor(String baseUrl) {
		return new PaymentClient(new PaymentProperties(URI.create(baseUrl), TIMEOUT, TIMEOUT), observations);
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
