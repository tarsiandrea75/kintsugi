package dev.kintsugi.agent.dossier;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import dev.kintsugi.agent.dossier.Dossier.SourceEvents;
import dev.kintsugi.agent.dossier.Dossier.TableStates;
import dev.kintsugi.agent.dossier.Topology.Edge;

/**
 * The topology as data (odd/tasks/topology-data.md T2, T3): the lab file holds the lab's call graph and owners, which
 * {@code CallGraph}'s constants held before T3 (odd/tasks/service-assessment.md T1, T1r), and reproduces today's
 * {@link DossierGatherer} lists; a file that names an unknown service, has a cycle or a duplicate, has a field of the
 * wrong JSON type or cannot be read fails when it loads, with a message naming the file and the culprit.
 */
class TopologyTest {

	@Test
	void theLabFileHasTheServicesInDisplayOrderAndPostgres() {
		assertThat(LAB.services()).containsExactly("gateway-mock", "payment-service", "order-service",
				"inventory-service");
		assertThat(LAB.datastores()).containsExactly("postgres");
	}

	@Test
	void theEdgesAreTheCallPathTheTablesAlwaysRendered() {
		assertThat(LAB.edges()).containsExactly(new Edge(null, "order-service", "POST", "/orders"),
				new Edge("order-service", "inventory-service", "POST", "/reservations"),
				new Edge("order-service", "payment-service", "POST", "/payments"),
				new Edge("payment-service", "gateway-mock", "POST", "/authorize"));
	}

	@Test
	void theServicesComeLeafFirstAndAreTheOnesTheGathererReads() {
		List<String> leafFirst = LAB.leafFirst();
		assertThat(leafFirst).containsExactly("gateway-mock", "inventory-service", "payment-service", "order-service");
		assertThat(leafFirst).containsExactlyInAnyOrderElementsOf(DossierGatherer.SERVICES);
		// Leaf first: every service comes after the services it calls.
		for (int i = 0; i < leafFirst.size(); i++) {
			for (String callee : LAB.calls(leafFirst.get(i))) {
				assertThat(leafFirst.indexOf(callee)).as("%s calls %s", leafFirst.get(i), callee).isBetween(0, i - 1);
			}
		}
	}

	@Test
	void eachServiceCallsAndIsCalledByItsNeighboursInEdgeOrder() {
		assertThat(LAB.calls("order-service")).containsExactly("inventory-service", "payment-service");
		assertThat(LAB.calls("payment-service")).containsExactly("gateway-mock");
		assertThat(LAB.calls("inventory-service")).isEmpty();
		assertThat(LAB.calls("gateway-mock")).isEmpty();
		assertThat(LAB.calledBy("gateway-mock")).containsExactly("payment-service");
		assertThat(LAB.calledBy("payment-service")).containsExactly("order-service");
		assertThat(LAB.calledBy("inventory-service")).containsExactly("order-service");
		// The first edge's caller is outside the lab.
		assertThat(LAB.calledBy("order-service")).containsExactly(Topology.EXTERNAL_CLIENT);
		assertThat(Topology.EXTERNAL_CLIENT).isEqualTo("external client");
		assertThat(LAB.calls("postgres")).isEmpty();
		assertThat(LAB.calledBy("postgres")).isEmpty();
		assertThat(LAB.calledBy("no-such-service")).isEmpty();
	}

	@Test
	void eachTableAndEventSourceHasItsOwnerByNameAndAnyOtherNameNone() {
		assertThat(LAB.ownerOfTable("orders")).hasValue("order-service");
		assertThat(LAB.ownerOfTable("payments")).hasValue("payment-service");
		assertThat(LAB.ownerOfSource("order")).hasValue("order-service");
		assertThat(LAB.ownerOfSource("payment")).hasValue("payment-service");
		assertThat(LAB.ownerOfSource("reservation")).hasValue("inventory-service");
		// No lab service owns them: the sheets keep them under "Other rows".
		assertThat(LAB.ownerOfTable("stock")).isEmpty();
		assertThat(LAB.ownerOfSource("inventory")).isEmpty();
		assertThat(LAB.ownerOfTable(null)).isEmpty();
		assertThat(LAB.ownerOfSource(null)).isEmpty();
	}

	@Test
	void everyTableAndEventSourceTheGathererProducesHasALabServiceAsItsOwner() {
		// The guard: a table or a source the gatherer reads with no owner here fails the build, not an incident's
		// sheets, which would keep it under "Other rows".
		Dossier dossier = DossierRendererTest.gather("f3");

		assertThat(dossier.states()).extracting(TableStates::table)
			.containsExactlyElementsOf(DossierGatherer.STATUS_TABLES)
			.allSatisfy(table -> assertThat(LAB.ownerOfTable(table)).as(table)
				.hasValueSatisfying(owner -> assertThat(LAB.services()).contains(owner)));
		assertThat(dossier.events()).extracting(SourceEvents::source)
			.containsExactlyElementsOf(DossierGatherer.EVENT_SOURCES)
			.allSatisfy(source -> assertThat(LAB.ownerOfSource(source)).as(source)
				.hasValueSatisfying(owner -> assertThat(LAB.services()).contains(owner)));
		assertThat(ServiceSheets.render(dossier, LAB).text()).doesNotContain("## Other rows");
	}

	@Test
	void theDefaultLocationIsTheLabFile() {
		assertThat(Topology.DEFAULT_LOCATION).isEqualTo("classpath:topology/lab.json");
		assertThat(Topology.load(new ClassPathResource("topology/lab.json"))).isEqualTo(LAB);
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

	@ParameterizedTest
	@ValueSource(strings = { "services", "datastores", "edges", "tableOwners", "sourceOwners" })
	void aMissingFieldFails(String field) {
		assertInvalid(without(field), "missing field '" + field + "'");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			services     | {"services": {"x": "a"}, "datastores": [], "edges": [], "tableOwners": {}, "sourceOwners": {}}
			services     | {"services": "a", "datastores": [], "edges": [], "tableOwners": {}, "sourceOwners": {}}
			datastores   | {"services": ["a"], "datastores": {}, "edges": [], "tableOwners": {}, "sourceOwners": {}}
			edges        | {"services": ["a"], "datastores": [], "edges": {}, "tableOwners": {}, "sourceOwners": {}}
			tableOwners  | {"services": ["a"], "datastores": [], "edges": [], "tableOwners": [], "sourceOwners": {}}
			sourceOwners | {"services": ["a"], "datastores": [], "edges": [], "tableOwners": {}, "sourceOwners": ["a"]}
			""")
	void aFieldOfTheWrongJsonTypeFails(String field, String json) {
		assertInvalid(json, "'" + field + "'");
	}

	@Test
	void anEdgeThatIsNotAnObjectFails() {
		assertInvalid(file("[\"a\"]", "[]", "\"a\"", "{}", "{}"), "edge 1");
	}

	@Test
	void anEdgeCallerThatIsNeitherNullNorAStringFails() {
		assertInvalid(file("[\"a\", \"b\"]", "[]", """
				{"caller": 5, "target": "a", "method": "GET", "uri": "/"}""", "{}", "{}"), "'caller'");
		assertInvalid(file("[\"a\", \"b\"]", "[]", """
				{"caller": ["b"], "target": "a", "method": "GET", "uri": "/"}""", "{}", "{}"), "'caller'");
		// The external client is written as an explicit null, never left out.
		assertInvalid(file("[\"a\"]", "[]", """
				{"target": "a", "method": "GET", "uri": "/"}""", "{}", "{}"), "'caller'");
	}

	@Test
	void anEmptyFileFails() {
		assertInvalid("", "empty");
		assertInvalid("   \n", "empty");
	}

	@Test
	void aRootThatIsNotAnObjectFails() {
		assertInvalid("[]", "object");
	}

	@Test
	void aServiceOrDatastoreNamedUnknownFails() {
		assertInvalid(file("[\"a\", \"UNKNOWN\"]", "[]", "", "{}", "{}"), "'UNKNOWN'");
		assertInvalid(file("[\"a\"]", "[\"UNKNOWN\"]", "", "{}", "{}"), "'UNKNOWN'");
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

	/** A valid one-service file without {@code field}. */
	private static String without(String field) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("services", "[\"a\"]");
		fields.put("datastores", "[]");
		fields.put("edges", "[]");
		fields.put("tableOwners", "{}");
		fields.put("sourceOwners", "{}");
		fields.remove(field);
		StringJoiner json = new StringJoiner(", ", "{", "}");
		fields.forEach((name, value) -> json.add("\"" + name + "\": " + value));
		return json.toString();
	}

	private static String edge(String caller, String target) {
		return """
				{"caller": "%s", "target": "%s", "method": "GET", "uri": "/"}""".formatted(caller, target);
	}

}
