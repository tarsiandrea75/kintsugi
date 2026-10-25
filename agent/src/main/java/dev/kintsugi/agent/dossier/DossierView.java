package dev.kintsugi.agent.dossier;

import java.util.Locale;

/**
 * How decision mode writes its dossier for the model, {@code kintsugi.agent.decision.view} (agent spec, "Decision
 * mode"; odd/tasks/service-assessment.md T1). Explorer mode has no dossier, and dossier mode always writes the tables.
 */
public enum DossierView {

	/** {@link DossierRenderer}'s tables, by data source: the dossier of dossier mode, and decision mode's default. */
	TABLES,

	/** {@link ServiceSheets}' fact sheets, one per service, leaf first. */
	SHEETS;

	/** The property that selects the view; only decision mode applies it, though every start checks its value. */
	public static final String PROPERTY = "kintsugi.agent.decision.view";

	/**
	 * The property's value when it is not set: the tables, today's dossier. A constant, so that the {@code @Value}
	 * bindings of the property name it; {@code application.yaml} and {@code replay-agent.sh} write it as a literal that
	 * names this constant.
	 */
	public static final String DEFAULT = "tables";

	/**
	 * The view named by the property's value.
	 * @throws IllegalArgumentException for any value but {@code tables} or {@code sheets}, naming the property
	 */
	public static DossierView of(String value) {
		for (DossierView view : values()) {
			if (view.value().equals(value)) {
				return view;
			}
		}
		throw new IllegalArgumentException(PROPERTY + " must be tables or sheets, got \"" + value + "\"");
	}

	/** The value of the property, and what a replay records with each sample. */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The dossier written in this view: the text the dossier message holds after its introduction. */
	public String render(Dossier dossier) {
		return switch (this) {
			case TABLES -> DossierRenderer.render(dossier);
			case SHEETS -> ServiceSheets.render(dossier).text();
		};
	}

}
