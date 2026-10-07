package dev.kintsugi.lab.fault.control;

import java.nio.file.Files;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.kintsugi.lab.fault.catalog.LabMechanism;

/**
 * Selected by {@code kintsugi.lab-control.mode=docker}: the real lab control, one {@link RoutingLabControl} over the
 * gateway-mock admin API and the compose recreate. The two controls are not beans themselves, so the application sees
 * exactly one {@link LabControl}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "kintsugi.lab-control", name = "mode", havingValue = "docker")
@EnableConfigurationProperties(LabControlProperties.class)
public class DockerLabControlConfiguration {

	@Bean
	LabControl dockerLabControl(LabControlProperties properties) {
		if (properties.composeFile() == null) {
			throw new IllegalStateException(
					"kintsugi.lab-control.compose-file is required when kintsugi.lab-control.mode=docker");
		}
		// Fail at startup, not at the first F3: a wrong path or a repository mounted elsewhere is a deployment error.
		if (!Files.isRegularFile(properties.composeFile()) || !Files.isReadable(properties.composeFile())) {
			throw new IllegalStateException("kintsugi.lab-control.compose-file " + properties.composeFile()
					+ " does not exist or is not readable; mount the repository read-only at its host path");
		}
		HttpGatewayControl gateway = new HttpGatewayControl(properties.gatewayBaseUrl(),
				properties.gatewayConnectTimeout(), properties.gatewayReadTimeout());
		ComposeRecreateControl compose = new ComposeRecreateControl(new SystemProcessRunner(),
				properties.composeFile(), properties.composeProject(), properties.recreateWaitTimeout(), System.getenv());
		return new RoutingLabControl(
				Map.of(LabMechanism.GATEWAY_BEHAVIOR, gateway, LabMechanism.COMPOSE_RECREATE, compose));
	}

}
