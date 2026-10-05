package dev.kintsugi.agent.tools;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Read-only, deterministic time tool. The model proposes the call; the answer is computed from
 * the injected {@link Clock}.
 */
@Component
public class TimeTools {

	private final Clock clock;

	public TimeTools(Clock clock) {
		this.clock = clock;
	}

	@Tool(description = "Current date-time in an IANA time zone, e.g. America/Argentina/Buenos_Aires")
	public String currentTime(@ToolParam(description = "IANA zone id") String zone) {
		if (zone == null || zone.isBlank()) {
			return invalidZone(zone);
		}
		try {
			return OffsetDateTime.ofInstant(clock.instant(), ZoneId.of(zone))
				.truncatedTo(ChronoUnit.SECONDS)
				.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		}
		catch (DateTimeException e) {
			return invalidZone(zone);
		}
	}

	private static String invalidZone(String zone) {
		return "Error: invalid time zone '" + zone + "'. Use an IANA zone id such as Europe/Rome.";
	}

}
