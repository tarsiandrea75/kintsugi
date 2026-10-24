package dev.kintsugi.agent.loop;

import java.util.List;

import dev.kintsugi.agent.lab.Phase;

/**
 * How the loop gathers its evidence, {@code kintsugi.agent.mode} (agent spec, "Dossier mode", "Decision mode"; ADR-010,
 * "Amended in T5a"; ADR-011, "Two measurable modes"). The mode is recorded on the incident and its report, so the evals
 * can split the results.
 */
public enum AgentMode {

	/** The ADR-010 loop: the model chooses every call, from TRIAGE on. */
	EXPLORER(List.of(Phase.TRIAGE, Phase.INVESTIGATE, Phase.KNOWLEDGE)),

	/**
	 * Code gathers the evidence dossier first and shows it to the model, which then has INVESTIGATE and KNOWLEDGE:
	 * the dossier covers TRIAGE's tools.
	 */
	DOSSIER(List.of(Phase.INVESTIGATE, Phase.KNOWLEDGE)),

	/**
	 * Code gathers the evidence dossier at the incident's opening, before the warm-up, and the model writes the report
	 * from it alone: no evidence phase and no tool call (decision-instrument T1). It measures the diagnosis, fast, and a
	 * replay of it makes only recorded calls.
	 */
	DECISION(List.of());

	private final List<Phase> phases;

	AgentMode(List<Phase> phases) {
		this.phases = phases;
	}

	/**
	 * The mode named by the property's value.
	 * @throws IllegalArgumentException for any value but {@code explorer}, {@code dossier} or {@code decision}
	 */
	public static AgentMode of(String value) {
		for (AgentMode mode : values()) {
			if (mode.value().equals(value)) {
				return mode;
			}
		}
		throw new IllegalArgumentException(
				"kintsugi.agent.mode must be explorer, dossier or decision, got \"" + value + "\"");
	}

	/** The value of the property, and what the incident and its report record. */
	public String value() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/**
	 * Whether code gathers the evidence dossier, which every model call then reads after the system message and the
	 * report may cite (T5b): every mode but explorer.
	 */
	public boolean gathersDossier() {
		return this != EXPLORER;
	}

	/**
	 * Whether the dossier is gathered before the warm-up, at the incident's opening, so that its windows end there and
	 * not after the model's load: decision mode only. Dossier mode gathers it after the warm-up, as it always has.
	 */
	public boolean gathersBeforeWarmUp() {
		return this == DECISION;
	}

	/** The evidence phases the model goes through, in order, none in decision mode; {@code CONCLUDE} follows them. */
	public List<Phase> phases() {
		return this.phases;
	}

	/** The incident's tool budget: the sum of the phases' allowances. */
	public int toolBudget() {
		return this.phases.stream().mapToInt(LoopSettings.ALLOWANCES::get).sum();
	}

}
