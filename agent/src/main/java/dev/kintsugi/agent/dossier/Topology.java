package dev.kintsugi.agent.dossier;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.core.io.Resource;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.dossier.Dossier.TrafficRow;

/**
 * A system's topology as data (odd/tasks/topology-data.md): its services in display order, its datastores, the
 * traffic edges between the services, and the service that owns each state table and event source. It is read from
 * one file ({@code topology/lab.json} for the lab) and checked when it loads, so an unknown name, a cycle or a
 * duplicate fails at startup, never later in a run. The orders the code uses are derived, not listed: another
 * system's file then needs only its names and edges.
 *
 * @param services the services in display order
 * @param datastores the datastores, which no edge targets and no table or source belongs to
 * @param edges the traffic edges in call-path order; a {@code null} caller is outside the system
 * @param tableOwners the {@code status_summary} tables and their owners, in the order the gatherer reads them
 * @param sourceOwners the {@code event_counts} sources and their owners, in the order the gatherer reads them
 */
public record Topology(List<String> services, List<String> datastores, List<Edge> edges,
		Map<String, String> tableOwners, Map<String, String> sourceOwners) {

	/**
	 * The property that names the topology file, a Spring resource location; the agent loads it once, at startup
	 * ({@code TopologyConfig}).
	 */
	public static final String PROPERTY = "kintsugi.agent.topology";

	/** The property's value when it is not set: the lab's file. */
	public static final String DEFAULT_LOCATION = "classpath:topology/lab.json";

	/** How a caller from outside the system is named: the caller of an edge whose {@code caller} is null. */
	public static final String EXTERNAL_CLIENT = "external client";

	private static final JsonMapper JSON = JsonMapper.shared();

	/** The report's cause for a component it cannot name, after the services and datastores. */
	private static final String UNKNOWN = "UNKNOWN";

	public Topology {
		services = List.copyOf(services);
		datastores = List.copyOf(datastores);
		edges = List.copyOf(edges);
		// Map.copyOf would lose the file order, which statusTables and eventSources keep.
		tableOwners = Collections.unmodifiableMap(new LinkedHashMap<>(tableOwners));
		sourceOwners = Collections.unmodifiableMap(new LinkedHashMap<>(sourceOwners));
		validate(services, datastores, edges, tableOwners, sourceOwners);
	}

	/**
	 * The topology in {@code resource}; an {@link IllegalArgumentException} naming the problem when the file is missing,
	 * unreadable or invalid.
	 */
	public static Topology load(Resource resource) {
		if (!resource.exists()) {
			throw new IllegalArgumentException("topology file not found: " + resource.getDescription());
		}
		try (InputStream in = resource.getInputStream()) {
			return load(in, resource.getDescription());
		}
		catch (IOException e) {
			throw new IllegalArgumentException("cannot read topology " + resource.getDescription(), e);
		}
	}

	/** The topology in {@code in}; {@code origin} names the file in the error of an invalid one. */
	public static Topology load(InputStream in, String origin) {
		try {
			JsonNode root = JSON.readTree(in);
			if (root == null || root.isMissingNode()) {
				throw new IllegalArgumentException("the file is empty");
			}
			if (!root.isObject()) {
				throw new IllegalArgumentException("the file must hold a JSON object");
			}
			List<Edge> edges = new ArrayList<>();
			int n = 0;
			for (JsonNode edge : field(root, "edges", JsonNode::isArray, "an array")) {
				n++;
				if (!edge.isObject()) {
					throw new IllegalArgumentException("edge " + n + " is not an object");
				}
				JsonNode caller = edge.path("caller");
				if (caller.isMissingNode()) {
					throw new IllegalArgumentException(
							"edge " + n + ": missing field 'caller' (null for the external client)");
				}
				edges.add(new Edge(caller.isNull() ? null : text(caller, "caller"), text(edge.path("target"), "target"),
						text(edge.path("method"), "method"), text(edge.path("uri"), "uri")));
			}
			return new Topology(names(root, "services"), names(root, "datastores"), edges, owners(root, "tableOwners"),
					owners(root, "sourceOwners"));
		}
		catch (JacksonException | IllegalArgumentException e) {
			throw new IllegalArgumentException("invalid topology " + origin + ": " + e.getMessage(), e);
		}
	}

	/** The services leaf first: by depth, the longest path to a leaf over the edges, ties in display order. */
	public List<String> leafFirst() {
		Map<String, Integer> depths = new HashMap<>();
		this.services.forEach(service -> depth(service, this.edges, depths, new ArrayList<>()));
		return this.services.stream().sorted(Comparator.comparingInt(depths::get)).toList();
	}

	/** The services in the order the gatherer reads them, down the call path: {@link #leafFirst()} reversed. */
	public List<String> gatherOrder() {
		return leafFirst().reversed();
	}

	/** The services {@code service} calls, in edge order; none for a leaf or a name outside the system. */
	public List<String> calls(String service) {
		return this.edges.stream().filter(edge -> service.equals(edge.caller())).map(Edge::target).toList();
	}

	/**
	 * The callers of {@code service}, in edge order: services, and {@link #EXTERNAL_CLIENT} for an edge whose
	 * caller is outside the system; none for a name outside the system.
	 */
	public List<String> calledBy(String service) {
		return this.edges.stream()
			.filter(edge -> edge.target().equals(service))
			.map(edge -> edge.caller() == null ? EXTERNAL_CLIENT : edge.caller())
			.toList();
	}

	/** The service that owns the {@code status_summary} table {@code table}; none for a name no service owns. */
	public Optional<String> ownerOfTable(String table) {
		return Optional.ofNullable(table == null ? null : this.tableOwners.get(table));
	}

	/** The service that owns the {@code event_counts} source {@code source}; none for a name no service owns. */
	public Optional<String> ownerOfSource(String source) {
		return Optional.ofNullable(source == null ? null : this.sourceOwners.get(source));
	}

	/** The {@code status_summary} tables the gatherer reads, in file order. */
	public List<String> statusTables() {
		return List.copyOf(this.tableOwners.keySet());
	}

	/** The {@code event_counts} sources the gatherer reads, in file order. */
	public List<String> eventSources() {
		return List.copyOf(this.sourceOwners.keySet());
	}

	/** The report's cause enum: the services in display order, the datastores, then {@code UNKNOWN}. */
	public List<String> causes() {
		List<String> causes = new ArrayList<>(this.services);
		causes.addAll(this.datastores);
		causes.add(UNKNOWN);
		return List.copyOf(causes);
	}

	private static void validate(List<String> services, List<String> datastores, List<Edge> edges,
			Map<String, String> tableOwners, Map<String, String> sourceOwners) {
		if (services.isEmpty()) {
			throw new IllegalArgumentException("no services");
		}
		Set<String> names = new HashSet<>();
		for (String name : concat(services, datastores)) {
			if (UNKNOWN.equals(name)) {
				throw new IllegalArgumentException(
						"'" + UNKNOWN + "' is the report's unknown cause, not a component name");
			}
			if (!names.add(name)) {
				throw new IllegalArgumentException("duplicate name '" + name + "'");
			}
		}
		for (Edge edge : edges) {
			if (edge.caller() != null && !services.contains(edge.caller())) {
				throw new IllegalArgumentException("edge caller '" + edge.caller() + "' is not a service");
			}
			if (!services.contains(edge.target())) {
				throw new IllegalArgumentException("edge target '" + edge.target() + "' is not a service");
			}
		}
		owned(tableOwners, "table", services);
		owned(sourceOwners, "source", services);
		// The depths walk every path, so a cycle fails here, before leafFirst ever reads them.
		Map<String, Integer> depths = new HashMap<>();
		services.forEach(service -> depth(service, edges, depths, new ArrayList<>()));
	}

	private static void owned(Map<String, String> owners, String kind, List<String> services) {
		owners.forEach((name, owner) -> {
			if (!services.contains(owner)) {
				throw new IllegalArgumentException(
						"owner '" + owner + "' of " + kind + " '" + name + "' is not a service");
			}
		});
	}

	/** The longest path from {@code service} to a leaf; {@code path} is the walk so far, to name a cycle. */
	private static int depth(String service, List<Edge> edges, Map<String, Integer> depths, List<String> path) {
		Integer known = depths.get(service);
		if (known != null) {
			return known;
		}
		if (path.contains(service)) {
			List<String> cycle = new ArrayList<>(path.subList(path.indexOf(service), path.size()));
			cycle.add(service);
			throw new IllegalArgumentException("cycle: " + String.join(" -> ", cycle));
		}
		path.add(service);
		int depth = 0;
		for (Edge edge : edges) {
			if (service.equals(edge.caller())) {
				depth = Math.max(depth, depth(edge.target(), edges, depths, path) + 1);
			}
		}
		path.removeLast();
		depths.put(service, depth);
		return depth;
	}

	private static List<String> concat(List<String> first, List<String> second) {
		List<String> all = new ArrayList<>(first);
		all.addAll(second);
		return all;
	}

	/** The field {@code name} of {@code root}, which must be {@code type}: {@code kind} names the type in the error. */
	private static JsonNode field(JsonNode root, String name, Predicate<JsonNode> type, String kind) {
		JsonNode node = root.path(name);
		if (node.isMissingNode() || node.isNull()) {
			throw new IllegalArgumentException("missing field '" + name + "'");
		}
		if (!type.test(node)) {
			throw new IllegalArgumentException("'" + name + "' must be " + kind);
		}
		return node;
	}

	private static List<String> names(JsonNode root, String field) {
		List<String> names = new ArrayList<>();
		for (JsonNode name : field(root, field, JsonNode::isArray, "an array of names")) {
			names.add(text(name, field));
		}
		return names;
	}

	private static Map<String, String> owners(JsonNode root, String field) {
		Map<String, String> owners = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : field(root, field, JsonNode::isObject, "an object of owners")
			.properties()) {
			owners.put(entry.getKey(), text(entry.getValue(), field + "." + entry.getKey()));
		}
		return owners;
	}

	private static String text(JsonNode node, String what) {
		if (!node.isString()) {
			throw new IllegalArgumentException("'" + what + "' is not a string");
		}
		return node.asString();
	}

	/**
	 * One traffic edge: the caller's client calls and the target's server requests with this method and uri.
	 *
	 * @param caller the calling service, or {@code null} when the caller is outside the system
	 */
	public record Edge(String caller, String target, String method, String uri) {

		public Edge {
			Objects.requireNonNull(target, "target");
			Objects.requireNonNull(method, "method");
			Objects.requireNonNull(uri, "uri");
		}

		/** Whether {@code row} is this edge's caller-side row: the caller's client call to the target. */
		public boolean callerRow(TrafficRow row) {
			return this.caller != null && "client".equals(row.side()) && this.caller.equals(row.service())
					&& this.target.equals(row.clientName()) && this.method.equals(row.method())
					&& this.uri.equals(row.uri());
		}

		/** Whether {@code row} is this edge's server-side row: the target's answer to this method and uri. */
		public boolean serverRow(TrafficRow row) {
			return "server".equals(row.side()) && this.target.equals(row.service()) && this.method.equals(row.method())
					&& this.uri.equals(row.uri());
		}

	}

}
