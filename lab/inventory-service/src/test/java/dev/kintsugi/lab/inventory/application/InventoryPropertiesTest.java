package dev.kintsugi.lab.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InventoryPropertiesTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void delayIsZeroByDefault() {
		runner.run(context -> assertThat(context.getBean(InventoryProperties.class).reservationDelay())
			.isEqualTo(Duration.ZERO));
	}

	@Test
	void delayTakesItsPropertyValue() {
		runner.withPropertyValues("kintsugi.inventory.reservation-delay=5s")
			.run(context -> assertThat(context.getBean(InventoryProperties.class).reservationDelay())
				.isEqualTo(Duration.ofSeconds(5)));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(InventoryProperties.class)
	static class PropertiesConfiguration {

	}

}
