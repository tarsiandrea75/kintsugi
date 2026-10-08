package dev.kintsugi.mcp.knowledge;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The short reason {@link OllamaDigests#failure} gives for each kind of failed Ollama call, as the HTTP client wraps
 * it: a read timeout, a connect timeout, a refused or unknown host, and an HTTP error answer.
 */
class OllamaFailureReasonTest {

	private static String reason(Throwable cause) {
		return OllamaDigests.failure(new ResourceAccessException("I/O error", new IOException(cause)))
			.getMessage();
	}

	@Test
	void aReadTimeoutDidNotAnswerInTime() {
		assertThat(reason(new HttpTimeoutException("request timed out"))).isEqualTo("Ollama did not answer in time");
		assertThat(reason(new SocketTimeoutException("Read timed out"))).isEqualTo("Ollama did not answer in time");
	}

	@Test
	void aConnectTimeoutIsToldApartFromASlowAnswer() {
		assertThat(reason(new HttpConnectTimeoutException("HTTP connect timed out")))
			.isEqualTo("Ollama did not accept a connection in time");
		assertThat(reason(new SocketTimeoutException("Connect timed out")))
			.isEqualTo("Ollama did not accept a connection in time");
	}

	@Test
	void aRefusedConnectionOrAnUnknownHostIsUnreachable() {
		assertThat(reason(new ConnectException("Connection refused"))).isEqualTo("Ollama is unreachable");
		assertThat(reason(new UnknownHostException("ollama"))).isEqualTo("Ollama is unreachable");
	}

	@Test
	void anHttpErrorIsAnErrorAnswer() {
		assertThat(OllamaDigests.failure(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)).getMessage())
			.isEqualTo("Ollama answered an error");
	}

}
