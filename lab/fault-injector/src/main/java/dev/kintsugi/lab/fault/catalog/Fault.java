package dev.kintsugi.lab.fault.catalog;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import dev.kintsugi.lab.fault.catalog.ParameterRefusedException.Reason;

/**
 * The fixed fault catalog and the ground truth of each fault (openspec/specs/fault-injector/spec.md, Catalog).
 *
 * <p>
 * Every field except {@link #id()} is part of the answer the agent must find: the target service is also the
 * root-cause service. Only the id ever leaves the injector through HTTP. Parameters of F3 and F4 use the compose
 * variable names, which the injector sets; {@code infra/compose.yaml} maps them to the container variables
 * ({@code ORDER_PAYMENT_BASEURL} to {@code KINTSUGI_PAYMENT_BASEURL}, and so on; see ADR-009).
 *
 * <p>
 * F1 and F2 also declare the values a caller may choose ({@link DeclaredParameter}), so a schedule can draw them
 * (lab-realism-min, ADR-026). The declared values are code too: a value outside them is refused. Without a choice each
 * fault applies its {@link #parameters()}, which are one of the declared values. A choice changes the parameters and the
 * root-cause sentence, never the root-cause service or category.
 */
public enum Fault {

	F1("gateway-mock", LabMechanism.GATEWAY_BEHAVIOR, RootCauseCategory.DEPENDENCY_LATENCY,
			applied -> "The payment gateway answers authorizations after " + seconds(applied.get("latencyMs"))
					+ " s, so payments and the orders waiting on them stay pending.",
			Map.of("latencyMs", 10_000),
			List.of(new DeclaredParameter("gatewayLatencyMs", "latencyMs", List.of(2_000, 5_000, 10_000, 20_000)))),

	F2("gateway-mock", LabMechanism.GATEWAY_BEHAVIOR, RootCauseCategory.DEPENDENCY_REJECTING,
			applied -> "The payment gateway rejects every authorization with " + applied.get("errorStatus") + " ("
					+ errorMeaning(applied.get("errorStatus")) + "), so payments fail.",
			Map.of("latencyMs", 0, "errorStatus", 429),
			List.of(new DeclaredParameter("gatewayErrorStatus", "errorStatus", List.of(429, 500, 503)))),

	F3("order-service", LabMechanism.COMPOSE_RECREATE, RootCauseCategory.CONFIGURATION,
			applied -> "order-service is deployed with a wrong payment-service URL (http://payment-service:9999), so its payment calls fail to connect.",
			Map.of("ORDER_PAYMENT_BASEURL", "http://payment-service:9999"), List.of()),

	F4("inventory-service", LabMechanism.COMPOSE_RECREATE, RootCauseCategory.RESOURCE_EXHAUSTION,
			applied -> "inventory-service runs with a database pool of 2 and reservations that hold a connection for 5 s, so the pool is exhausted and reservations fail.",
			Map.of("INVENTORY_POOL_SIZE", "2", "INVENTORY_RESERVATION_DELAY", "5s"), List.of());

	private final String rootCauseService;

	private final LabMechanism mechanism;

	private final RootCauseCategory rootCauseCategory;

	private final Function<Map<String, Object>, String> rootCause;

	private final Map<String, Object> parameters;

	private final List<DeclaredParameter> declaredParameters;

	Fault(String rootCauseService, LabMechanism mechanism, RootCauseCategory rootCauseCategory,
			Function<Map<String, Object>, String> rootCause, Map<String, Object> parameters,
			List<DeclaredParameter> declaredParameters) {
		this.rootCauseService = rootCauseService;
		this.mechanism = mechanism;
		this.rootCauseCategory = rootCauseCategory;
		this.rootCause = rootCause;
		this.parameters = parameters;
		this.declaredParameters = declaredParameters;
	}

	/** The catalog fault with exactly this id ({@code "F1"} … {@code "F4"}), or empty. */
	public static Optional<Fault> byId(String id) {
		return Arrays.stream(values()).filter(fault -> fault.id().equals(id)).findFirst();
	}

	public String id() {
		return name();
	}

	/** The service the injector acts on. For F1–F4 it is also {@link #rootCauseService()}. */
	public String targetService() {
		return rootCauseService;
	}

	/** How the injector applies and undoes this fault on the lab. */
	public LabMechanism mechanism() {
		return mechanism;
	}

	public String rootCauseService() {
		return rootCauseService;
	}

	public RootCauseCategory rootCauseCategory() {
		return rootCauseCategory;
	}

	/** One sentence, the true cause as an SRE would write it in a postmortem, for the catalog parameters. */
	public String rootCause() {
		return rootCause(parameters);
	}

	/** The true-cause sentence for the parameters {@link #applied} returned. */
	public String rootCause(Map<String, Object> applied) {
		return rootCause.apply(applied);
	}

	/**
	 * The catalog injection parameters, applied when a caller chooses none: gateway-mock behavior for F1 and F2,
	 * compose variables for F3 and F4.
	 */
	public Map<String, Object> parameters() {
		return parameters;
	}

	/** The parameters a caller may choose, with their declared values; empty for F3 and F4. */
	public List<DeclaredParameter> declaredParameters() {
		return declaredParameters;
	}

	/**
	 * The parameters to apply: the catalog {@link #parameters()}, with each requested value in place of its field.
	 * @param requested parameter names to values, as the caller sent them; empty for the catalog parameters
	 * @throws ParameterRefusedException a name this fault does not declare, or a value outside its declared values
	 */
	public Map<String, Object> applied(Map<String, ?> requested) {
		Map<String, Object> applied = new LinkedHashMap<>(parameters);
		for (Map.Entry<String, ?> choice : requested.entrySet()) {
			DeclaredParameter declared = declaredParameters.stream()
				.filter(parameter -> parameter.name().equals(choice.getKey()))
				.findFirst()
				.orElseThrow(() -> new ParameterRefusedException(Reason.UNDECLARED_PARAMETER));
			if (!declared.declares(choice.getValue())) {
				throw new ParameterRefusedException(Reason.UNDECLARED_VALUE);
			}
			applied.put(declared.behaviorField(), choice.getValue());
		}
		return Map.copyOf(applied);
	}

	private static long seconds(Object milliseconds) {
		return ((Number) milliseconds).longValue() / 1000;
	}

	/** What each of F2's declared statuses means, for its root-cause sentence. */
	private static String errorMeaning(Object status) {
		return switch (((Number) status).intValue()) {
			case 429 -> "rate limited";
			case 500 -> "internal server error";
			case 503 -> "service unavailable";
			default -> throw new IllegalArgumentException("undeclared error status " + status);
		};
	}

}
