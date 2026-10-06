package dev.kintsugi.lab.order.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The {@code kintsugi.payment.*} properties: the fault injector changes them, so their names and defaults matter. */
class PaymentPropertiesTest {

	@Test
	void defaultsPointToALocalPaymentService() {
		PaymentProperties properties = bind(Map.of());

		assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:8081"));
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
	}

	@Test
	void everyPropertyCanBeOverridden() {
		PaymentProperties properties = bind(Map.of("kintsugi.payment.base-url", "http://payment:9000",
				"kintsugi.payment.connect-timeout", "250ms", "kintsugi.payment.read-timeout", "10s"));

		assertThat(properties.baseUrl()).isEqualTo(URI.create("http://payment:9000"));
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(250));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
	}

	private static PaymentProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("kintsugi.payment",
				PaymentProperties.class);
	}

}
