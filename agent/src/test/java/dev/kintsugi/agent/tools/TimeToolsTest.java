package dev.kintsugi.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.support.ToolCallbacks;

class TimeToolsTest {

	private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

	private final TimeTools tools = new TimeTools(Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void buenosAiresIsThreeHoursBehindUtc() {
		assertThat(tools.currentTime("America/Argentina/Buenos_Aires")).isEqualTo("2026-10-05T09:00:00-03:00");
	}

	@Test
	void romeIsTwoHoursAheadOfUtcInOctober() {
		String result = tools.currentTime("Europe/Rome");

		assertThat(result).isEqualTo("2026-10-05T14:00:00+02:00");
		assertThat(OffsetDateTime.parse(result).toInstant()).isEqualTo(NOW);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "Mars/Olympus_Mons", "", "   " })
	void invalidZoneReturnsErrorInsteadOfThrowing(String zone) {
		String result = tools.currentTime(zone);

		assertThat(result).startsWith("Error: ").contains("'" + zone + "'");
	}

	@Test
	void exposesCurrentTimeToolWithZoneParameterInSchema() {
		ToolCallback[] callbacks = ToolCallbacks.from(tools);

		assertThat(callbacks).hasSize(1);
		assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("currentTime");
		assertThat(callbacks[0].getToolDefinition().inputSchema()).contains("\"zone\"");
		assertThat(callbacks[0].call("{\"zone\":\"Europe/Rome\"}")).contains("2026-10-05T14:00:00+02:00");
	}

}
