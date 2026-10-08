package dev.kintsugi.mcp.tool;

/** A tool parameter is missing or outside its domain; thrown before any backend is called. */
public class InvalidParameterException extends RuntimeException {

	private final String parameter;

	public InvalidParameterException(String parameter, String message) {
		super(parameter + " " + message);
		this.parameter = parameter;
	}

	public String parameter() {
		return this.parameter;
	}

}
