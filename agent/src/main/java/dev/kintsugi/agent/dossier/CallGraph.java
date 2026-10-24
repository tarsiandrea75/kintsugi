package dev.kintsugi.agent.dossier;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.kintsugi.agent.dossier.Dossier.TrafficRow;

/**
 * The lab's call graph as data, the one source of it in code (odd/tasks/service-assessment.md T1): the services, the
 * edges between them, and the service that owns each state table and event source. The tables render its edges
 * ({@link DossierRenderer}), the sheets its services and owners ({@link ServiceSheets}). It states the lab's topology,
 * never a cause the lab may be put through.
 */
public final class CallGraph {

	/** How a caller from outside the lab is named: the first edge's, the shop's clients calling order-service. */
	public static final String EXTERNAL_CLIENT = "external client";

	/**
	 * The lab services in sheet order, leaf first: each comes after the services it calls. The gatherer reads the same
	 * four in the order of the call path ({@link DossierGatherer#SERVICES}).
	 */
	public static final List<String> SERVICES = List.of("gateway-mock", "inventory-service", "payment-service",
			"order-service");

	/**
	 * The traffic edges, in the order of the call path (ADR-011, "Sections"). The first has no caller in the lab, so the
	 * tables write only its server side.
	 */
	public static final List<Edge> EDGES = List.of(new Edge(null, "order-service", "POST", "/orders"),
			new Edge("order-service", "inventory-service", "POST", "/reservations"),
			new Edge("order-service", "payment-service", "POST", "/payments"),
			new Edge("payment-service", "gateway-mock", "POST", "/authorize"));

	/** The {@code status_summary} tables by owner, matched by name: the service that writes them. */
	private static final Map<String, String> TABLE_OWNERS = Map.of("orders", "order-service", "payments",
			"payment-service");

	/** The {@code event_counts} sources by owner, matched by name: the service whose events they are. */
	private static final Map<String, String> SOURCE_OWNERS = Map.of("order", "order-service", "payment",
			"payment-service", "reservation", "inventory-service");

	private CallGraph() {
	}

	/** The services {@code service} calls, in edge order; none for a leaf or a name outside the lab. */
	public static List<String> calls(String service) {
		return EDGES.stream().filter(edge -> service.equals(edge.caller())).map(Edge::target).toList();
	}

	/**
	 * The callers of {@code service}, in edge order: lab services, and {@link #EXTERNAL_CLIENT} for the edge whose
	 * caller is outside the lab; none for a name outside the lab.
	 */
	public static List<String> calledBy(String service) {
		return EDGES.stream()
			.filter(edge -> edge.target().equals(service))
			.map(edge -> edge.caller() == null ? EXTERNAL_CLIENT : edge.caller())
			.toList();
	}

	/**
	 * The service that owns the {@code status_summary} table {@code table}.
	 * @throws IllegalArgumentException for a table the gatherer does not read ({@link DossierGatherer#STATUS_TABLES})
	 */
	public static String ownerOfTable(String table) {
		return owner(TABLE_OWNERS, table, "table");
	}

	/**
	 * The service that owns the {@code event_counts} source {@code source}.
	 * @throws IllegalArgumentException for a source the gatherer does not read ({@link DossierGatherer#EVENT_SOURCES})
	 */
	public static String ownerOfSource(String source) {
		return owner(SOURCE_OWNERS, source, "event source");
	}

	private static String owner(Map<String, String> owners, String name, String what) {
		String owner = name == null ? null : owners.get(name);
		if (owner == null) {
			throw new IllegalArgumentException("no lab service owns the " + what + " \"" + name + "\"");
		}
		return owner;
	}

	/**
	 * One traffic edge: the caller's client calls and the target's server requests with this method and uri.
	 *
	 * @param caller the calling lab service, or {@code null} when the caller is outside the lab
	 */
	public record Edge(String caller, String target, String method, String uri) {

		public Edge {
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(method, "method");
			Objects.requireNonNull(uri, "uri");
		}

		/** Whether {@code row} is this edge's caller-side row: the caller's client call to the target. */
		boolean callerRow(TrafficRow row) {
			return this.caller != null && "client".equals(row.side()) && this.caller.equals(row.service())
					&& this.target.equals(row.clientName()) && this.method.equals(row.method())
					&& this.uri.equals(row.uri());
		}

		/** Whether {@code row} is this edge's server-side row: the target's answer to this method and uri. */
		boolean serverRow(TrafficRow row) {
			return "server".equals(row.side()) && this.target.equals(row.service()) && this.method.equals(row.method())
					&& this.uri.equals(row.uri());
		}

	}

}
