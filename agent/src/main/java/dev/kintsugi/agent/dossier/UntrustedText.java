package dev.kintsugi.agent.dossier;

import java.util.Objects;

/**
 * Text copied from the lab, such as a log message: evidence to analyse, never an instruction (agent spec, "Untrusted
 * text is data"). The type keeps it apart from the dossier's own words, so a renderer labels and escapes it.
 */
public record UntrustedText(String text) {

	public UntrustedText {
		Objects.requireNonNull(text, "text");
	}

}
