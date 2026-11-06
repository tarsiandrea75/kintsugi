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
import java.util.stream.Stream;

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
		assertThat(leafFirst).containsExactlyInAnyOrderElementsOf(LAB.gatherOrder());
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
			.containsExactlyElementsOf(LAB.statusTables())
			.allSatisfy(table -> assertThat(LAB.ownerOfTable(table)).as(table)
				.hasValueSatisfying(owner -> assertThat(LAB.services()).contains(owner)));
		assertThat(dossier.events()).extracting(SourceEvents::source)
			.containsExactlyElementsOf(LAB.eventSources())
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
		assertThat(LAB.gatherOrder()).containsExactly("order-service", "payment-service", "inventory-service",
				"gateway-mock");
		assertThat(LAB.statusTables()).containsExactly("orders", "payments");
		assertThat(LAB.eventSources()).containsExactly("order", "payment", "reservation");
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

	// The RCAEval topology files (odd/tasks/rcaeval-converter.md T2): services from the metric prefixes, edges from the
	// pinned upstream manifests and sources, one CALL / edge per caller -> target pair.

	@Test
	void theRe2ObFileHasOnlineBoutiquesServicesAndManifestEdges() {
		Topology ob = Topology.load(new ClassPathResource("topology/re2-ob.json"));
		assertThat(ob.services()).containsExactly("frontend", "adservice", "cartservice", "checkoutservice",
				"currencyservice", "emailservice", "paymentservice", "productcatalogservice", "recommendationservice",
				"redis", "shippingservice");
		assertThat(ob.datastores()).isEmpty();
		assertThat(ob.edges()).hasSize(16).allSatisfy(edge -> {
			assertThat(edge.method()).isEqualTo("CALL");
			assertThat(edge.uri()).isEqualTo("/");
		});
		assertThat(ob.edges().getFirst()).isEqualTo(new Edge(null, "frontend", "CALL", "/"));
		assertThat(ob.calls("frontend")).containsExactly("adservice", "cartservice", "checkoutservice",
				"currencyservice", "productcatalogservice", "recommendationservice", "shippingservice");
		assertThat(ob.calls("checkoutservice")).containsExactly("cartservice", "currencyservice", "emailservice",
				"paymentservice", "productcatalogservice", "shippingservice");
		assertThat(ob.calls("recommendationservice")).containsExactly("productcatalogservice");
		assertThat(ob.calls("cartservice")).containsExactly("redis");
		assertThat(ob.statusTables()).isEmpty();
		assertThat(ob.eventSources()).isEmpty();
	}

	@Test
	void theRe2SsFileHasSockShopsServicesAndSourceEdges() {
		Topology ss = Topology.load(new ClassPathResource("topology/re2-ss.json"));
		assertThat(ss.services()).containsExactly("front-end", "carts", "catalogue", "orders", "payment",
				"queue-master", "rabbitmq", "session", "shipping", "user");
		assertThat(ss.datastores()).isEmpty();
		assertThat(ss.edges()).hasSize(12).allSatisfy(edge -> {
			assertThat(edge.method()).isEqualTo("CALL");
			assertThat(edge.uri()).isEqualTo("/");
		});
		assertThat(ss.edges().getFirst()).isEqualTo(new Edge(null, "front-end", "CALL", "/"));
		assertThat(ss.calls("front-end")).containsExactly("carts", "catalogue", "orders", "session", "user");
		assertThat(ss.calls("orders")).containsExactly("carts", "payment", "shipping", "user");
		assertThat(ss.calls("shipping")).containsExactly("rabbitmq");
		assertThat(ss.calls("queue-master")).containsExactly("rabbitmq");
		assertThat(ss.calledBy("rabbitmq")).containsExactly("shipping", "queue-master");
		assertThat(ss.statusTables()).isEmpty();
		assertThat(ss.eventSources()).isEmpty();
	}

	@Test
	void theRe2ObServicesAreTheNormalizedMetricPrefixes() {
		// The prefixes of the simple_metrics.csv headers of RE2-OB/*/1 (the union over the 30 repetition-1 files).
		List<String> prefixes = List.of("adservice", "cartservice", "checkoutservice", "currencyservice",
				"emailservice", "frontend", "frontend-external", "istio-init", "paymentservice", "productcatalogservice",
				"recommendationservice", "redis", "shippingservice");
		assertThat(Topology.load(new ClassPathResource("topology/re2-ob.json")).services())
			.isEqualTo(displayOrder("frontend", normalized(prefixes)));
	}

	@Test
	void theRe2SsServicesAreTheNormalizedMetricPrefixes() {
		// The prefixes of the one RE2-SS header read: RE2-SS/carts_cpu/1/simple_metrics.csv, first line only.
		List<String> prefixes = List.of("carts", "carts-db", "catalogue", "catalogue-db", "front-end", "orders",
				"orders-db", "payment", "queue-master", "rabbitmq", "rabbitmq-exporter", "session-db", "shipping", "user",
				"user-db");
		assertThat(Topology.load(new ClassPathResource("topology/re2-ss.json")).services())
			.isEqualTo(displayOrder("front-end", normalized(prefixes)));
	}

	@Test
	void theRe2TtFileHasTrainTicketsServicesAndSourceEdges() {
		Topology tt = Topology.load(new ClassPathResource("topology/re2-tt.json"));
		assertThat(tt.services()).hasSize(68);
		assertThat(tt.datastores()).isEmpty();
		assertThat(tt.edges()).hasSize(153).allSatisfy(edge -> {
			assertThat(edge.method()).isEqualTo("CALL");
			assertThat(edge.uri()).isEqualTo("/");
		});
		assertThat(tt.edges().getFirst()).isEqualTo(new Edge(null, "ts-ui-dashboard", "CALL", "/"));
		assertThat(tt.calls("ts-travel-service")).containsExactly("ts-order-service", "ts-route-service",
				"ts-seat-service", "ts-ticketinfo-service", "ts-train-service", "ts-travel-mongo");
		assertThat(tt.calls("ts-travel2-service")).containsExactly("ts-order-other-service", "ts-route-service",
				"ts-seat-service", "ts-ticketinfo-service", "ts-train-service", "ts-travel2-mongo");
		// Train Ticket's seat service calls back into the travel services, a cycle Topology rejects: the back edges
		// seat -> travel and seat -> travel2 are dropped (ADR-033), the main request direction travel -> seat is kept.
		assertThat(tt.calls("ts-seat-service")).containsExactly("ts-config-service", "ts-order-other-service",
				"ts-order-service");
		assertThat(tt.edges()).doesNotContain(new Edge("ts-seat-service", "ts-travel-service", "CALL", "/"),
				new Edge("ts-seat-service", "ts-travel2-service", "CALL", "/"));
		// Deployed and measured, but no source at v0.1.0 connects to them (ADR-033): services with no edge.
		for (String store : List.of("ts-food-mysql", "ts-notification-mongo", "ts-preserve-mongo",
				"ts-preserve-other-mongo")) {
			assertThat(tt.calls(store)).as(store).isEmpty();
			assertThat(tt.calledBy(store)).as(store).isEmpty();
		}
		assertThat(tt.statusTables()).isEmpty();
		assertThat(tt.eventSources()).isEmpty();
	}

	@Test
	void theRe2TtServicesAreTheNormalizedMetricPrefixes() {
		// The prefixes of the simple_metrics.csv headers of RE2-TT (the union over its 90 files, header lines only).
		List<String> prefixes = List.of("istio-init", "ts-admin-basic-info-service", "ts-admin-order-service",
				"ts-admin-route-service", "ts-admin-travel-service", "ts-admin-user-service", "ts-assurance-mongo",
				"ts-assurance-service", "ts-auth-mongo", "ts-auth-service", "ts-avatar-service", "ts-basic-service",
				"ts-cancel-service", "ts-config-mongo", "ts-config-service", "ts-consign-mongo",
				"ts-consign-price-mongo", "ts-consign-price-service", "ts-consign-service", "ts-contacts-mongo",
				"ts-contacts-service", "ts-execute-service", "ts-food-map-mongo", "ts-food-map-service", "ts-food-mongo",
				"ts-food-mysql", "ts-food-service", "ts-inside-payment-mongo", "ts-inside-payment-service",
				"ts-news-service", "ts-notification-mongo", "ts-notification-service", "ts-order-mongo",
				"ts-order-other-mongo", "ts-order-other-service", "ts-order-service", "ts-payment-mongo",
				"ts-payment-service", "ts-preserve-mongo", "ts-preserve-other-mongo", "ts-preserve-other-service",
				"ts-preserve-service", "ts-price-mongo", "ts-price-service", "ts-rebook-service", "ts-route-mongo",
				"ts-route-plan-service", "ts-route-service", "ts-seat-service", "ts-security-mongo",
				"ts-security-service", "ts-station-mongo", "ts-station-service", "ts-ticket-office-mongo",
				"ts-ticket-office-service", "ts-ticketinfo-service", "ts-train-mongo", "ts-train-service",
				"ts-travel-mongo", "ts-travel-plan-service", "ts-travel-service", "ts-travel2-mongo",
				"ts-travel2-service", "ts-ui-dashboard", "ts-user-mongo", "ts-user-service",
				"ts-verification-code-service", "ts-voucher-mysql", "ts-voucher-service");
		assertThat(Topology.load(new ClassPathResource("topology/re2-tt.json")).services())
			.isEqualTo(displayOrder("ts-ui-dashboard", normalized(prefixes)));
	}

	/**
	 * The services RCAEval's evaluator names for {@code prefixes} ({@code to_service}: the part before the first
	 * underscore, {@code -db} folded), minus the prefixes that are no component of the application: a Kubernetes
	 * Service alias of the frontend, Istio's init container and a Prometheus exporter sidecar.
	 */
	private static List<String> normalized(List<String> prefixes) {
		List<String> notComponents = List.of("frontend-external", "istio-init", "rabbitmq-exporter");
		return prefixes.stream()
			.filter(prefix -> !notComponents.contains(prefix))
			.map(prefix -> prefix.split("_")[0].replace("-db", ""))
			.distinct()
			.toList();
	}

	/** The display order of the RCAEval files: the entry service first, then the others alphabetically. */
	private static List<String> displayOrder(String entry, List<String> services) {
		return Stream
			.concat(Stream.of(entry), services.stream().filter(s -> !s.equals(entry)).sorted())
			.toList();
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
