package dev.kintsugi.mcp.tool;

/** A backend (Loki, Prometheus) failed, timed out or answered something unreadable. The message is a short reason. */
public class UpstreamException extends RuntimeException {

	public UpstreamException(String reason, Throwable cause) {
		super(reason, cause);
	}

	public UpstreamException(String reason) {
		super(reason);
	}

}
