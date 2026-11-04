package dev.kintsugi.agent.dossier;

import java.util.Objects;

/**
 * What one numbered line {@code D<n>} of a rendered dossier states (ADR-028, Decision 3): the service its fact is
 * about, the section it belongs to, and whether that fact changed between the windows. Both views compute it from the
 * typed {@link Dossier} while they write the line, never from the text.
 *
 * @param owner the service the fact is about, or {@link #HEADER} for a line with no single service
 * @param section the section the fact belongs to
 * @param changed whether the fact changed, by {@link WindowChange}
 */
public record LineFact(String owner, Section section, Change changed) {

	/** The owner of a line that states no single lab service's fact. */
	public static final String HEADER = "header";

	public LineFact {
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(section, "section");
		Objects.requireNonNull(changed, "changed");
	}

	/** A line of {@code owner} in {@code section} whose fact was not compared. */
	static LineFact notCompared(String owner, Section section) {
		return new LineFact(owner, section, Change.NOT_COMPARED);
	}

}
