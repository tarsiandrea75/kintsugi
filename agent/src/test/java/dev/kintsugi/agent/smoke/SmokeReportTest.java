package dev.kintsugi.agent.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import dev.kintsugi.agent.smoke.SmokeReport.Verdict;
import org.junit.jupiter.api.Test;

class SmokeReportTest {

	private static final ToolCall OK = new ToolCall("currentTime", "{\"zone\":\"Asia/Tokyo\"}", false,
			Duration.ofMillis(2));

	private static final ToolCall ERR = new ToolCall("currentTime", "{\"zone\":\"Tokyo\"}", true,
			Duration.ofMillis(1));

	private static PromptResult positive(String id, List<ToolCall> calls) {
		return new PromptResult(PromptCase.positive(id, "q " + id, "21:00"), calls, "It is 21:00.",
				Duration.ofSeconds(10));
	}

	private static PromptResult negative(String id, List<ToolCall> calls) {
		return new PromptResult(PromptCase.negative(id, "q " + id, "Tokyo"), calls, "Tokyo.", Duration.ofSeconds(2));
	}

	/** Builds 8 positives, the first {@code valid} of them valid, plus 2 negatives. */
	private static List<PromptResult> results(int valid, int negativesPassed) {
		List<PromptResult> results = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			results.add(positive("P" + i, i <= valid ? List.of(OK) : List.of()));
		}
		results.add(negative("N1", negativesPassed >= 1 ? List.of() : List.of(OK)));
		results.add(negative("N2", negativesPassed >= 2 ? List.of() : List.of(OK)));
		return results;
	}

	@Test
	void positiveIsValidOnlyWithAtLeastOneCallAndNoErrors() {
		assertThat(positive("P1", List.of(OK)).passed()).isTrue();
		assertThat(positive("P1", List.of(OK, OK)).passed()).isTrue();
		assertThat(positive("P1", List.of()).passed()).isFalse();
		assertThat(positive("P1", List.of(ERR, OK)).passed()).isFalse();
		assertThat(positive("P1", List.of(OK, ERR)).passed()).isFalse();
	}

	@Test
	void negativePassesOnlyWithoutCalls() {
		assertThat(negative("N1", List.of()).passed()).isTrue();
		assertThat(negative("N1", List.of(OK)).passed()).isFalse();
	}

	@Test
	void containsExpectedIsCaseInsensitiveAndRequiresEveryValue() {
		PromptCase twoZones = PromptCase.positive("P7", "q", "13:00", "05:00");
		PromptCase anyZone = PromptCase.positive("P6", "q");

		assertThat(new PromptResult(twoZones, List.of(OK), "13:00 and 05:00", Duration.ZERO).containsExpected())
			.contains(true);
		assertThat(new PromptResult(twoZones, List.of(OK), "13:00 only", Duration.ZERO).containsExpected())
			.contains(false);
		assertThat(new PromptResult(PromptCase.positive("P4", "q", "yes"), List.of(OK), "Yes, it is.", Duration.ZERO)
			.containsExpected()).contains(true);
		assertThat(new PromptResult(anyZone, List.of(OK), "whatever", Duration.ZERO).containsExpected()).isEmpty();
		assertThat(new PromptResult(twoZones, List.of(OK), null, Duration.ZERO).containsExpected()).contains(false);
	}

	@Test
	void adoptsAtSevenOfEightWithOneNegativePassed() {
		assertThat(new SmokeReport("m", results(7, 1)).verdict()).isEqualTo(Verdict.ADOPT);
		assertThat(new SmokeReport("m", results(8, 2)).verdict()).isEqualTo(Verdict.ADOPT);
	}

	@Test
	void rejectsAtSixOfEight() {
		assertThat(new SmokeReport("m", results(6, 2)).verdict()).isEqualTo(Verdict.REJECT);
	}

	@Test
	void rejectsWhenNoNegativePassesEvenWithAllPositivesValid() {
		assertThat(new SmokeReport("m", results(8, 0)).verdict()).isEqualTo(Verdict.REJECT);
	}

	@Test
	void fourOfEightIsNotPlanBButThreeIs() {
		assertThat(new SmokeReport("m", results(4, 2)).verdict()).isEqualTo(Verdict.REJECT);
		assertThat(new SmokeReport("m", results(3, 2)).verdict()).isEqualTo(Verdict.PLAN_B);
	}

	@Test
	void computesRatesAndLatencies() {
		List<PromptResult> results = List.of(
				new PromptResult(PromptCase.positive("P1", "q", "x"), List.of(OK), "x", Duration.ofSeconds(1)),
				new PromptResult(PromptCase.positive("P2", "q", "x"), List.of(ERR, OK), "x", Duration.ofSeconds(5)),
				new PromptResult(PromptCase.positive("P3", "q", "x"), List.of(), "x", Duration.ofSeconds(3)),
				new PromptResult(PromptCase.negative("N1", "q", "x"), List.of(), "x", Duration.ofSeconds(9)));
		SmokeReport report = new SmokeReport("m", results);

		assertThat(report.validPositiveRate()).isEqualTo(1.0 / 3);
		assertThat(report.negativesPassed()).isEqualTo(1);
		assertThat(report.firstAttemptValidRate()).isEqualTo(1.0 / 3);
		assertThat(report.medianLatency()).isEqualTo(Duration.ofSeconds(4));
		assertThat(report.maxLatency()).isEqualTo(Duration.ofSeconds(9));
	}

	@Test
	void medianOfOddCountIsTheMiddleValue() {
		SmokeReport report = new SmokeReport("m", List.of(positive("P1", List.of(OK)),
				negative("N1", List.of()), negative("N2", List.of())));

		assertThat(report.medianLatency()).isEqualTo(Duration.ofSeconds(2));
	}

	@Test
	void rendersAMarkdownReport() {
		List<PromptResult> results = new ArrayList<>(results(7, 1));
		results.set(0, new PromptResult(PromptCase.positive("P1", "q", "21:00"), List.of(ERR, OK),
				"line one | pipe\nline two", Duration.ofSeconds(10)));
		String markdown = new SmokeReport("granite4.1:8b", results).toMarkdown();

		assertThat(markdown).startsWith("# Tool-calling smoke test: granite4.1:8b")
			.contains("**Verdict: REJECT**")
			.contains("| P1 |")
			.contains("line one \\| pipe line two")
			.contains("`{\"zone\":\"Tokyo\"}` (error)")
			.doesNotContain("line one | pipe");
	}

	@Test
	void sanitizesModelNamesForFileNames() {
		assertThat(SmokeReport.fileName("granite4.1:8b")).isEqualTo("granite4.1_8b.md");
		assertThat(SmokeReport.fileName("library/qwen3:8b")).isEqualTo("library_qwen3_8b.md");
	}

}
