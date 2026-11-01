package dev.kintsugi.agent.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.Topology;

/**
 * Which rules configuration runs, {@code kintsugi.agent.rules} (ADR-021, "Selection"; odd/tasks/resource-facts.md T5):
 * the {@code rules} replay route's diagnosis and the hybrid report's candidates (ADR-019) both come from it, so the
 * rules a hybrid run is given are exactly those a rules-only run applies.
 */
public enum RulesVersion {

	/** Config 0 (ADR-016), frozen at {@code 5797d16}: {@link RulesDiagnosis} unchanged. */
	V1,

	/**
	 * Config 0R (ADR-021): Config 0's verdict when it is not UNKNOWN, else R-RES's, else Config 0's UNKNOWN; and
	 * Config 0's candidates, then the resource-abnormal services.
	 */
	V2;

	/** The property that selects the version. */
	public static final String PROPERTY = "kintsugi.agent.rules";

	/**
	 * The property's value when it is not set: Config 0. A constant, so that the {@code @Value} binding of the property
	 * names it; {@code application.yaml} writes it as a literal that names this constant.
	 */
	public static final String DEFAULT = "v1";

	/**
	 * The version named by the property's value.
	 * @throws IllegalArgumentException for any value but {@code v1} or {@code v2}, naming the property
	 */
	public static RulesVersion of(String value) {
		for (RulesVersion version : values()) {
			if (version.value().equals(value)) {
				return version;
			}
		}
		throw new IllegalArgumentException(PROPERTY + " must be v1 or v2, got "
				+ (value == null ? "null" : "\"" + value + "\""));
	}

	/** The value of the property. */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The model a rules sample records in {@code summary.tsv} and its incident: {@code rules-v1} or {@code rules-v2}. */
	public String model() {
		return "rules-" + value();
	}

	/**
	 * This configuration's verdict on {@code dossier}. Under {@code v2}, R-RES runs after R-REACH and R-DOWN and before
	 * R-NONE, so it can only replace Config 0's abstention.
	 */
	public RulesVerdict diagnose(Dossier dossier, Topology topology) {
		RulesVerdict config0 = RulesDiagnosis.diagnose(dossier, topology);
		if (this == V1 || !RulesDiagnosis.UNKNOWN.equals(config0.service())) {
			return config0;
		}
		List<RulesVerdict> resources = ResourceRule.abnormal(dossier, topology);
		return resources.isEmpty() ? config0 : resources.get(0);
	}

	/**
	 * This configuration's candidates on {@code dossier}, for the hybrid report. Under {@code v2}: Config 0's list
	 * unchanged, then each resource-abnormal service it does not already hold, by resource ratio, as
	 * {@code service|RESOURCE_EXHAUSTION} by R-RES; at most {@value RulesDiagnosis#MAX_CANDIDATES} in all.
	 */
	public List<RulesCandidate> candidates(Dossier dossier, Topology topology) {
		List<RulesCandidate> config0 = RulesDiagnosis.candidates(dossier, topology);
		if (this == V1) {
			return config0;
		}
		Map<String, RulesCandidate> candidates = new LinkedHashMap<>();
		config0.forEach(candidate -> candidates.put(candidate.service(), candidate));
		ResourceRule.abnormal(dossier, topology)
			.forEach(verdict -> candidates.putIfAbsent(verdict.service(), new RulesCandidate(verdict.service(),
					verdict.category(), verdict.rule(), verdict.facts())));
		return candidates.values().stream().limit(RulesDiagnosis.MAX_CANDIDATES).toList();
	}

}
