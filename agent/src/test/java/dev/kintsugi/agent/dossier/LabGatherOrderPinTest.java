package dev.kintsugi.agent.dossier;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.dossier.Dossier.ServiceLatency;
import dev.kintsugi.agent.dossier.Dossier.ServiceLogs;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;
import dev.kintsugi.agent.dossier.Dossier.SourceEvents;
import dev.kintsugi.agent.dossier.Dossier.TableStates;

/**
 * Pins the lists the gatherer reads for the lab topology (odd/tasks/topology-data.md T4): the services in call-path
 * order, the {@code status_summary} tables and the {@code event_counts} sources, as the dossier records them. The
 * expected values are the constants the gatherer held before T4 derived them from the topology; they must never
 * change. Only {@link #gather()} names the API.
 */
class LabGatherOrderPinTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);

	@Test
	void theGatheredListsAreTodays() {
		Dossier dossier = gather();

		assertThat(((Gathered.Ok<java.util.List<ServiceStatus>>) dossier.services()).value())
			.extracting(ServiceStatus::service)
			.containsExactly("order-service", "payment-service", "inventory-service", "gateway-mock");
		assertThat(dossier.latency()).extracting(ServiceLatency::service)
			.containsExactly("order-service", "payment-service", "inventory-service", "gateway-mock");
		assertThat(dossier.logs()).extracting(ServiceLogs::service)
			.containsExactly("order-service", "payment-service", "inventory-service", "gateway-mock");
		assertThat(dossier.states()).extracting(TableStates::table).containsExactly("orders", "payments");
		assertThat(dossier.events()).extracting(SourceEvents::source).containsExactly("order", "payment", "reservation");
		assertThat(dossier.calls()).hasSize(33);
	}

	private static Dossier gather() {
		return new DossierGatherer(new RecordedLab("healthy"), CLOCK, LAB).gather(10);
	}

}
