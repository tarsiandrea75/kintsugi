package dev.kintsugi.agent.loop;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cloud route, {@code kintsugi.agent.cloud} (ADR-012; agent spec, "Routes"). Its answer caps are its own,
 * {@code kintsugi.agent.cloud.num-predict}, today equal to the local route's: the local report cap is bounded by the
 * CPU's write time, which the cloud route does not have (agent spec, "Answer caps").
 *
 * @param name the model, {@code claude-haiku-5-5}
 * @param callTimeout the deadline of one call per task, {@code call-timeout.act} and {@code call-timeout.report}
 * @param numPredict the answer caps per kind of call, {@code num-predict.first-act}, {@code .act} and {@code .report},
 * sent as {@code maxTokens}
 */
@ConfigurationProperties("kintsugi.agent.cloud")
public record CloudRouteProperties(String name, CallTimeouts callTimeout, NumPredict numPredict) {

	/** Both groups are required: without them every call would fail at its first use instead of at start. */
	public CloudRouteProperties {
		if (callTimeout == null) {
			throw new IllegalArgumentException(
					"kintsugi.agent.cloud.call-timeout is missing: set call-timeout.act and call-timeout.report");
		}
		if (numPredict == null) {
			throw new IllegalArgumentException("kintsugi.agent.cloud.num-predict is missing: set num-predict.first-act,"
					+ " num-predict.act and num-predict.report");
		}
	}

}
