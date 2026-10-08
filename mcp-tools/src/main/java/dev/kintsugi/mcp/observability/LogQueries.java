package dev.kintsugi.mcp.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only LogQL mcp-tools sends. Every query selects exactly one stream, {@code {service="<allowed service>"}}
 * (Alloy sets the {@code service} label to the compose service name), and adds at most two line filters:
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

	private LogQueries() {
	}

	/** Lines of {@code service}, optionally filtered by literal text and level. Inputs must be validated. */
	public static String lines(String service, String contains, String level) {
		StringBuilder query = new StringBuilder("{service=\"").append(service).append("\"}");
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

	static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
