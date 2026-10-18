package dev.kintsugi.agent.loop;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The cloud route, {@code kintsugi.agent.cloud} (ADR-012; agent spec, "Routes"). Its answer caps are the local
 * route's, {@code kintsugi.agent.model.num-predict}, so that a cap means the same on both routes.
 *
 * @param name the model, {@code claude-haiku-5-5}
 * @param callTimeout the deadline of one call per task, {@code call-timeout.act} and {@code call-timeout.report}
 */
@ConfigurationProperties("kintsugi.agent.cloud")
public record CloudRouteProperties(String name, CallTimeouts callTimeout) {

	/** The deadlines are required: without them every call would fail at its first use instead of at start. */
	public CloudRouteProperties {
		if (callTimeout == null) {
			throw new IllegalArgumentException(
					"kintsugi.agent.cloud.call-timeout is missing: set call-timeout.act and call-timeout.report");
		}
	}

}
