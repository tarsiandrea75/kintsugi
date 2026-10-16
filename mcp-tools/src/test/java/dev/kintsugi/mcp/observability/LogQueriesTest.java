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
		assertThat(LogQueries.count("payment-service", "event=AUTHORIZE", "ERROR", 15)).isEqualTo(
				"sum(count_over_time({service=\"payment-service\"} |= \"event=AUTHORIZE\" |~ `^\\S+\\s+ERROR\\s` [15m]))");
	}

	@Test
	void gatewayMockLinesOfTheLabsControlPlaneAreLeftOut() {
		// BehaviorStore logs only the fault-injector's admin calls ("Gateway behavior changed/reset"): a real gateway
		// would not tell where a fault was injected. Its other lines, such as "Authorize answered", stay.
		assertThat(LogQueries.lines("gateway-mock", "behavior", "INFO")).isEqualTo("{service=\"gateway-mock\"}"
				+ " !~ `\\.BehaviorStore\\s+:\\s` |= \"behavior\" |~ `^\\S+\\s+INFO\\s`");
		assertThat(LogQueries.count("gateway-mock", null, null, 15)).isEqualTo(
				"sum(count_over_time({service=\"gateway-mock\"} !~ `\\.BehaviorStore\\s+:\\s` [15m]))");
	}

	@Test
	void theControlPlaneFilterMatchesTheBehaviorStoreLinesOnly() {
		java.util.regex.Pattern filter = java.util.regex.Pattern.compile("\\.BehaviorStore\\s+:\\s");
		assertThat(filter.matcher("2026-10-16T13:54:45.327Z  INFO 1 --- [gateway-mock] [at-handler-1417]"
				+ " d.k.lab.gateway.behavior.BehaviorStore   : Gateway behavior changed: event=BEHAVIOR_CHANGED"
				+ " previous_latency_ms=0 latency_ms=0 previous_error_status=none error_status=429").find()).isTrue();
		assertThat(filter.matcher("2026-10-16T13:54:46.001Z  INFO 1 --- [gateway-mock] [at-handler-1418]"
				+ " d.k.lab.gateway.api.AuthorizeController   : Authorize answered: event=AUTHORIZE status=429").find())
			.isFalse();
	}

	@Test
	void levelIsReadFromTheConsoleLine() {
		assertThat(LogQueries.levelOf("2026-10-08T10:00:00.123Z  WARN 1 --- [order-service] [main] c.e.X : boom"))
			.isEqualTo("WARN");
		assertThat(LogQueries.levelOf("not a spring line")).isNull();
	}

}
