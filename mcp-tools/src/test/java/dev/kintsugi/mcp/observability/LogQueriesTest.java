package dev.kintsugi.mcp.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LogQL is built only from templates. The {@code contains} text is escaped for a double-quoted LogQL string, so it is
 * matched literally and can never close the string or the stream selector.
 */
class LogQueriesTest {

	@Test
	void selectsOnlyTheRequestedServiceStream() {
		assertThat(LogQueries.lines("order-service", null, null)).isEqualTo("{service=\"order-service\"}");
	}

	@Test
	void containsIsALiteralLineFilter() {
		assertThat(LogQueries.lines("order-service", "PAYMENT_UNAVAILABLE", null))
			.isEqualTo("{service=\"order-service\"} |= \"PAYMENT_UNAVAILABLE\"");
	}

	@Test
	void quotesAreEscaped() {
		assertThat(LogQueries.lines("order-service", "say \"hi\"", null))
			.isEqualTo("{service=\"order-service\"} |= \"say \\\"hi\\\"\"");
	}

	@Test
	void backslashesAreEscaped() {
		assertThat(LogQueries.lines("order-service", "C:\\temp\\", null))
			.isEqualTo("{service=\"order-service\"} |= \"C:\\\\temp\\\\\"");
	}

	@Test
	void backticksNeedNoEscapeInsideADoubleQuotedString() {
		assertThat(LogQueries.lines("order-service", "a`b`", null))
			.isEqualTo("{service=\"order-service\"} |= \"a`b`\"");
	}

	@Test
	void querySyntaxInContainsStaysInsideTheString() {
		// The spec scenario: `"} |= "" or 1=1`.
		assertThat(LogQueries.lines("order-service", "\"} |= \"\" or 1=1", null))
			.isEqualTo("{service=\"order-service\"} |= \"\\\"} |= \\\"\\\" or 1=1\"");
	}

	@Test
	void levelMatchesTheSpringBootConsoleLevelColumn() {
		assertThat(LogQueries.lines("payment-service", null, "WARN"))
			.isEqualTo("{service=\"payment-service\"} |~ `^\\S+\\s+WARN\\s`");
	}

	@Test
	void countWrapsTheSameFilterInCountOverTimeOverTheWindow() {
		assertThat(LogQueries.count("gateway-mock", "event=AUTHORIZE", "ERROR", 15)).isEqualTo(
				"sum(count_over_time({service=\"gateway-mock\"} |= \"event=AUTHORIZE\" |~ `^\\S+\\s+ERROR\\s` [15m]))");
	}

	@Test
	void levelIsReadFromTheConsoleLine() {
		assertThat(LogQueries.levelOf("2026-10-08T10:00:00.123Z  WARN 1 --- [order-service] [main] c.e.X : boom"))
			.isEqualTo("WARN");
		assertThat(LogQueries.levelOf("not a spring line")).isNull();
	}

}
