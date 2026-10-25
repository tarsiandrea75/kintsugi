package dev.kintsugi.agent.loop;

import java.util.List;
import java.util.Optional;

import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.lab.Phase;

/**
 * How the loop gathers its evidence, {@code kintsugi.agent.mode} (agent spec, "Dossier mode", "Decision mode"; ADR-010,
 * "Amended in T5a"; ADR-011, "Two measurable modes"). The mode is recorded on the incident and its report, so the evals
 * can split the results.
 */
public enum AgentMode {

	/** The ADR-010 loop: the model chooses every call, from TRIAGE on. */
	EXPLORER(List.of(Phase.TRIAGE, Phase.INVESTIGATE, Phase.KNOWLEDGE), DossierGathering.NONE),

	/**
	 * Code gathers the evidence dossier first and shows it to the model, which then has INVESTIGATE and KNOWLEDGE:
	 * the dossier covers TRIAGE's tools.
	 */
	DOSSIER(List.of(Phase.INVESTIGATE, Phase.KNOWLEDGE), DossierGathering.AFTER_WARM_UP),

	/**
	 * Code gathers the evidence dossier at the incident's opening, before the warm-up, and the model writes the report
	 * from it alone: no evidence phase and no tool call (decision-instrument T1). It measures the diagnosis, fast, and a
	 * replay of it makes only recorded calls.
	 */
	DECISION(List.of(), DossierGathering.BEFORE_WARM_UP);

	private final List<Phase> phases;

	private final DossierGathering dossierGathering;

	AgentMode(List<Phase> phases, DossierGathering dossierGathering) {
		this.phases = phases;
		this.dossierGathering = dossierGathering;
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
	 * When code gathers the evidence dossier, if at all: the run's opening, before its first phase, is the warm-up with
	 * the dossier before it, after it or nowhere (agent spec, "Dossier mode", the table's "Before the first phase").
	 */
	public DossierGathering dossierGathering() {
		return this.dossierGathering;
	}

	/**
	 * Whether code gathers the evidence dossier, which every model call then reads after the system message and the
	 * report may cite (T5b): every mode but explorer.
	 */
	public boolean gathersDossier() {
		return this.dossierGathering != DossierGathering.NONE;
	}

	/** The evidence phases the model goes through, in order, none in decision mode; {@code CONCLUDE} follows them. */
	public List<Phase> phases() {
		return this.phases;
	}

	/** The incident's tool budget: the sum of the phases' allowances. */
	public int toolBudget() {
		return this.phases.stream().mapToInt(LoopSettings.ALLOWANCES::get).sum();
	}

	/**
	 * The dossier view this mode takes from {@code kintsugi.agent.decision.view} set to {@code view}: that view in
	 * decision mode, and none in the others, which never apply the setting (explorer mode has no dossier, and dossier
	 * mode always writes the tables). The one rule of which mode applies it: the loop writes decision mode's dossier in
	 * it, and a replay records it with each sample (service-assessment T1r).
	 */
	public Optional<DossierView> viewFrom(DossierView view) {
		return this == DECISION ? Optional.of(view) : Optional.empty();
	}

	/** When code gathers the evidence dossier, relative to the warm-up. */
	public enum DossierGathering {

		/** Never: the model gathers all its evidence (explorer mode). */
		NONE,

		/**
		 * Before the warm-up, at the incident's opening, so that the dossier's windows end there and not after the
		 * model's load (decision mode).
		 */
		BEFORE_WARM_UP,

		/** After the warm-up, as dossier mode always has. */
		AFTER_WARM_UP

	}

}
