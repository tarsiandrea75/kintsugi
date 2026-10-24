package dev.kintsugi.lab.fault.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/** The allow-list's binding: a shipped default, no way to empty it, and the filter first on every path. */
class LoopbackGuardConfigurationTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withUserConfiguration(LoopbackGuardConfiguration.class);

	@Test
	void theShippedDefaultListsTheComposeNameTheContainerPortAndTheHostPort() throws Exception {
		Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

		assertThat(properties.getProperty("kintsugi.fault-injector.allowed-hosts"))
			.isEqualTo("fault-injector:8084,localhost:8084,localhost:18084,127.0.0.1:18084");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " , " })
	void anEmptyAllowListFailsTheStartup(String hosts) {
		runner.withPropertyValues("kintsugi.fault-injector.allowed-hosts=" + hosts).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("kintsugi.fault-injector.allowed-hosts");
		});
	}

	@Test
	void aMissingAllowListFailsTheStartup() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("kintsugi.fault-injector.allowed-hosts");
		});
	}

	@Test
	void theFilterRunsFirstOnEveryPath() {
		runner.withPropertyValues("kintsugi.fault-injector.allowed-hosts=fault-injector:8084").run(context -> {
			assertThat(context).hasNotFailed();
			FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
			assertThat(registration.getFilter()).isInstanceOf(LoopbackGuardFilter.class);
			assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
			assertThat(registration.getUrlPatterns()).isEmpty();
		});
	}

}
