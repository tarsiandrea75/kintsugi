package dev.kintsugi.agent.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link ReplayLabTools} over recorded incidents ({@code src/test/resources/replay-fixture}) and the recorded tool
 * listing ({@code mcp-outputs/tools-list.json}) (agent spec, "Replay of recorded tool outputs").
 */
class ReplayLabToolsTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final String SEARCH_LOGS_ARGS = "{\"limit\":20,\"service\":\"payment-service\",\"sinceMinutes\":12}";

	@Test
	void aRecordedCallReturnsItsRecordedOutputAndOutcome() throws Exception {
		ReplayLabTools tools = replay("incident-a.json");

		ToolResult result = tools.call("search_logs", SEARCH_LOGS_ARGS);

		assertThat(result.tool()).isEqualTo("search_logs");
		assertThat(result.outcome()).isEqualTo(ToolOutcome.OK);
		assertThat(result.rawOutput()).isEqualTo(recordedOutput("incident-a.json", 3));
		assertThat(JSON.readTree(result.args())).isEqualTo(JSON.readTree(SEARCH_LOGS_ARGS));
		assertThat(result.durationMs()).isZero();
		assertThat(tools.recordedCalls()).isEqualTo(1);
		assertThat(tools.unrecordedCalls()).isZero();
	}

	@Test
	void aRecordedErrorKeepsItsOutcome() throws Exception {
		ToolResult result = replay("incident-a.json").call("get_order_trace",
				"{\"orderId\":\"1ba57520-0bf7-4413-aa1b-08ec92cf2fa9\"}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.UPSTREAM_ERROR);
		assertThat(result.rawOutput()).isEqualTo(recordedOutput("incident-a.json", 4));
	}

	@Test
	void theOrderOfTheArgumentsDoesNotMatter() throws Exception {
		ToolResult result = replay("incident-a.json").call("search_logs",
				"{\"sinceMinutes\":12,\"service\":\"payment-service\",\"limit\":20}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.OK);
		assertThat(result.rawOutput()).isEqualTo(recordedOutput("incident-a.json", 3));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			{"service":"payment-service","sinceMinutes":12,"limit":50}
			{"service":"payment-service","sinceMinutes":12}
			""")
	void theLimitIsCappedBeforeTheLookup(String args) throws Exception {
		ReplayLabTools tools = replay("incident-a.json");

		ToolResult result = tools.call("search_logs", args);

		assertThat(result.rawOutput()).isEqualTo(recordedOutput("incident-a.json", 3));
		assertThat(JSON.readTree(result.args()).path("limit").asInt()).isEqualTo(20);
		assertThat(tools.recordedCalls()).isEqualTo(1);
	}

	@Test
	void theDossierCallsAreServed() throws Exception {
		ReplayLabTools tools = replay("incident-a.json");

		ToolResult restarts = tools.call("service_restarts", "{\"sinceMinutes\":12}");
		ToolResult previous = tools.call("status_summary",
				"{\"untilMinutesAgo\":12,\"table\":\"orders\",\"sinceMinutes\":24}");

		assertThat(restarts.outcome()).isEqualTo(ToolOutcome.OK);
		assertThat(restarts.rawOutput()).contains("gateway-mock");
		assertThat(previous.outcome()).isEqualTo(ToolOutcome.OK);
		assertThat(previous.rawOutput()).contains("CONFIRMED");
		assertThat(tools.recordedCalls()).isEqualTo(2);
		assertThat(tools.coverage()).isEqualTo(1.0);
	}

	@Test
	void anUnrecordedCallIsAClientErrorAndCounted() throws Exception {
		ReplayLabTools tools = replay("incident-a.json");
		tools.call("search_logs", SEARCH_LOGS_ARGS);

		ToolResult result = tools.call("search_logs",
				"{\"sinceMinutes\":12,\"service\":\"order-service\",\"level\":\"ERROR\"}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.CLIENT_ERROR);
		assertThat(result.rawOutput()).isEqualTo("not recorded in this replay: search_logs "
				+ "{\"level\":\"ERROR\",\"limit\":20,\"service\":\"order-service\",\"sinceMinutes\":12}");
		assertThat(result.durationMs()).isZero();
		assertThat(tools.recordedCalls()).isEqualTo(1);
		assertThat(tools.unrecordedCalls()).isEqualTo(1);
		assertThat(tools.coverage()).isEqualTo(0.5);
	}

	@Test
	void aStepWithoutOutputIsNotRecorded() throws Exception {
		// Step 5 of incident-a is a refused count_logs: it never reached mcp-tools, so it has no output to replay.
		ReplayLabTools tools = replay("incident-a.json");

		ToolResult result = tools.call("count_logs", "{\"service\":\"payment-service\",\"sinceMinutes\":12}");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.CLIENT_ERROR);
		assertThat(tools.unrecordedCalls()).isEqualTo(1);
	}

	@Test
	void argumentsThatAreNotAnObjectAreRejectedAsMcpLabToolsDoes() throws Exception {
		ReplayLabTools tools = replay("incident-a.json");

		ToolResult result = tools.call("count_logs", "[1, 2]");

		ToolResult live = McpLabTools.notAnObject("count_logs", "[1, 2]");
		assertThat(result).isEqualTo(live);
		assertThat(result.outcome()).isEqualTo(ToolOutcome.INVALID_PARAMETER);
		assertThat(JSON.readTree(result.rawOutput()).get("reason").asString())
			.isEqualTo("the arguments are not a JSON object");
		assertThat(tools.call("count_logs", "not json").outcome()).isEqualTo(ToolOutcome.INVALID_PARAMETER);
	}

	@Test
	void blankArgumentsAreAnEmptyObject() throws Exception {
		ToolResult result = replay("incident-a.json").call("service_restarts", " ");

		assertThat(result.outcome()).isEqualTo(ToolOutcome.CLIENT_ERROR);
		assertThat(result.rawOutput()).isEqualTo("not recorded in this replay: service_restarts {}");
	}

	@Test
	void aNameOutsideTheLabToolsIsABugOfTheCaller() throws Exception {
		ReplayLabTools tools = replay("incident-a.json");

		assertThatIllegalArgumentException().isThrownBy(() -> tools.call("rollback", "{}"));
		assertThat(tools.recordedCalls() + tools.unrecordedCalls()).isZero();
	}

	@Test
	void aConflictingRecordingKeepsTheFirstAndIsCounted() throws Exception {
		// incident-b records the same search_logs call with another output, the same get_order_trace output, and a
		// query_metric call incident-a does not hold.
		ReplayLabTools tools = replay("incident-a.json", "incident-b.json");

		assertThat(tools.call("search_logs", SEARCH_LOGS_ARGS).rawOutput())
			.isEqualTo(recordedOutput("incident-a.json", 3));
		assertThat(tools.call("query_metric", "{\"service\":\"order-service\",\"metric\":\"error_rate\"}").outcome())
			.isEqualTo(ToolOutcome.OK);
		assertThat(tools.conflicts()).isEqualTo(1);
	}

	@Test
	void eachPhaseOffersItsRecordedDefinitions() throws Exception {
		ReplayLabTools tools = replay("incident-a.json");

		assertThat(names(tools.toolsFor(Phase.TRIAGE))).containsExactly("status_summary", "query_metric",
				"service_restarts");
		assertThat(names(tools.toolsFor(Phase.INVESTIGATE))).containsExactly("search_logs", "count_logs",
				"recent_events", "get_order_trace");
		assertThat(names(tools.toolsFor(Phase.KNOWLEDGE))).containsExactly("search_runbooks");
		assertThat(tools.toolsFor(Phase.CONCLUDE)).isEmpty();
	}

	@Test
	void anOfferedDefinitionCarriesItsSchemaButCannotBeExecuted() throws Exception {
		ToolCallback searchLogs = replay("incident-a.json").toolsFor(Phase.INVESTIGATE).get(0);

		assertThat(searchLogs.getToolDefinition().description()).isNotBlank();
		assertThat(JSON.readTree(searchLogs.getToolDefinition().inputSchema()).path("properties").has("service"))
			.isTrue();
		assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> searchLogs.call("{}"));
	}

	@Test
	void aToolMissingFromTheListingMakesThePhaseUnavailable() throws Exception {
		ObjectNode listing = (ObjectNode) JSON.readTree(path("/mcp-outputs/tools-list.json").toFile());
		ArrayNode kept = JSON.createArrayNode();
		for (JsonNode tool : listing.get("tools")) {
			if (!tool.path("name").asString().equals("search_runbooks")) {
				kept.add(tool);
			}
		}
		listing.set("tools", kept);
		ReplayLabTools tools = new ReplayLabTools(listing,
				List.of(JSON.readTree(path("/replay-fixture/incident-a.json").toFile())));

		assertThatExceptionOfType(LabToolsUnavailableException.class).isThrownBy(() -> tools.toolsFor(Phase.KNOWLEDGE))
			.withMessageContaining("search_runbooks");
		assertThat(names(tools.toolsFor(Phase.TRIAGE))).hasSize(3);
	}

	private static ReplayLabTools replay(String... incidents) throws URISyntaxException {
		List<Path> paths = new ArrayList<>();
		for (String incident : incidents) {
			paths.add(path("/replay-fixture/" + incident));
		}
		return ReplayLabTools.fromFiles(path("/mcp-outputs/tools-list.json"), paths);
	}

	private static String recordedOutput(String incident, int stepNo) throws URISyntaxException {
		for (JsonNode step : JSON.readTree(path("/replay-fixture/" + incident).toFile()).get("steps")) {
			if (step.path("stepNo").asInt() == stepNo) {
				return step.get("toolOutput").asString();
			}
		}
		throw new IllegalArgumentException("no step " + stepNo + " in " + incident);
	}

	private static Path path(String resource) throws URISyntaxException {
		return Path.of(ReplayLabToolsTest.class.getResource(resource).toURI());
	}

	private static List<String> names(List<ToolCallback> tools) {
		return tools.stream().map(tool -> tool.getToolDefinition().name()).toList();
	}

}
