package dev.kintsugi.mcp.evidence;

import java.util.Arrays;
import java.util.List;

import dev.kintsugi.mcp.tool.InvalidParameterException;

/**
 * The three append-only event tables, with the values each one can hold. Types are those of the table's
 * {@code CHECK} constraint (the services' Flyway migrations); outcomes, which have no constraint, are those the
 * services write, as their runbooks list them.
 */
public enum EventSource {

	ORDER("order", Database.ORDERS, "order_events", Values.ORDER_TYPES, Values.ORDER_OUTCOMES),

	PAYMENT("payment", Database.PAYMENTS, "payment_events", Values.PAYMENT_TYPES, Values.PAYMENT_OUTCOMES),

	RESERVATION("reservation", Database.INVENTORY, "reservation_events", Values.RESERVATION_TYPES,
			Values.RESERVATION_OUTCOMES);

	private final String id;

	private final Database database;

	private final String table;

	private final List<String> types;

	private final List<String> outcomes;

	EventSource(String id, Database database, String table, String types, String outcomes) {
		this.id = id;
		this.database = database;
		this.table = table;
		this.types = List.of(types.split(Values.SEPARATOR));
		this.outcomes = List.of(outcomes.split(Values.SEPARATOR));
	}

	public String id() {
		return this.id;
	}

	public Database database() {
		return this.database;
	}

	/** The table name, a constant: never built from a parameter. */
	public String table() {
		return this.table;
	}

	public static List<String> ids() {
		return Arrays.stream(values()).map(EventSource::id).toList();
	}

	public static EventSource byId(String id) {
		return Arrays.stream(values())
			.filter(source -> source.id.equals(id))
			.findFirst()
			.orElseThrow(() -> new InvalidParameterException("source", "must be one of " + ids()));
	}

	/** An optional event type, which must be one this table allows. */
	public String type(String value) {
		if (value != null && !this.types.contains(value)) {
			throw new InvalidParameterException("type", "must be one of " + this.types + " for source " + this.id);
		}
		return value;
	}

	/** An optional outcome, which must be one this source writes. */
	public String outcome(String value) {
		if (value != null && !this.outcomes.contains(value)) {
			throw new InvalidParameterException("outcome",
					"must be one of " + this.outcomes + " for source " + this.id);
		}
		return value;
	}

	/**
	 * The allowed values of each source, as compile-time constants joined by {@link #SEPARATOR}. They are the single
	 * source of both the validation above and the {@code recent_events} parameter descriptions, which are annotation
	 * values and so must be constants: the two cannot drift apart.
	 */
	static final class Values {

		static final String SEPARATOR = ", ";

		static final String ORDER_TYPES = "ORDER_RECEIVED, INVENTORY_RESERVED, INVENTORY_REJECTED, INVENTORY_UNAVAILABLE,"
				+ " PAYMENT_AUTHORIZED, PAYMENT_DECLINED, PAYMENT_FAILED, PAYMENT_UNAVAILABLE";

		/** The order status after the event. */
		static final String ORDER_OUTCOMES = "CREATED, PAYMENT_PENDING, PAID, PAYMENT_FAILED, REJECTED_OUT_OF_STOCK,"
				+ " REJECTED_INVENTORY_UNAVAILABLE";

		static final String PAYMENT_TYPES = "PAYMENT_ATTEMPT";

		static final String PAYMENT_OUTCOMES = "AUTHORIZED, DECLINED, FAILED, GATEWAY_UNAVAILABLE, ERROR, COMMIT_UNKNOWN";

		static final String RESERVATION_TYPES = "RESERVATION_ATTEMPT";

		static final String RESERVATION_OUTCOMES = "RESERVED, INSUFFICIENT_STOCK";

		private Values() {
		}

	}

}
