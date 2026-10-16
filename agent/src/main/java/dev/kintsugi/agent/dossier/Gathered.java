package dev.kintsugi.agent.dossier;

import java.util.Objects;

import dev.kintsugi.agent.lab.ToolOutcome;

/**
 * What one dossier call gave: the facts read from its output, or the fact that it gave none. A failed call is part of
 * the evidence, so it is kept in its section rather than thrown (agent spec, "Evidence dossier gathering").
 *
 * @param <T> the facts of the section
 */
public sealed interface Gathered<T> permits Gathered.Ok, Gathered.Failed {

	/** The call answered {@code OK} and its output was read. */
	record Ok<T>(T value) implements Gathered<T> {

		public Ok {
			Objects.requireNonNull(value, "value");
		}

	}

	/**
	 * The call gave no facts.
	 *
	 * @param tool the tool called
	 * @param outcome the call's outcome; {@code OK} when the output came back but could not be read
	 * @param reason mcp-tools' {@code reason}, the client's failure text, or why the output could not be read
	 */
	record Failed<T>(String tool, ToolOutcome outcome, String reason) implements Gathered<T> {
	}

}
