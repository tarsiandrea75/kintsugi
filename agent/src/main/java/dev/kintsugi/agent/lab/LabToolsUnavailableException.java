package dev.kintsugi.agent.lab;

/** mcp-tools could not list its tools, or does not serve a tool the phases need. */
public class LabToolsUnavailableException extends RuntimeException {

	public LabToolsUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}

}
