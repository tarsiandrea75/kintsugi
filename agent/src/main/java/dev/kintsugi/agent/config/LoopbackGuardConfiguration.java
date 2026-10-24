package dev.kintsugi.agent.config;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

/**
 * Puts {@link LoopbackGuardFilter} in front of every path, actuator included, before any other filter. Only with a
 * servlet web server: the demo profile has none, so it needs no allow-list.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LoopbackGuardConfiguration {

	/**
	 * The {@code Host} values the API accepts, comma-separated; any other answers 421. The default in
	 * {@code application.yaml} lists every in-repo caller: {@code agent:8086} on the compose network,
	 * {@code localhost:8086} for the compose healthcheck inside the container, {@code localhost:18086} and
	 * {@code 127.0.0.1:18086} for the host. There is no default in code, and an empty list stops the startup, so the
	 * check cannot be switched off by accident (ADR-004).
	 */
	static final String ALLOWED_HOSTS_PROPERTY = "kintsugi.agent.allowed-hosts";

	@Bean
	FilterRegistrationBean<LoopbackGuardFilter> loopbackGuardFilter(Environment environment) {
		FilterRegistrationBean<LoopbackGuardFilter> registration = new FilterRegistrationBean<>(
				new LoopbackGuardFilter(allowedHosts(environment)));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	static List<String> allowedHosts(Environment environment) {
		List<String> hosts = Binder.get(environment)
			.bind(ALLOWED_HOSTS_PROPERTY, Bindable.listOf(String.class))
			.orElse(List.of())
			.stream()
			.map(String::strip)
			.filter(host -> !host.isEmpty())
			.toList();
		if (hosts.isEmpty()) {
			throw new IllegalStateException(ALLOWED_HOSTS_PROPERTY
					+ " must list the Host values the incident API accepts; an empty list would let any Host through"
					+ " (ADR-004)");
		}
		return hosts;
	}

}
