package dev.kintsugi.agent.loop;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Ollama route, {@code kintsugi.agent.model} (ADR-003 for the model and its settings, ADR-010 for the deadlines and
 * the answer caps). The values live in {@code application.yaml}, and their reasons in the agent spec.
 *
 * @param name the model, {@code granite4.1:8b}
 * @param temperature 0, so a run is as repeatable as the model allows
 * @param numCtx the context window, in tokens
 * @param callTimeout the deadline of one call per task, {@code call-timeout.act} and {@code call-timeout.report}
 * (agent spec, "Budgets")
 * @param numPredict the answer caps per kind of call, {@code num-predict.first-act}, {@code .act} and {@code .report}
 * (agent spec, "Answer caps")
 * @param think Ollama's {@code think} request field, {@code kintsugi.agent.model.think} (agent spec, "Routes";
 * ADR-017): null, the default, sends no such field, so every request is the one sent before the field existed;
 * {@code true} or {@code false} sends it with that value. An empty value, as {@code KINTSUGI_AGENT_MODEL_THINK=}, binds
 * to null, so a replay can clear one left in the shell.
 */
@ConfigurationProperties("kintsugi.agent.model")
public record ModelRouteProperties(String name, double temperature, int numCtx, CallTimeouts callTimeout,
		NumPredict numPredict, Boolean think) {

	/** Both groups are required: without them every call would fail at its first use instead of at start. */
	public ModelRouteProperties {
		if (callTimeout == null) {
			throw new IllegalArgumentException(
					"kintsugi.agent.model.call-timeout is missing: set call-timeout.act and call-timeout.report");
		}
		if (numPredict == null) {
			throw new IllegalArgumentException("kintsugi.agent.model.num-predict is missing: set num-predict.first-act,"
					+ " num-predict.act and num-predict.report");
		}
	}

}
