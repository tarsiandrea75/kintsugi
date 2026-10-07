package dev.kintsugi.lab.fault.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** The catalog is the ground truth: these values are what every diagnosis is scored against (spec, Catalog). */
class FaultTest {

	@Test
	void catalogHoldsF1ToF4InIdOrder() {
		assertThat(Fault.values()).extracting(Fault::id).containsExactly("F1", "F2", "F3", "F4");
	}

	@Test
	void eachFaultCarriesItsStructuredTrueCause() {
		assertThat(Fault.F1.rootCauseService()).isEqualTo("gateway-mock");
		assertThat(Fault.F1.rootCauseCategory()).isEqualTo(RootCauseCategory.DEPENDENCY_LATENCY);
		assertThat(Fault.F2.rootCauseService()).isEqualTo("gateway-mock");
		assertThat(Fault.F2.rootCauseCategory()).isEqualTo(RootCauseCategory.DEPENDENCY_REJECTING);
		assertThat(Fault.F3.rootCauseService()).isEqualTo("order-service");
		assertThat(Fault.F3.rootCauseCategory()).isEqualTo(RootCauseCategory.CONFIGURATION);
		assertThat(Fault.F4.rootCauseService()).isEqualTo("inventory-service");
		assertThat(Fault.F4.rootCauseCategory()).isEqualTo(RootCauseCategory.RESOURCE_EXHAUSTION);
		assertThat(Fault.values()).allSatisfy(fault -> {
			assertThat(fault.targetService()).isEqualTo(fault.rootCauseService());
			assertThat(fault.rootCause()).isNotBlank();
		});
	}

	/** F3 and F4 use the compose variable names, which the injector sets; compose maps them to container variables. */
	@Test
	void parametersMatchTheCatalog() {
		assertThat(Fault.F1.parameters()).isEqualTo(Map.of("latencyMs", 10_000));
		assertThat(Fault.F2.parameters()).isEqualTo(Map.of("latencyMs", 0, "errorStatus", 429));
		assertThat(Fault.F3.parameters()).isEqualTo(Map.of("ORDER_PAYMENT_BASEURL", "http://payment-service:9999"));
		assertThat(Fault.F4.parameters())
			.isEqualTo(Map.of("INVENTORY_POOL_SIZE", "2", "INVENTORY_RESERVATION_DELAY", "5s"));
	}

	@Test
	void lookupByIdIsExactAndEmptyForUnknownIds() {
		assertThat(Fault.byId("F3")).contains(Fault.F3);
		assertThat(Fault.byId("F9")).isEmpty();
		assertThat(Fault.byId("f1")).isEmpty();
		assertThat(Fault.byId(null)).isEmpty();
	}

}
