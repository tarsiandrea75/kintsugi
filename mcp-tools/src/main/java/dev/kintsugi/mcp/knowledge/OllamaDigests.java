package dev.kintsugi.mcp.knowledge;

import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.kintsugi.mcp.tool.UpstreamException;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.web.client.ResourceAccessException;

/**
 * The digest Ollama reports now for a model ({@code GET /api/tags}), cached for at most {@link #TTL} (mcp-tools spec,
 * "Runbook search refuses a model mismatch"). The cache keeps {@code /api/tags} off the path of most searches, and
 * its bound means a model re-pulled under the same name is refused within a minute. Only a found digest is cached:
 * a failure or an unlisted model is asked again on the next search.
 */
public class OllamaDigests {

	static final Duration TTL = Duration.ofSeconds(60);

	private final OllamaApi ollama;

	private final Clock clock;

	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public OllamaDigests(OllamaApi ollama, Clock clock) {
		this.ollama = ollama;
		this.clock = clock;
	}

	private record Cached(String digest, Instant readAt) {
	}

	/** The digest of {@code model}; a name without a tag means {@code :latest}, as in Ollama itself. */
	String digest(String model) {
		Instant now = this.clock.instant();
		Cached cached = this.cache.get(model);
		if (cached != null && now.isBefore(cached.readAt().plus(TTL))) {
			return cached.digest();
		}
		String tagged = model.contains(":") ? model : model + ":latest";
		OllamaApi.ListModelResponse listed;
		try {
			listed = this.ollama.listModels();
		}
		catch (RuntimeException ex) {
			throw failure(ex);
		}
		if (listed == null || listed.models() == null) {
			throw new UpstreamException("Ollama answered without a model list");
		}
		String digest = listed.models()
			.stream()
			.filter(listedModel -> tagged.equals(listedModel.name()))
			.map(OllamaApi.Model::digest)
			.findFirst()
			.orElseThrow(() -> new UpstreamException("model " + model + " is not listed by Ollama"));
		this.cache.put(model, new Cached(digest, now));
		return digest;
	}

	/**
	 * A short reason for any failed Ollama call: never the URL or the answer's body. Three transport cases are told
	 * apart, like the other backends':
	 * <ul>
	 * <li>a read past the timeout ({@code spring.http.clients.read-timeout}): "did not answer in time";</li>
	 * <li>a connect past the timeout ({@code spring.http.clients.connect-timeout}): the JDK client throws
	 * {@link HttpConnectTimeoutException}, and a socket-based client a {@link SocketTimeoutException} whose message
	 * names the connect ("Connect timed out"): "did not accept a connection in time";</li>
	 * <li>a refused connection or an unknown host: "unreachable".</li>
	 * </ul>
	 */
	static UpstreamException failure(RuntimeException ex) {
		boolean transport = false;
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (isConnectTimeout(cause)) {
				return new UpstreamException("Ollama did not accept a connection in time", ex);
			}
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
				return new UpstreamException("Ollama did not answer in time", ex);
			}
			transport |= cause instanceof ResourceAccessException;
		}
		return new UpstreamException(transport ? "Ollama is unreachable" : "Ollama answered an error", ex);
	}

	private static boolean isConnectTimeout(Throwable cause) {
		if (cause instanceof HttpConnectTimeoutException) {
			return true;
		}
		String message = cause.getMessage();
		return cause instanceof SocketTimeoutException && message != null
				&& message.toLowerCase(Locale.ROOT).contains("connect");
	}

}
