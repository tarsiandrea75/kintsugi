package dev.kintsugi.lab.fault.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The Origin and Host checks on every request (fault-injector spec, "Browser requests are rejected"; ADR-004). */
class LoopbackGuardFilterTest {

	/** The default of {@code kintsugi.fault-injector.allowed-hosts} in {@code application.properties}. */
	private static final List<String> COMPOSE_HOSTS = List.of("fault-injector:8084", "localhost:8084",
			"localhost:18084", "127.0.0.1:18084");

	private final LoopbackGuardFilter filter = new LoopbackGuardFilter(COMPOSE_HOSTS);

	@ParameterizedTest
	@ValueSource(strings = { "http://localhost:18084", "https://evil.example", "null", "" })
	void anyOriginIsRejectedWith403EvenWithAnAllowedHost(String origin) throws Exception {
		MockHttpServletRequest request = request("localhost:18084");
		request.addHeader("Origin", origin);

		Outcome outcome = run(filter, request);

		assertThat(outcome.status()).isEqualTo(403);
		assertThat(outcome.handled()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "fault-injector:8084", "localhost:8084", "localhost:18084", "127.0.0.1:18084",
			"LocalHost:18084", "Fault-Injector:8084" })
	void theComposeHostsReachTheHandler(String host) throws Exception {
		Outcome outcome = run(filter, request(host));

		assertThat(outcome.status()).isEqualTo(200);
		assertThat(outcome.handled()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "evil.example", "evil.example:18084", "localhost", "localhost:18086",
			"fault-injector:18084", "127.0.0.1:8084", "localhost:18084.evil.example", "" })
	void anyOtherHostIsRejectedWith421(String host) throws Exception {
		Outcome outcome = run(filter, request(host));

		assertThat(outcome.status()).isEqualTo(421);
		assertThat(outcome.handled()).isFalse();
	}

	@Test
	void aMissingHostIsRejectedWith421() throws Exception {
		Outcome outcome = run(filter, request(null));

		assertThat(outcome.status()).isEqualTo(421);
		assertThat(outcome.handled()).isFalse();
	}

	/** The form mcp-tools' tests use: an entry ending in {@code :*} accepts that host on any port, or with none. */
	@ParameterizedTest
	@ValueSource(strings = { "localhost:54321", "localhost", "LOCALHOST:1" })
	void anEntryEndingInAStarAcceptsAnyPort(String host) throws Exception {
		Outcome outcome = run(new LoopbackGuardFilter(List.of("localhost:*")), request(host));

		assertThat(outcome.handled()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1:54321", "localhost.evil.example:54321", "localhostx:1" })
	void anEntryEndingInAStarKeepsItsHost(String host) throws Exception {
		Outcome outcome = run(new LoopbackGuardFilter(List.of("localhost:*")), request(host));

		assertThat(outcome.status()).isEqualTo(421);
	}

	private static MockHttpServletRequest request(String host) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/faults");
		if (host != null) {
			request.addHeader("Host", host);
		}
		return request;
	}

	private static Outcome run(LoopbackGuardFilter filter, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return new Outcome(response.getStatus(), chain.getRequest() != null);
	}

	private record Outcome(int status, boolean handled) {
	}

}
