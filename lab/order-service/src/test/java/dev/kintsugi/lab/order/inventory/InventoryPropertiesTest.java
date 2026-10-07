package dev.kintsugi.lab.order.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The {@code kintsugi.inventory.*} properties: the fault injector changes them, so their names and defaults matter. */
class InventoryPropertiesTest {

	@Test
	void defaultsPointToALocalInventoryService() {
		InventoryProperties properties = bind(Map.of());

		assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:8083"));
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
	}

	@Test
	void everyPropertyCanBeOverridden() {
		InventoryProperties properties = bind(Map.of("kintsugi.inventory.base-url", "http://inventory:9000",
				"kintsugi.inventory.connect-timeout", "250ms", "kintsugi.inventory.read-timeout", "10s"));

		assertThat(properties.baseUrl()).isEqualTo(URI.create("http://inventory:9000"));
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(250));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
	}

	private static InventoryProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("kintsugi.inventory",
				InventoryProperties.class);
	}

}
