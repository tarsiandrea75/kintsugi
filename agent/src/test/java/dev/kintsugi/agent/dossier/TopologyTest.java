package dev.kintsugi.agent.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The topology as data (odd/tasks/topology-data.md T2): the lab file reproduces today's {@link CallGraph} and
 * {@link DossierGatherer} constants and orders, and a file that names an unknown service, has a cycle or a duplicate,
 * or cannot be read fails when it loads, with a message naming the culprit.
 */
class TopologyTest {

	private static final Topology LAB = Topology.load(new ClassPathResource("topology/lab.json"));

	@Test
	void theLabFileHasTheServicesInDisplayOrderAndPostgres() {
		assertThat(LAB.services()).containsExactly("gateway-mock", "payment-service", "order-service",
				"inventory-service");
		assertThat(LAB.datastores()).containsExactly("postgres");
	}

	@Test
	void theLabFileReproducesTheCallGraph() {
		assertThat(LAB.leafFirst()).isEqualTo(CallGraph.SERVICES);
		assertThat(LAB.edges()).isEqualTo(CallGraph.EDGES);
		for (String service : LAB.services()) {
			assertThat(LAB.calls(service)).as("calls %s", service).isEqualTo(CallGraph.calls(service));
			assertThat(LAB.calledBy(service)).as("calledBy %s", service).isEqualTo(CallGraph.calledBy(service));
		}
		assertThat(LAB.calledBy("order-service")).containsExactly(CallGraph.EXTERNAL_CLIENT);
		assertThat(LAB.calls("postgres")).isEmpty();
		assertThat(LAB.calledBy("no-such-service")).isEmpty();
	}

	@Test
	void theLabFileReproducesTheOwners() {
		for (String table : DossierGatherer.STATUS_TABLES) {
			assertThat(LAB.ownerOfTable(table)).as("table %s", table).isEqualTo(CallGraph.ownerOfTable(table)).isPresent();
		}
		for (String source : DossierGatherer.EVENT_SOURCES) {
			assertThat(LAB.ownerOfSource(source)).as("source %s", source)
				.isEqualTo(CallGraph.ownerOfSource(source))
				.isPresent();
		}
		assertThat(LAB.ownerOfTable("other")).isEmpty();
		assertThat(LAB.ownerOfTable(null)).isEmpty();
		assertThat(LAB.ownerOfSource(null)).isEmpty();
	}

	@Test
	void theLabFileReproducesTheGathererLists() {
		assertThat(LAB.gatherOrder()).isEqualTo(DossierGatherer.SERVICES);
		assertThat(LAB.statusTables()).isEqualTo(DossierGatherer.STATUS_TABLES);
		assertThat(LAB.eventSources()).isEqualTo(DossierGatherer.EVENT_SOURCES);
	}

	@Test
	void leafFirstBreaksDepthTiesByDisplayOrder() {
		// b and a are both leaves (depth 0), listed b first; c calls a (depth 1).
		Topology topology = load("""
				{"services": ["b", "c", "a"], "datastores": [],
				 "edges": [{"caller": "c", "target": "a", "method": "GET", "uri": "/"}],
				 "tableOwners": {}, "sourceOwners": {}}""");
		assertThat(topology.leafFirst()).containsExactly("b", "a", "c");
		assertThat(topology.gatherOrder()).containsExactly("c", "a", "b");
		assertThat(topology.causes()).containsExactly("b", "c", "a", "UNKNOWN");
	}

	@Test
	void anEdgeTargetOutsideTheServicesFails() {
		assertInvalid(file("[\"a\"]", "[\"db\"]", edge("a", "db"), "{}", "{}"), "edge target 'db'");
		assertInvalid(file("[\"a\"]", "[]", edge("a", "ghost"), "{}", "{}"), "edge target 'ghost'");
	}

	@Test
	void anEdgeCallerOutsideTheServicesFails() {
		assertInvalid(file("[\"a\"]", "[]", edge("ghost", "a"), "{}", "{}"), "edge caller 'ghost'");
	}

	@Test
	void anOwnerOutsideTheServicesFails() {
		assertInvalid(file("[\"a\"]", "[\"db\"]", "", "{\"t\": \"db\"}", "{}"), "owner 'db' of table 't'");
		assertInvalid(file("[\"a\"]", "[]", "", "{}", "{\"s\": \"ghost\"}"), "owner 'ghost' of source 's'");
	}

	@Test
	void aCycleFails() {
		assertInvalid(file("[\"a\", \"b\"]", "[]", edge("a", "b") + ", " + edge("b", "a"), "{}", "{}"),
				"cycle: a -> b -> a");
		assertInvalid(file("[\"a\"]", "[]", edge("a", "a"), "{}", "{}"), "cycle: a -> a");
	}

	@Test
	void aDuplicateNameFails() {
		assertInvalid(file("[\"a\", \"a\"]", "[]", "", "{}", "{}"), "duplicate name 'a'");
		assertInvalid(file("[\"a\"]", "[\"a\"]", "", "{}", "{}"), "duplicate name 'a'");
	}

	@Test
	void noServicesFails() {
		assertInvalid(file("[]", "[]", "", "{}", "{}"), "no services");
	}

	@Test
	void aMissingFieldFails() {
		assertInvalid("{\"services\": [\"a\"], \"datastores\": [], \"tableOwners\": {}, \"sourceOwners\": {}}",
				"'edges'");
	}

	@Test
	void aMissingResourceFails() {
		assertThatThrownBy(() -> Topology.load(new ClassPathResource("topology/no-such-file.json")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("no-such-file.json");
	}

	@Test
	void malformedJsonFails() {
		assertInvalid("{\"services\": [", "fixture");
	}

	private static Topology load(String json) {
		return Topology.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "fixture");
	}

	private static void assertInvalid(String json, String culprit) {
		assertThatThrownBy(() -> load(json)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fixture")
			.hasMessageContaining(culprit);
	}

	private static String file(String services, String datastores, String edges, String tableOwners,
			String sourceOwners) {
		return """
				{"services": %s, "datastores": %s, "edges": [%s], "tableOwners": %s, "sourceOwners": %s}"""
			.formatted(services, datastores, edges, tableOwners, sourceOwners);
	}

	private static String edge(String caller, String target) {
		return """
				{"caller": "%s", "target": "%s", "method": "GET", "uri": "/"}""".formatted(caller, target);
	}

}
