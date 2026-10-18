package dev.kintsugi.agent.loop;

import java.util.Locale;

/**
 * Where the loop's model runs, {@code kintsugi.agent.route} (agent spec, "Routes"; ADR-012; ADR-010, "Amended in
 * cloud-route T3"). The route is recorded on the incident and its report, with the model, so the evals can split the
 * results.
 */
public enum AgentRoute {

	/** The local Ollama model, the default and the only route that can pass M2's check. */
	LOCAL("ollama"),

	/** Anthropic's API, for design iterations (ADR-012). */
	CLOUD("anthropic");

	private final String provider;

	AgentRoute(String provider) {
		this.provider = provider;
	}

	/**
	 * The route named by the property's value.
	 * @throws IllegalArgumentException for any value but {@code local} or {@code cloud}
	 */
	public static AgentRoute of(String value) {
		for (AgentRoute route : values()) {
			if (route.value().equals(value)) {
				return route;
			}
		}
		throw new IllegalArgumentException("kintsugi.agent.route must be local or cloud, got \"" + value + "\"");
	}

	/** The value of the property, and what the incident and its report record. */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** Spring AI's name of the route's chat model provider, the value of {@code spring.ai.model.chat}. */
	public String provider() {
		return this.provider;
	}

}
