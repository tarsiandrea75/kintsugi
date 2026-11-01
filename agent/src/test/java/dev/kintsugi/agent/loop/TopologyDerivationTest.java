package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.Topology;
import dev.kintsugi.agent.dossier.Topology.Edge;
import dev.kintsugi.agent.lab.LabTools;
import dev.kintsugi.agent.lab.Phase;
import dev.kintsugi.agent.lab.ToolOutcome;
import dev.kintsugi.agent.lab.ToolResult;

/**
 * The lists and texts that name the system derive from the loaded topology (odd/tasks/topology-data.md T4): another
 * topology file, here three services {@code a -> b -> c} keeping their data in {@code db}
 * ({@code src/test/resources/topology/chain.json}), changes the gather order, the cause enum and the system message.
 * {@link LabTopologyPinsTest} pins the lab's.
 */
class TopologyDerivationTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	private static final Topology CHAIN = Topology
		.load(new DefaultResourceLoader().getResource("classpath:topology/chain.json"));

	@Test
	void anotherTopologyChangesTheGatheredLists() {
		List<String> calls = new ArrayList<>();
		LabTools lab = new LabTools() {
			@Override
			public ToolResult call(String tool, String jsonArgs) {
				calls.add(tool + " " + jsonArgs);
				return new ToolResult(tool, jsonArgs, "mcp-tools call failed: offline", ToolOutcome.CLIENT_ERROR, 0);
			}

			@Override
			public List<org.springframework.ai.tool.ToolCallback> toolsFor(Phase phase) {
				return List.of();
			}
		};

		new DossierGatherer(lab, Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC), CHAIN).gather(10);

		assertThat(calls).filteredOn(call -> call.startsWith("query_metric") && call.contains("\"latency_avg\""))
			.extracting(call -> JSON.readTree(call.substring(call.indexOf(' '))).path("service").asString())
			.containsExactly("a", "b", "c");
		assertThat(calls).filteredOn(call -> call.startsWith("query_metric") && call.contains("\"open_files\""))
			.extracting(call -> JSON.readTree(call.substring(call.indexOf(' '))).path("service").asString())
			.containsExactly("a", "b", "c");
		assertThat(calls).filteredOn(call -> call.startsWith("status_summary"))
			.extracting(call -> JSON.readTree(call.substring(call.indexOf(' '))).path("table").asString())
			.containsExactly("ta", "ta");
		assertThat(calls).filteredOn(call -> call.startsWith("event_counts"))
			.extracting(call -> JSON.readTree(call.substring(call.indexOf(' '))).path("source").asString())
			.containsExactly("sb", "sb", "sc", "sc");
	}

	@Test
	void anotherTopologyChangesTheCauseEnum() {
		List<String> causes = List.of("a", "b", "c", "db", "UNKNOWN");
		for (AgentMode mode : AgentMode.values()) {
			assertThat(values(JSON.readTree(Schemas.report(mode, CHAIN)).at("/properties/rootCauseService/enum")))
				.isEqualTo(causes);
		}
		assertThat(values(JSON.readTree(Schemas.assessedReport(CHAIN)).at("/properties/rootCauseService/enum")))
			.isEqualTo(causes);
		JsonNode plan = JSON.readTree("{\"hypotheses\":[{\"rootCauseService\":\"postgres\","
				+ "\"rootCauseCategory\":\"CONFIGURATION\",\"why\":\"x\"}],\"nextTools\":[]}");
		assertThat(PlanCheck.check(plan, Phase.INVESTIGATE, CHAIN).errors()).containsExactly(
				"hypotheses[0].rootCauseService must be one of a, b, c, db, UNKNOWN; got \"postgres\"");
		JsonNode report = JSON.readTree("{\"summary\":\"s\",\"evidence\":[],\"rootCauseService\":\"db\","
				+ "\"rootCauseCategory\":\"UNKNOWN\",\"confidence\":0.5}");
		assertThat(ReportCheck.check(report, Map.of(), CHAIN).errors())
			.noneMatch(error -> error.startsWith("rootCauseService"));
	}

	@Test
	void anotherTopologyChangesTheSystemMessage() {
		String system = Prompts.system(CHAIN, "orders are slow", 15);

		assertThat(system).startsWith("You are an SRE agent diagnosing one incident in a small online shop. Its "
				+ "components are a, b, c and their database, db. The call path: a calls b; b calls c; the three "
				+ "services keep their data in db.\n\nReported symptom: \"orders are slow\"\n");
		assertThat(system).contains("shows: a, b, c, db, or UNKNOWN.\n").doesNotContain("order-service");
	}

	@Test
	void theRenderingRuleJoinsListsAndCountsTheServicesThatKeepData() {
		Topology fan = new Topology(List.of("front", "x", "y", "z"), List.of("pg", "redis"),
				List.of(new Edge(null, "front", "GET", "/"), new Edge("front", "x", "GET", "/x"),
						new Edge("front", "y", "GET", "/y"), new Edge("front", "z", "GET", "/z")),
				Map.of("t", "x"), Map.of());
		assertThat(Prompts.system(fan, "s", 1)).contains("Its components are front, x, y, z and their databases, pg "
				+ "and redis. The call path: front calls x, y and z; x keeps its data in pg and redis.\n");

		Topology bare = new Topology(List.of("p", "q"), List.of(), List.of(), Map.of(), Map.of());
		assertThat(Prompts.system(bare, "s", 1))
			.contains("Its components are p and q. The call path: no component calls another.\n")
			.contains("shows: p, q, or UNKNOWN.");
	}

	private static List<String> values(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(value -> values.add(value.asString()));
		return values;
	}

}
