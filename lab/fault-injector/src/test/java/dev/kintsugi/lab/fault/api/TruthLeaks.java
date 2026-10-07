package dev.kintsugi.lab.fault.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.kintsugi.lab.fault.catalog.Fault;

/**
 * Every string that would give away part of a true cause: field names, root-cause services and targets, categories,
 * root-cause sentences, parameter names and parameter values longer than two characters. A response body must contain
 * none of them.
 */
final class TruthLeaks {

	private static final List<String> FORBIDDEN = forbidden();

	private TruthLeaks() {
	}

	static void assertNoTruthIn(String body) {
		String lowerCase = body.toLowerCase(Locale.ROOT);
		assertThat(FORBIDDEN.stream().filter(token -> lowerCase.contains(token.toLowerCase(Locale.ROOT))))
			.as("truth leaked in response body: %s", body)
			.isEmpty();
	}

	private static List<String> forbidden() {
		List<String> tokens = new ArrayList<>(List.of("root_cause", "rootCause", "root-cause", "target", "parameters",
				"category", "service", "latency", "errorStatus", "payment", "inventory", "gateway"));
		for (Fault fault : Fault.values()) {
			tokens.add(fault.rootCauseService());
			tokens.add(fault.targetService());
			tokens.add(fault.rootCauseCategory().name());
			tokens.add(fault.rootCause());
			fault.parameters().forEach((name, value) -> {
				tokens.add(name);
				// Values of one or two characters ("2", "5s") would match ids and timestamps; longer ones are checked.
				if (value instanceof String text && text.length() > 2) {
					tokens.add(text);
				}
			});
		}
		return List.copyOf(tokens);
	}

}
