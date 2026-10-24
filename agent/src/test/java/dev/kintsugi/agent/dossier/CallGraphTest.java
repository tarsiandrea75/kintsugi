package dev.kintsugi.agent.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.dossier.CallGraph.Edge;

/**
 * The lab's call graph as data (odd/tasks/service-assessment.md T1): the one list of edges the tables and the sheets
 * render, the services leaf first, who calls whom, and the service that owns each state table and event source.
 */
class CallGraphTest {

	@Test
	void theEdgesAreTheCallPathTheTablesAlwaysRendered() {
		assertThat(CallGraph.EDGES).containsExactly(new Edge(null, "order-service", "POST", "/orders"),
				new Edge("order-service", "inventory-service", "POST", "/reservations"),
				new Edge("order-service", "payment-service", "POST", "/payments"),
				new Edge("payment-service", "gateway-mock", "POST", "/authorize"));
	}

	@Test
	void theServicesComeLeafFirstAndAreTheOnesTheGathererReads() {
		assertThat(CallGraph.SERVICES).containsExactly("gateway-mock", "inventory-service", "payment-service",
				"order-service");
		assertThat(CallGraph.SERVICES).containsExactlyInAnyOrderElementsOf(DossierGatherer.SERVICES);
		// Leaf first: every service comes after the services it calls.
		for (int i = 0; i < CallGraph.SERVICES.size(); i++) {
			for (String callee : CallGraph.calls(CallGraph.SERVICES.get(i))) {
				assertThat(CallGraph.SERVICES.indexOf(callee)).as("%s calls %s", CallGraph.SERVICES.get(i), callee)
					.isBetween(0, i - 1);
			}
		}
		for (Edge edge : CallGraph.EDGES) {
			assertThat(CallGraph.SERVICES).contains(edge.target());
			if (edge.caller() != null) {
				assertThat(CallGraph.SERVICES).contains(edge.caller());
			}
		}
	}

	@Test
	void eachServiceCallsAndIsCalledByItsNeighboursInEdgeOrder() {
		assertThat(CallGraph.calls("order-service")).containsExactly("inventory-service", "payment-service");
		assertThat(CallGraph.calls("payment-service")).containsExactly("gateway-mock");
		assertThat(CallGraph.calls("inventory-service")).isEmpty();
		assertThat(CallGraph.calls("gateway-mock")).isEmpty();
		assertThat(CallGraph.calledBy("gateway-mock")).containsExactly("payment-service");
		assertThat(CallGraph.calledBy("payment-service")).containsExactly("order-service");
		assertThat(CallGraph.calledBy("inventory-service")).containsExactly("order-service");
		// The first edge's caller is outside the lab.
		assertThat(CallGraph.calledBy("order-service")).containsExactly(CallGraph.EXTERNAL_CLIENT);
		assertThat(CallGraph.calls("postgres")).isEmpty();
		assertThat(CallGraph.calledBy("postgres")).isEmpty();
	}

	@Test
	void everyGatheredTableAndEventSourceHasItsOwnerByName() {
		assertThat(CallGraph.ownerOfTable("orders")).isEqualTo("order-service");
		assertThat(CallGraph.ownerOfTable("payments")).isEqualTo("payment-service");
		assertThat(CallGraph.ownerOfSource("order")).isEqualTo("order-service");
		assertThat(CallGraph.ownerOfSource("payment")).isEqualTo("payment-service");
		assertThat(CallGraph.ownerOfSource("reservation")).isEqualTo("inventory-service");
		assertThat(DossierGatherer.STATUS_TABLES).allSatisfy(
				table -> assertThat(CallGraph.SERVICES).contains(CallGraph.ownerOfTable(table)));
		assertThat(DossierGatherer.EVENT_SOURCES).allSatisfy(
				source -> assertThat(CallGraph.SERVICES).contains(CallGraph.ownerOfSource(source)));
		assertThatThrownBy(() -> CallGraph.ownerOfTable("stock")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("stock");
		assertThatThrownBy(() -> CallGraph.ownerOfSource("inventory")).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inventory");
	}

}
