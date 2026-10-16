package dev.kintsugi.mcp.observability;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only LogQL mcp-tools sends. Every query selects exactly one stream, {@code {service="<allowed service>"}}
 * (Alloy sets the {@code service} label to the compose service name), drops the lines of the lab's control plane
 * ({@link #controlPlaneFilter(String)}), and adds at most two line filters:
 * <ul>
 * <li>{@code contains}: a literal {@code |=} filter. The text is written in a double-quoted LogQL string with
 * {@code \} and {@code "} escaped, so it can never end the string; backticks need no escape there. Control characters
 * are rejected earlier, by validation.</li>
 * <li>{@code level}: a {@code |~} filter on the level column of Spring Boot's console line ({@code <timestamp>
 * <level> <pid> --- ...}). Loki has no level label for the lab streams. The level comes from an enum.</li>
 * </ul>
 */
public final class LogQueries {

	private static final Pattern LEVEL = Pattern.compile("^\\S+\\s+(ERROR|WARN|INFO|DEBUG|TRACE)\\s");

	/**
	 * The logger whose lines are the lab's control plane, per service. gateway-mock's {@code BehaviorStore} logs only
	 * the fault-injector's admin calls ("Gateway behavior changed", "Gateway behavior reset"), which say when and how
	 * F1 or F2 was injected: a real gateway has no such lines. Spring Boot's console line ends the logger column with
	 * the class name, then spaces and a colon.
	 */
	private static final Map<String, String> CONTROL_PLANE_LOGGERS = Map.of("gateway-mock", "BehaviorStore");

	private LogQueries() {
	}

	/** Lines of {@code service}, optionally filtered by literal text and level. Inputs must be validated. */
	public static String lines(String service, String contains, String level) {
		StringBuilder query = new StringBuilder("{service=\"").append(service).append("\"}");
		query.append(controlPlaneFilter(service));
		if (contains != null) {
			query.append(" |= \"").append(escape(contains)).append('"');
		}
		if (level != null) {
			query.append(" |~ `^\\S+\\s+").append(level).append("\\s`");
		}
		return query.toString();
	}

	/** The number of matching lines over the last {@code sinceMinutes}, as one instant value. */
	public static String count(String service, String contains, String level, int sinceMinutes) {
		return "sum(count_over_time(" + lines(service, contains, level) + " [" + sinceMinutes + "m]))";
	}

	/** The level of a Spring Boot console line, or {@code null} when the line has another shape. */
	public static String levelOf(String line) {
		Matcher matcher = LEVEL.matcher(line);
		return matcher.find() ? matcher.group(1) : null;
	}

	/** A {@code !~} filter that drops the control-plane lines of {@code service}, or nothing when it has none. */
	static String controlPlaneFilter(String service) {
		String logger = CONTROL_PLANE_LOGGERS.get(service);
		return logger == null ? "" : " !~ `\\." + logger + "\\s+:\\s`";
	}

	static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
