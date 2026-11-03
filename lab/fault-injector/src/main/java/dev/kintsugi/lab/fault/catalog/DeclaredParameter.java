package dev.kintsugi.lab.fault.catalog;

import java.util.List;

/**
 * A parameter a caller of {@code POST /faults} may choose, and the only values it may take (lab-realism-min, declared
 * ranges). Like the catalog, the values are fixed in code and never leave the injector through HTTP.
 *
 * @param name the request name, for example {@code gatewayLatencyMs}
 * @param behaviorField the key of the fault's catalog parameters it replaces, for example {@code latencyMs}
 * @param values the declared values, in ascending order; the catalog default is one of them
 */
public record DeclaredParameter(String name, String behaviorField, List<Integer> values) {

	public DeclaredParameter {
		values = List.copyOf(values);
	}

	/** Whether {@code value} is exactly one of the declared integers: a string, a decimal or {@code null} is not. */
	boolean declares(Object value) {
		return value instanceof Integer number && values.contains(number);
	}

}
