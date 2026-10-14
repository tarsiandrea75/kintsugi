package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.loop.ScriptedChatModel.evidence;
import static dev.kintsugi.agent.loop.ScriptedChatModel.report;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The evidence check against the fourth full verify-agent run (2026-10-14), whose six reports were all rejected
 * because the model quoted the summaries it was shown (T5c6; agent spec, "Evidence is checked"). The fixture holds
 * that run's tool steps (output and summary) and its report answers, unchanged. Since T5c8 a summary quote counts only
 * from a line that copies the output; the replay is unchanged by it, since none of the run's quotes relied on text only
 * the summary's code wrote.
 */
class LiveRunReportCheckTest {

	private static final JsonNode RUN = load("/live-runs/verify-agent-20261014T174310Z.json");

	@Test
	void aQuoteOfTheSummaryTheModelSawPasses() {
		// F2 step 3: the summary shows status="PAYMENT_FAILED" count=6; the raw output holds {"status":...,"count":6}.
		assertThat(summary("F2", 3)).contains("status=\"PAYMENT_FAILED\" count=6");
		assertThat(errors("F2", report("payment-service", "DEPENDENCY_REJECTING",
				evidence(3, "status_summary", "status=\"PAYMENT_FAILED\" count=6"),
				evidence(5, "recent_events", "type=\"PAYMENT_ATTEMPT\" outcome=\"FAILED\" count=6"))))
			.isEmpty();
	}

	@Test
	void aRunbookQuoteOfTheSummaryWithItsEscapedNewlinesCollapsedPasses() {
		// F2 step 11: the summary shows the chunk as an escaped literal, "orders\n\nAn order ..." with backslashes; the
		// model writes real line breaks and collapses the double one.
		String quote = "untrustedText=\"### Burst of `PAYMENT_FAILED` orders\nAn order ends `PAYMENT_FAILED`";
		assertThat(errors("F2", report("payment-service", "DEPENDENCY_REJECTING",
				evidence(11, "search_runbooks", quote)))).isEmpty();
	}

	@Test
	void aQuoteWithAnInventedCutMarkerOrAbsentTextIsStillRejected() {
		// F3 step 11 shows "| Gatewa\"(cut)"; the model wrote "Gatewa...(cut)". F3 step 13 has "| gateway-mock |", not
		// "| **g". F1 step 5 has "durationMs min=... max=10008 of 9 events", not "durationMs=10008 of 9 events".
		assertThat(errors("F3", report("payment-service", "DEPENDENCY_REJECTING",
				evidence(11, "search_runbooks", "Gatewa...(cut)"),
				evidence(13, "search_runbooks", "|------------|\n| **g"))))
			.containsExactly("evidence[0].quote is not in the output of step 11",
					"evidence[1].quote is not in the output of step 13");
		assertThat(errors("F1", report("payment-service", "DEPENDENCY_REJECTING",
				evidence(5, "recent_events", "durationMs=10008 of 9 events"))))
			.containsExactly("evidence[0].quote is not in the output of step 5");
	}

	@Test
	void replayingTheRunsReportsLeavesOnlyTheQuotesThatAreNotVerbatim() {
		Map<String, List<String>> errors = new LinkedHashMap<>();
		for (String incident : List.of("F1", "F2", "F3")) {
			for (JsonNode report : RUN.at("/incidents/" + incident + "/reports")) {
				errors.put(incident + "#" + report.get("stepNo").intValue(),
						ReportCheck.errors(Answers.json(report.get("answer").asString()), toolSteps(incident)));
			}
		}
		assertThat(errors).containsExactly( //
				Map.entry("F1#15", List.of("evidence[1].quote is not in the output of step 5")),
				Map.entry("F1#16", List.of()),
				Map.entry("F2#15", List.of("evidence[4].quote is not in the output of step 13")),
				Map.entry("F2#16", List.of("evidence[4].quote is not in the output of step 13")),
				Map.entry("F3#15", List.of("evidence[0].quote is not in the output of step 11",
						"evidence[4].quote is not in the output of step 13")),
				Map.entry("F3#16", List.of("evidence[3].quote is not in the output of step 11")));
	}

	private static List<String> errors(String incident, String answer) {
		return ReportCheck.errors(Answers.json(answer), toolSteps(incident));
	}

	private static String summary(String incident, int stepNo) {
		return toolSteps(incident).get(stepNo).summary();
	}

	private static Map<Integer, ToolStep> toolSteps(String incident) {
		Map<Integer, ToolStep> steps = new LinkedHashMap<>();
		for (JsonNode step : RUN.at("/incidents/" + incident + "/toolSteps")) {
			int stepNo = step.get("stepNo").intValue();
			steps.put(stepNo, new ToolStep(stepNo, step.get("tool").asString(), step.get("outcome").asString(),
					text(step.get("output")), text(step.get("summary"))));
		}
		return steps;
	}

	private static String text(JsonNode node) {
		return node == null || node.isNull() ? null : node.asString();
	}

	private static JsonNode load(String resource) {
		try (InputStream in = LiveRunReportCheckTest.class.getResourceAsStream(resource)) {
			return JsonMapper.shared().readTree(in);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
