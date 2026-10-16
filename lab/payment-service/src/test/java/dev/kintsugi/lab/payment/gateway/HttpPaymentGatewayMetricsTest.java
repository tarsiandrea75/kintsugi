package dev.kintsugi.lab.payment.gateway;

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

import dev.kintsugi.lab.payment.domain.PaymentStatus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

/**
 * The gateway client records {@code http.client.requests} through the observation registry it is given, so the
 * payment-service to gateway-mock edge has a caller-side series next to gateway-mock's server-side one.
 */
class HttpPaymentGatewayMetricsTest {

	private static final UUID ORDER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

	private static final Duration TIMEOUT = Duration.ofMillis(200);

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final ObservationRegistry observations = observationRegistryFor(meters);

	private final FakeGatewayServer server = FakeGatewayServer.start();

	@AfterEach
	void stopServer() {
		server.close();
	}

	@Test
	void successfulCallIsRecordedWithItsStatus() {
		server.replyWith(200, "AUTHORIZED");

		assertThat(gatewayFor(server.baseUrl()).authorize(ORDER_ID)).isEqualTo(PaymentStatus.AUTHORIZED);

		assertThat(onlyTimerTags()).containsEntry("client.name", "gateway-mock")
			.containsEntry("method", "POST")
			.containsEntry("uri", "/authorize")
			.containsEntry("status", "200")
			.containsEntry("outcome", "SUCCESS")
			.containsEntry("exception", "none");
	}

	// Fault F2: the gateway rejects with 429, which this service stores as FAILED.
	@Test
	void rateLimitedCallIsRecordedWithItsStatus() {
		server.replyWithBody(429, "{\"error\": \"rate limited\"}");

		assertThat(gatewayFor(server.baseUrl()).authorize(ORDER_ID)).isEqualTo(PaymentStatus.FAILED);

		assertThat(onlyTimerTags()).containsEntry("uri", "/authorize")
			.containsEntry("status", "429")
			.containsEntry("outcome", "CLIENT_ERROR");
	}

	@Test
	void refusedConnectionIsRecordedAsConnectException() throws IOException {
		try (Socket portHolder = new Socket()) {
			portHolder.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
			HttpPaymentGateway unreachable = gatewayFor("http://127.0.0.1:" + portHolder.getLocalPort());

			assertThatExceptionOfType(GatewayUnavailableException.class)
				.isThrownBy(() -> unreachable.authorize(ORDER_ID));
		}

		assertThat(onlyTimerTags()).containsEntry("client.name", "gateway-mock")
			.containsEntry("uri", "/authorize")
			.containsEntry("status", "CLIENT_ERROR")
			.containsEntry("outcome", "UNKNOWN")
			.containsEntry("exception", "ConnectException");
	}

	// Fault F1: the gateway answers too slowly, so the read timeout ends the call.
	@Test
	void readTimeoutIsRecordedAsTimeout() {
		server.replyAfter(Duration.ofSeconds(2));

		assertThatExceptionOfType(GatewayUnavailableException.class)
			.isThrownBy(() -> gatewayFor(server.baseUrl()).authorize(ORDER_ID));

		assertThat(onlyTimerTags()).containsEntry("client.name", "gateway-mock")
			.containsEntry("status", "CLIENT_ERROR")
			.containsEntry("outcome", "UNKNOWN")
			.containsEntry("exception", "HttpTimeoutException");
	}

	private HttpPaymentGateway gatewayFor(String baseUrl) {
		return new HttpPaymentGateway(new GatewayProperties(URI.create(baseUrl), TIMEOUT, TIMEOUT), observations);
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
