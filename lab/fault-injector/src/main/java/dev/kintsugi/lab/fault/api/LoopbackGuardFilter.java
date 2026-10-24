package dev.kintsugi.lab.fault.api;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects browser requests to the injector's API, the rule mcp-tools applies on {@code /mcp} (ADR-004, fault-injector
 * spec, "Browser requests are rejected"). The API is bound to loopback with no authentication; that stops other hosts,
 * not a page open in the human's own browser.
 *
 * <p>
 * Any {@code Origin} header answers 403: the API's callers are the verify scripts ({@code curl}) and the compose
 * healthcheck, which send none, so a request that carries one comes from a browser. That stops cross-site "simple"
 * requests, such as a body-less {@code POST /faults/{id}/restore}, which a browser sends without a preflight. A
 * {@code Host} outside the allow-list answers 421: DNS rebinding sends the attacker's own hostname, so this closes the
 * reads a rebound page could make. An entry matches the whole {@code host:port} value, with the host compared ignoring
 * case; an entry ending in {@code :*} accepts that host on any port, the form mcp-tools' allow-list also takes.
 */
public class LoopbackGuardFilter extends OncePerRequestFilter {

	private final List<String> allowedHosts;

	LoopbackGuardFilter(List<String> allowedHosts) {
		this.allowedHosts = List.copyOf(allowedHosts);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getHeader(HttpHeaders.ORIGIN) != null) {
			reject(response, HttpServletResponse.SC_FORBIDDEN, "Origin header not allowed");
		}
		else if (!allowed(request.getHeader(HttpHeaders.HOST))) {
			reject(response, 421, "Host header not allowed");
		}
		else {
			chain.doFilter(request, response);
		}
	}

	private boolean allowed(String host) {
		if (host == null || host.isBlank()) {
			return false;
		}
		for (String entry : this.allowedHosts) {
			if (entry.equalsIgnoreCase(host)) {
				return true;
			}
			if (entry.endsWith(":*")) {
				String base = entry.substring(0, entry.length() - 2);
				if (base.equalsIgnoreCase(host) || startsWithIgnoringCase(host, base + ":")) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean startsWithIgnoringCase(String value, String prefix) {
		return value.regionMatches(true, 0, prefix, 0, prefix.length());
	}

	/** A short plain-text answer; the request never reaches the controller or the error page. */
	private static void reject(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.TEXT_PLAIN_VALUE);
		response.getWriter().write(message);
	}

}
