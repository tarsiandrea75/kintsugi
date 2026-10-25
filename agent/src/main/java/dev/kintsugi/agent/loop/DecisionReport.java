package dev.kintsugi.agent.loop;

import java.util.Locale;

import dev.kintsugi.agent.dossier.DossierView;

/**
 * Which report decision mode asks for, {@code kintsugi.agent.decision.report} (agent spec, "Decision mode", "The
 * report"; odd/tasks/service-assessment.md T2). Explorer and dossier mode always ask for the plain report.
 */
public enum DecisionReport {

	/** The report of every mode: the summary, the evidence, then the cause; decision mode's default. */
	PLAIN,

	/**
	 * The assessed report: first the role of each lab service, each citing a line of its own sheet, then the summary
	 * and the cause. Code checks the roles against the call graph and derives the evidence from them.
	 */
	ASSESSED;

	/** The property that selects the report; only decision mode applies it, though every start checks its value. */
	public static final String PROPERTY = "kintsugi.agent.decision.report";

	/**
	 * The property's value when it is not set: the plain report, today's. A constant, so that the {@code @Value}
	 * binding of the property names it; {@code application.yaml} writes it as a literal that names this constant.
	 */
	public static final String DEFAULT = "plain";

	/**
	 * The report named by the property's value.
	 * @throws IllegalArgumentException for any value but {@code plain} or {@code assessed}, naming the property
	 */
	public static DecisionReport of(String value) {
		for (DecisionReport report : values()) {
			if (report.value().equals(value)) {
				return report;
			}
		}
		throw new IllegalArgumentException(PROPERTY + " must be plain or assessed, got \"" + value + "\"");
	}

	/** The value of the property. */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

	/**
	 * Checks that decision mode can ask for this report when it writes its dossier in {@code view}: the assessed report
	 * needs the sheets, since its checks read the service that owns each line.
	 * @throws IllegalArgumentException for the assessed report with any other view, naming both properties
	 */
	public void checkView(DossierView view) {
		if (this == ASSESSED && view != DossierView.SHEETS) {
			throw new IllegalArgumentException(PROPERTY + "=" + value() + " needs " + DossierView.PROPERTY + "="
					+ DossierView.SHEETS.value() + ", got " + view.value()
					+ ": the assessed report's checks read the service that owns each line of the sheets");
		}
	}

}
