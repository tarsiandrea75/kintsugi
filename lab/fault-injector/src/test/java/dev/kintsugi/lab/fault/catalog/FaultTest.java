package dev.kintsugi.lab.fault.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.kintsugi.lab.fault.catalog.ParameterRefusedException.Reason;

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

	@Test
	void gatewayFaultsUseTheAdminApiAndDeployFaultsARecreate() {
		assertThat(Fault.F1.mechanism()).isEqualTo(LabMechanism.GATEWAY_BEHAVIOR);
		assertThat(Fault.F2.mechanism()).isEqualTo(LabMechanism.GATEWAY_BEHAVIOR);
		assertThat(Fault.F3.mechanism()).isEqualTo(LabMechanism.COMPOSE_RECREATE);
		assertThat(Fault.F4.mechanism()).isEqualTo(LabMechanism.COMPOSE_RECREATE);
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

	/** The declared values of lab-realism-min's design table: F1's gateway latency and F2's error status only. */
	@Test
	void onlyF1AndF2DeclareParametersWithTheDesignValues() {
		assertThat(Fault.F1.declaredParameters()).singleElement().satisfies(parameter -> {
			assertThat(parameter.name()).isEqualTo("gatewayLatencyMs");
			assertThat(parameter.values()).containsExactly(2_000, 5_000, 10_000, 20_000);
		});
		assertThat(Fault.F2.declaredParameters()).singleElement().satisfies(parameter -> {
			assertThat(parameter.name()).isEqualTo("gatewayErrorStatus");
			assertThat(parameter.values()).containsExactly(429, 500, 503);
		});
		assertThat(Fault.F3.declaredParameters()).isEmpty();
		assertThat(Fault.F4.declaredParameters()).isEmpty();
	}

	/** A request without parameters applies today's constants, so the default is one of the declared values. */
	@Test
	void withoutParametersEveryFaultAppliesItsCatalogParametersAndSentence() {
		assertThat(Fault.values()).allSatisfy(fault -> {
			assertThat(fault.applied(Map.of())).isEqualTo(fault.parameters());
			assertThat(fault.rootCause(fault.applied(Map.of()))).isEqualTo(fault.rootCause());
			fault.declaredParameters()
				.forEach(parameter -> assertThat(parameter.values())
					.contains((Integer) fault.parameters().get(parameter.behaviorField())));
		});
		assertThat(Fault.F1.rootCause()).isEqualTo(
				"The payment gateway answers authorizations after 10 s, so payments and the orders waiting on them stay pending.");
		assertThat(Fault.F2.rootCause())
			.isEqualTo("The payment gateway rejects every authorization with 429 (rate limited), so payments fail.");
	}

	@ParameterizedTest
	@ValueSource(ints = { 2_000, 5_000, 10_000, 20_000 })
	void eachDeclaredF1LatencyIsApplied(int latencyMs) {
		Map<String, Object> applied = Fault.F1.applied(Map.of("gatewayLatencyMs", latencyMs));

		assertThat(applied).isEqualTo(Map.of("latencyMs", latencyMs));
		assertThat(Fault.F1.rootCause(applied)).contains("after " + latencyMs / 1000 + " s,");
	}

	@ParameterizedTest
	@ValueSource(ints = { 429, 500, 503 })
	void eachDeclaredF2StatusIsApplied(int status) {
		Map<String, Object> applied = Fault.F2.applied(Map.of("gatewayErrorStatus", status));

		assertThat(applied).isEqualTo(Map.of("latencyMs", 0, "errorStatus", status));
		assertThat(Fault.F2.rootCause(applied)).contains("with " + status + " (");
	}

	static Stream<Arguments> refusals() {
		Map<String, Object> nullValue = new HashMap<>();
		nullValue.put("gatewayLatencyMs", null);
		return Stream.of(Arguments.of(Fault.F1, Map.of("gatewayLatencyMs", 3_000), Reason.UNDECLARED_VALUE),
				Arguments.of(Fault.F1, Map.of("gatewayLatencyMs", 0), Reason.UNDECLARED_VALUE),
				Arguments.of(Fault.F1, Map.of("gatewayLatencyMs", "5000"), Reason.UNDECLARED_VALUE),
				Arguments.of(Fault.F1, Map.of("gatewayLatencyMs", 5000.0), Reason.UNDECLARED_VALUE),
				Arguments.of(Fault.F1, nullValue, Reason.UNDECLARED_VALUE),
				Arguments.of(Fault.F2, Map.of("gatewayErrorStatus", 404), Reason.UNDECLARED_VALUE),
				Arguments.of(Fault.F2, Map.of("gatewayErrorStatus", List.of(429)), Reason.UNDECLARED_VALUE),
				Arguments.of(Fault.F1, Map.of("gatewayErrorStatus", 500), Reason.UNDECLARED_PARAMETER),
				Arguments.of(Fault.F2, Map.of("gatewayLatencyMs", 5_000), Reason.UNDECLARED_PARAMETER),
				Arguments.of(Fault.F1, Map.of("latencyMs", 5_000), Reason.UNDECLARED_PARAMETER),
				Arguments.of(Fault.F1, Map.of("gatewayLatencyMs", 5_000, "speed", 1), Reason.UNDECLARED_PARAMETER),
				Arguments.of(Fault.F3, Map.of("gatewayLatencyMs", 5_000), Reason.UNDECLARED_PARAMETER),
				Arguments.of(Fault.F3, Map.of("ORDER_PAYMENT_BASEURL", "http://payment-service:1"),
						Reason.UNDECLARED_PARAMETER),
				Arguments.of(Fault.F4, Map.of("INVENTORY_POOL_SIZE", "1"), Reason.UNDECLARED_PARAMETER));
	}

	@ParameterizedTest
	@MethodSource("refusals")
	void aParameterOutsideTheDeclaredOnesIsRefused(Fault fault, Map<String, Object> requested, Reason reason) {
		assertThatExceptionOfType(ParameterRefusedException.class).isThrownBy(() -> fault.applied(requested))
			.satisfies(refused -> assertThat(refused.reason()).isEqualTo(reason));
	}

}
