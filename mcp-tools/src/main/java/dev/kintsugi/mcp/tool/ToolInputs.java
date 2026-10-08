package dev.kintsugi.mcp.tool;

import java.util.List;
import java.util.Locale;

/**
 * Validation of the shared tool parameters (mcp-tools spec, "Shared rules" and "Parameter domains"). Every method
 * returns the validated value or throws {@link InvalidParameterException} naming the parameter and its domain.
 */
public final class ToolInputs {

	/** The only services whose logs and metrics a tool may read. Never {@code fault-injector} or {@code mcp-tools}. */
	public static final List<String> SERVICES = List.of("order-service", "payment-service", "inventory-service",
			"gateway-mock");

	public static final List<String> LEVELS = List.of("ERROR", "WARN", "INFO", "DEBUG");

	public static final int MAX_SINCE_MINUTES = 360;

	public static final int DEFAULT_LIMIT = 50;

	public static final int MAX_LIMIT = 100;

	public static final int MAX_CONTAINS_LENGTH = 200;

	private ToolInputs() {
	}

	public static String service(String value) {
		if (value == null || !SERVICES.contains(value)) {
			throw new InvalidParameterException("service", "must be one of " + SERVICES);
		}
		return value;
	}

	/** An optional service: {@code null} means every allowed service. */
	public static String optionalService(String value) {
		return value == null ? null : service(value);
	}

	public static int sinceMinutes(Integer value) {
		if (value == null || value < 1 || value > MAX_SINCE_MINUTES) {
			throw new InvalidParameterException("sinceMinutes", "must be an integer from 1 to " + MAX_SINCE_MINUTES);
		}
		return value;
	}

	public static int limit(Integer value) {
		if (value == null) {
			return DEFAULT_LIMIT;
		}
		if (value < 1 || value > MAX_LIMIT) {
			throw new InvalidParameterException("limit", "must be an integer from 1 to " + MAX_LIMIT
					+ " (maximum " + MAX_LIMIT + ", default " + DEFAULT_LIMIT + ")");
		}
		return value;
	}

	/** An optional log level, accepted in any case and returned upper case. */
	public static String level(String value) {
		if (value == null) {
			return null;
		}
		String upper = value.toUpperCase(Locale.ROOT);
		if (!LEVELS.contains(upper)) {
			throw new InvalidParameterException("level", "must be one of " + LEVELS);
		}
		return upper;
	}

	/** Optional plain text to match literally: 1 to 200 characters, no control characters. */
	public static String contains(String value) {
		if (value == null) {
			return null;
		}
		if (value.isEmpty() || value.length() > MAX_CONTAINS_LENGTH) {
			throw new InvalidParameterException("contains",
					"must be plain text of 1 to " + MAX_CONTAINS_LENGTH + " characters");
		}
		if (value.chars().anyMatch(Character::isISOControl)) {
			throw new InvalidParameterException("contains", "must not contain control characters such as newlines");
		}
		return value;
	}

}
