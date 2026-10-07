package dev.kintsugi.lab.fault.control;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the real lab control ({@code kintsugi.lab-control.mode=docker}).
 * @param gatewayBaseUrl gateway-mock's base URL, for F1 and F2
 * @param gatewayConnectTimeout connect timeout of every gateway-mock call
 * @param gatewayReadTimeout read timeout of every gateway-mock call
 * @param composeFile the lab's compose file, for F3 and F4. Required. It must be mounted at its host path: compose
 * resolves the file's relative bind mounts against it, and the Docker daemon reads those paths on the host.
 * @param composeProject the compose project the lab runs as
 * @param recreateWaitTimeout {@code --wait-timeout} of a recreate; whole seconds, at least one
 */
@ConfigurationProperties("kintsugi.lab-control")
public record LabControlProperties(@DefaultValue("http://gateway-mock:8082") URI gatewayBaseUrl,
		@DefaultValue("2s") Duration gatewayConnectTimeout, @DefaultValue("5s") Duration gatewayReadTimeout,
		Path composeFile, @DefaultValue("kintsugi-lab") String composeProject,
		@DefaultValue("120s") Duration recreateWaitTimeout) {

}
