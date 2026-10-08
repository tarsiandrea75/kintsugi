package dev.kintsugi.agent.incident;

/** A {@code POST /incidents} body that breaks a rule; {@code field} is null when the body itself is not an object. */
class InvalidIncidentException extends RuntimeException {

	private final String field;

	InvalidIncidentException(String field, String rule) {
		super(rule, null, false, false);
		this.field = field;
	}

	String field() {
		return field;
	}

}
