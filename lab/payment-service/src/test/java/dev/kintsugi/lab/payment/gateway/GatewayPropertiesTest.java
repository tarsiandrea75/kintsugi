package dev.kintsugi.lab.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The {@code kintsugi.gateway.*} properties: the fault injector changes them, so their names and defaults matter. */
class GatewayPropertiesTest {

	@Test
	void defaultsPointToALocalGatewayMock() {
		GatewayProperties properties = bind(Map.of());

		assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:8082"));
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(15));
	}

	@Test
	void everyPropertyCanBeOverridden() {
		GatewayProperties properties = bind(Map.of("kintsugi.gateway.base-url", "http://gateway:9000",
				"kintsugi.gateway.connect-timeout", "250ms", "kintsugi.gateway.read-timeout", "5s"));

		assertThat(properties.baseUrl()).isEqualTo(URI.create("http://gateway:9000"));
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(250));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

	private static GatewayProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("kintsugi.gateway",
				GatewayProperties.class);
	}

}
