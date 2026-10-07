package dev.kintsugi.lab.inventory.application;

/**
 * Quotes values for {@code key=value} log lines (logfmt), so Loki's {@code | logfmt} parser reads them back even when
 * they contain spaces or quotes.
 */
public final class LogFmt {

	private LogFmt() {
	}

	/** {@code value} in double quotes, with backslashes, quotes and line breaks escaped; {@code ""} for null. */
	public static String quote(String value) {
		if (value == null) {
			return "\"\"";
		}
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
				+ "\"";
	}

}
