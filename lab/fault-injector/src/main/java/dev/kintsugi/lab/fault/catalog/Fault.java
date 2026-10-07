package dev.kintsugi.lab.fault.catalog;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * The fixed fault catalog and the ground truth of each fault (openspec/specs/fault-injector/spec.md, Catalog).
 *
 * <p>
 * Every field except {@link #id()} is part of the answer the agent must find: the target service is also the
 * root-cause service. Only the id ever leaves the injector through HTTP. Parameters of F3 and F4 use the compose
 * variable names, which the injector sets; {@code infra/compose.yaml} maps them to the container variables
 * ({@code ORDER_PAYMENT_BASEURL} to {@code KINTSUGI_PAYMENT_BASEURL}, and so on; see ADR-009).
 */
public enum Fault {

	F1("gateway-mock", RootCauseCategory.DEPENDENCY_LATENCY,
			"The payment gateway answers authorizations after 10 s, so payments and the orders waiting on them stay pending.",
			Map.of("latencyMs", 10_000)),

	F2("gateway-mock", RootCauseCategory.DEPENDENCY_REJECTING,
			"The payment gateway rejects every authorization with 429 (rate limited), so payments fail.",
			Map.of("latencyMs", 0, "errorStatus", 429)),

	F3("order-service", RootCauseCategory.CONFIGURATION,
			"order-service is deployed with a wrong payment-service URL (http://payment-service:9999), so its payment calls fail to connect.",
			Map.of("ORDER_PAYMENT_BASEURL", "http://payment-service:9999")),

	F4("inventory-service", RootCauseCategory.RESOURCE_EXHAUSTION,
			"inventory-service runs with a database pool of 2 and reservations that hold a connection for 5 s, so the pool is exhausted and reservations fail.",
			Map.of("INVENTORY_POOL_SIZE", "2", "INVENTORY_RESERVATION_DELAY", "5s"));

	private final String rootCauseService;

	private final RootCauseCategory rootCauseCategory;

	private final String rootCause;

	private final Map<String, Object> parameters;

	Fault(String rootCauseService, RootCauseCategory rootCauseCategory, String rootCause,
			Map<String, Object> parameters) {
		this.rootCauseService = rootCauseService;
		this.rootCauseCategory = rootCauseCategory;
		this.rootCause = rootCause;
		this.parameters = parameters;
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

	public String rootCauseService() {
		return rootCauseService;
	}

	public RootCauseCategory rootCauseCategory() {
		return rootCauseCategory;
	}

	/** One sentence, the true cause as an SRE would write it in a postmortem. */
	public String rootCause() {
		return rootCause;
	}

	/** The injection parameters: gateway-mock behavior for F1 and F2, compose variables for F3 and F4. */
	public Map<String, Object> parameters() {
		return parameters;
	}

}
