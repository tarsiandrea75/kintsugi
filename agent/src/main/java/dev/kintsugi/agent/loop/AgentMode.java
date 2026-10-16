package dev.kintsugi.agent.loop;

import java.util.List;

import dev.kintsugi.agent.lab.Phase;

/**
 * How the loop gathers its evidence, {@code kintsugi.agent.mode} (agent spec, "Dossier mode"; ADR-010, "Amended in
 * T5a"; ADR-011, "Two measurable modes"). The mode is recorded on the incident and its report, so the evals can split
 * the results.
 */
public enum AgentMode {

	/** The ADR-010 loop: the model chooses every call, from TRIAGE on. */
	EXPLORER(List.of(Phase.TRIAGE, Phase.INVESTIGATE, Phase.KNOWLEDGE)),

	/**
	 * Code gathers the evidence dossier first and shows it to the model, which then has INVESTIGATE and KNOWLEDGE:
	 * the dossier covers TRIAGE's tools.
	 */
	DOSSIER(List.of(Phase.INVESTIGATE, Phase.KNOWLEDGE));

	private final List<Phase> phases;

	AgentMode(List<Phase> phases) {
		this.phases = phases;
	}

	/**
	 * The mode named by the property's value.
	 * @throws IllegalArgumentException for any value but {@code explorer} or {@code dossier}
	 */
	public static AgentMode of(String value) {
		for (AgentMode mode : values()) {
			if (mode.value().equals(value)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("kintsugi.agent.mode must be explorer or dossier, got \"" + value + "\"");
	}

	/** The value of the property, and what the incident and its report record. */
	public String value() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/** The evidence phases the model goes through, in order; {@code CONCLUDE} follows them. */
	public List<Phase> phases() {
		return this.phases;
	}

	/** The incident's tool budget: the sum of the phases' allowances. */
	public int toolBudget() {
		return this.phases.stream().mapToInt(LoopSettings.ALLOWANCES::get).sum();
	}

}
