package dev.kintsugi.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;

/** The allow-list's binding: a shipped default, no way to empty it, and the filter first on every path. */
class LoopbackGuardConfigurationTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
		.withUserConfiguration(LoopbackGuardConfiguration.class);

	@Test
	void theShippedDefaultListsTheComposeNameTheContainerPortAndTheHostPort() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application.yaml"));
		Properties properties = yaml.getObject();

		assertThat(properties.getProperty("kintsugi.agent.allowed-hosts"))
			.isEqualTo("agent:8086,localhost:8086,localhost:18086,127.0.0.1:18086");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " , " })
	void anEmptyAllowListFailsTheStartup(String hosts) {
		this.runner.withPropertyValues("kintsugi.agent.allowed-hosts=" + hosts).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("kintsugi.agent.allowed-hosts");
		});
	}

	@Test
	void aMissingAllowListFailsTheStartup() {
		this.runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("kintsugi.agent.allowed-hosts");
		});
	}

	@Test
	void theFilterRunsFirstOnEveryPath() {
		this.runner.withPropertyValues("kintsugi.agent.allowed-hosts=agent:8086").run(context -> {
			assertThat(context).hasNotFailed();
			FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
			assertThat(registration.getFilter()).isInstanceOf(LoopbackGuardFilter.class);
			assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
			assertThat(registration.getUrlPatterns()).isEmpty();
		});
	}

	/** The demo profile runs without a web server, so it needs no allow-list. */
	@Test
	void anApplicationWithoutAWebServerHasNoFilterAndNeedsNoList() {
		new ApplicationContextRunner().withUserConfiguration(LoopbackGuardConfiguration.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
		});
	}

}
