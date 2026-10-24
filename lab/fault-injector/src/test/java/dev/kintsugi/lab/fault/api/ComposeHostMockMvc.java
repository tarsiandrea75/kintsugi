package dev.kintsugi.lab.fault.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Gives every MockMvc request the {@code Host} the scripts send, {@code 127.0.0.1:18084}, which the shipped allow-list
 * accepts: MockMvc sends no {@code Host} of its own, and {@link LoopbackGuardFilter} answers 421 without one. A request
 * that sets its own {@code Host} keeps it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ComposeHostMockMvc {

	static final String HOST = "127.0.0.1:18084";

	@Bean
	MockMvcBuilderCustomizer composeHost() {
		return builder -> builder.defaultRequest(get("/").header(HttpHeaders.HOST, HOST));
	}

}
