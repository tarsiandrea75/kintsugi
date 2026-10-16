package dev.kintsugi.agent.dossier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.dossier.Dossier.Compared;
import dev.kintsugi.agent.dossier.Dossier.EventCounts;
import dev.kintsugi.agent.dossier.Dossier.EventGroup;
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
import dev.kintsugi.agent.dossier.Dossier.LogLines;
import dev.kintsugi.agent.dossier.Dossier.LogSignature;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;
import dev.kintsugi.agent.dossier.Dossier.StatusCount;
import dev.kintsugi.agent.dossier.Dossier.TrafficRow;
import dev.kintsugi.agent.lab.ToolOutcome;

/**
 * The dossier's gathering (agent spec, "Evidence dossier gathering"; ADR-011): the same calls in the same order for
 * every incident, through {@code LabTools}, for the incident window and the previous one, mapped to facts from
 * outputs recorded in mcp-tools' shape ({@code src/test/resources/dossier}).
 */
class DossierGathererTest {

	private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	private static final List<String> SERVICES = List.of("order-service", "payment-service", "inventory-service",
			"gateway-mock");

	@Test
	void callsEveryToolForBothWindowsInAFixedOrder() {
		RecordedLab lab = new RecordedLab("healthy");

		new DossierGatherer(lab, CLOCK).gather(10);

		List<String> expected = new ArrayList<>(List.of( //
				"service_restarts {\"sinceMinutes\":10}",
				"status_summary {\"table\":\"orders\",\"sinceMinutes\":20,\"untilMinutesAgo\":10}",
				"status_summary {\"table\":\"orders\",\"sinceMinutes\":10}",
				"status_summary {\"table\":\"payments\",\"sinceMinutes\":20,\"untilMinutesAgo\":10}",
				"status_summary {\"table\":\"payments\",\"sinceMinutes\":10}",
				"traffic_counts {\"sinceMinutes\":20,\"untilMinutesAgo\":10}", "traffic_counts {\"sinceMinutes\":10}"));
		for (String service : SERVICES) {
			for (String level : List.of("WARN", "ERROR")) {
				String subject = "\"service\":\"" + service + "\",\"level\":\"" + level + "\",\"sinceMinutes\":10";
				expected.add("count_logs {" + subject + "}");
				expected.add("search_logs {" + subject + ",\"limit\":20}");
			}
		}
		for (String source : List.of("order", "payment", "reservation")) {
			expected.add("event_counts {\"source\":\"" + source + "\",\"sinceMinutes\":20,\"untilMinutesAgo\":10}");
			expected.add("event_counts {\"source\":\"" + source + "\",\"sinceMinutes\":10}");
		}
		assertThat(lab.calls()).containsExactlyElementsOf(expected);
	}

	@Test
	void gatheringTwiceGivesTheSameCallsAndTheSameDossier() {
		RecordedLab first = new RecordedLab("f3");
		RecordedLab second = new RecordedLab("f3");

		Dossier one = new DossierGatherer(first, CLOCK).gather(10);
		Dossier two = new DossierGatherer(second, CLOCK).gather(10);

		assertThat(second.calls()).isEqualTo(first.calls());
		assertThat(two).isEqualTo(one);
		assertThat(one.calls()).hasSize(29);
	}

	@Test
	void windowsAreTheLastNMinutesAndTheNMinutesJustBefore() {
		Dossier dossier = new DossierGatherer(new RecordedLab("healthy"), CLOCK).gather(10);

		assertThat(dossier.gatheredAt()).isEqualTo(NOW);
		assertThat(dossier.incidentWindow())
			.isEqualTo(new Dossier.Window(10, 0, Instant.parse("2026-10-16T09:50:00Z"), NOW));
		assertThat(dossier.previousWindow()).isEqualTo(new Dossier.Window(20, 10,
				Instant.parse("2026-10-16T09:40:00Z"), Instant.parse("2026-10-16T09:50:00Z")));
	}

	@Test
	void serviceStatusHasRestartsInTheWindowAndUptimeFromTheNewestStart() {
		Dossier dossier = new DossierGatherer(new RecordedLab("f3"), CLOCK).gather(10);

		List<ServiceStatus> services = ok(dossier.services());
		assertThat(services).extracting(ServiceStatus::service).containsExactlyElementsOf(SERVICES);
		ServiceStatus order = services.get(0);
		assertThat(order.startTimes()).containsExactly(Instant.parse("2026-10-16T08:00:20.300Z"),
				Instant.parse("2026-10-16T09:53:12.400Z"));
		assertThat(order.restartsInWindow()).containsExactly(Instant.parse("2026-10-16T09:53:12.400Z"));
		assertThat(order.uptime()).isEqualTo(Duration.parse("PT6M47.6S"));
		ServiceStatus payment = services.get(1);
		assertThat(payment.restartsInWindow()).isEmpty();
		assertThat(payment.uptime()).isEqualTo(Duration.between(Instant.parse("2026-10-16T08:00:10.700Z"), NOW));
	}

	@Test
	void restartsAreTheNewestStartTimesWhateverTheOrderOfTheOutput() {
		RecordedLab lab = new RecordedLab("healthy").answer("service_restarts",
				"{\"outcome\":\"OK\",\"returned\":1,\"truncated\":false,\"items\":[{\"service\":\"order-service\","
						+ "\"startTimes\":[\"2026-10-16T09:53:12.400Z\",\"2026-10-16T08:00:20.300Z\"],\"restartsInWindow\":1}]}");

		ServiceStatus order = ok(new DossierGatherer(lab, CLOCK).gather(10).services()).get(0);

		assertThat(order.startTimes()).containsExactly(Instant.parse("2026-10-16T08:00:20.300Z"),
				Instant.parse("2026-10-16T09:53:12.400Z"));
		assertThat(order.restartsInWindow()).containsExactly(Instant.parse("2026-10-16T09:53:12.400Z"));
	}

	@Test
	void aServiceMissingFromTheOutputKeepsItsPlaceWithoutStartTimes() {
		RecordedLab lab = new RecordedLab("healthy").answer("service_restarts",
				"{\"outcome\":\"OK\",\"returned\":1,\"truncated\":false,\"items\":[{\"service\":\"gateway-mock\","
						+ "\"startTimes\":[\"2026-10-16T08:00:01.200Z\"],\"restartsInWindow\":0}]}");

		List<ServiceStatus> services = ok(new DossierGatherer(lab, CLOCK).gather(10).services());

		assertThat(services).extracting(ServiceStatus::service).containsExactlyElementsOf(SERVICES);
		assertThat(services.get(0).startTimes()).isEmpty();
		assertThat(services.get(0).uptime()).isNull();
		assertThat(services.get(3).uptime()).isEqualTo(Duration.between(Instant.parse("2026-10-16T08:00:01.200Z"), NOW));
	}

	@Test
	void orderAndPaymentStatesPerWindowWithSampleOrderIds() {
		Dossier dossier = new DossierGatherer(new RecordedLab("f3"), CLOCK).gather(10);

		assertThat(dossier.states()).extracting(Dossier.TableStates::table).containsExactly("orders", "payments");
		Compared<List<StatusCount>> orders = dossier.states().get(0).windows();
		assertThat(ok(orders.previous())).containsExactly(new StatusCount("PAID", 4,
				List.of("5b1f0c2e-3a4d-4e6f-8a9b-0c1d2e3f4a01", "5b1f0c2e-3a4d-4e6f-8a9b-0c1d2e3f4a02",
						"5b1f0c2e-3a4d-4e6f-8a9b-0c1d2e3f4a03")));
		assertThat(ok(orders.incident())).containsExactly(new StatusCount("PAYMENT_PENDING", 6,
				List.of("7d3a2b1c-0e9f-4a8b-b7c6-d5e4f3a2b106", "7d3a2b1c-0e9f-4a8b-b7c6-d5e4f3a2b105",
						"7d3a2b1c-0e9f-4a8b-b7c6-d5e4f3a2b104")));
		Compared<List<StatusCount>> payments = dossier.states().get(1).windows();
		assertThat(ok(payments.previous())).extracting(StatusCount::status, StatusCount::count)
			.containsExactly(tuple("AUTHORIZED", 4L));
		assertThat(ok(payments.incident())).isEmpty();
	}

	@Test
	void trafficKeepsTheToolsRowsPerWindow() {
		Dossier dossier = new DossierGatherer(new RecordedLab("f3"), CLOCK).gather(10);

		assertThat(ok(dossier.traffic().previous())).hasSize(7)
			.contains(new TrafficRow("server", "payment-service", null, "POST", "/payments", "201", "SUCCESS", null, 4));
		assertThat(ok(dossier.traffic().incident())).containsExactly(
				new TrafficRow("client", "order-service", "inventory-service", "POST", "/reservations", "201",
						"SUCCESS", "none", 6),
				new TrafficRow("client", "order-service", "payment-service", "POST", "/payments", "CLIENT_ERROR",
						"UNKNOWN", "ConnectException", 6),
				new TrafficRow("server", "inventory-service", null, "POST", "/reservations", "201", "SUCCESS", null, 6),
				new TrafficRow("server", "order-service", null, "POST", "/orders", "201", "SUCCESS", null, 6));
	}

	@Test
	void logSignaturesPerServiceAndLevelWithCountsAndUntrustedText() {
		Dossier dossier = new DossierGatherer(new RecordedLab("f3"), CLOCK).gather(10);

		assertThat(dossier.logs()).extracting(Dossier.ServiceLogs::service).containsExactlyElementsOf(SERVICES);
		List<LevelLogs> order = dossier.logs().get(0).levels();
		assertThat(order).extracting(LevelLogs::level).containsExactly("WARN", "ERROR");
		assertThat(ok(order.get(0).count())).isEqualTo(6L);
		LogLines warn = ok(order.get(0).lines());
		assertThat(warn.returned()).isEqualTo(6);
		assertThat(warn.truncated()).isFalse();
		assertThat(warn.signatures()).hasSize(1);
		LogSignature signature = warn.signatures().get(0);
		assertThat(signature.count()).isEqualTo(6);
		assertThat(signature.newest()).isEqualTo(Instant.parse("2026-10-16T09:57:52.106123456Z"));
		assertThat(signature.message().text()).startsWith("Payment call failed for order 7d3a2b1c-0e9f-4a8b-b7c6-d5e4f3a2b106")
			.contains("cause=java.net.ConnectException: Connection refused");
		assertThat(ok(order.get(1).count())).isZero();
		assertThat(ok(order.get(1).lines()).signatures()).isEmpty();
		LevelLogs paymentWarn = dossier.logs().get(1).levels().get(0);
		assertThat(ok(paymentWarn.count())).isZero();
		assertThat(ok(paymentWarn.lines())).isEqualTo(new LogLines(0, false, List.of()));
	}

	@Test
	void signaturesGroupMessagesAlikeButForNumbersAndIdsMostFrequentFirst() {
		String output = "{\"outcome\":\"OK\",\"returned\":3,\"truncated\":true,\"items\":["
				+ line("2026-10-16T09:59:00Z", "Gateway rejected order 11111111-2222-3333-4444-555555555555 with 429")
				+ "," + line("2026-10-16T09:58:00Z", "Pool stats: active=3") + ","
				+ line("2026-10-16T09:57:00Z", "Gateway rejected order 11111111-2222-3333-4444-666666666666 with 429")
				+ "]}";
		RecordedLab lab = new RecordedLab("healthy").answer("search_logs-payment-service-WARN-incident", output);

		LogLines lines = ok(new DossierGatherer(lab, CLOCK).gather(10).logs().get(1).levels().get(0).lines());

		assertThat(lines.returned()).isEqualTo(3);
		assertThat(lines.truncated()).isTrue();
		assertThat(lines.signatures()).containsExactly(
				new LogSignature(2, Instant.parse("2026-10-16T09:59:00Z"),
						new UntrustedText("Gateway rejected order 11111111-2222-3333-4444-555555555555 with 429")),
				new LogSignature(1, Instant.parse("2026-10-16T09:58:00Z"), new UntrustedText("Pool stats: active=3")));
	}

	@Test
	void eventCountsPerSourceAndWindow() {
		Dossier dossier = new DossierGatherer(new RecordedLab("f3"), CLOCK).gather(10);

		assertThat(dossier.events()).extracting(Dossier.SourceEvents::source)
			.containsExactly("order", "payment", "reservation");
		Compared<EventCounts> order = dossier.events().get(0).windows();
		assertThat(ok(order.previous()).total()).isEqualTo(12);
		assertThat(ok(order.incident())).isEqualTo(new EventCounts(18,
				List.of(new EventGroup("INVENTORY_RESERVED", "CREATED", 6),
						new EventGroup("ORDER_RECEIVED", "CREATED", 6),
						new EventGroup("PAYMENT_UNAVAILABLE", "PAYMENT_PENDING", 6))));
		assertThat(ok(dossier.events().get(1).windows().incident())).isEqualTo(new EventCounts(0, List.of()));
	}

	@Test
	void aFailingToolIsRecordedAsAFactAndTheRestIsGathered() {
		RecordedLab lab = new RecordedLab("f3").answer("traffic_counts-incident",
				"{\"outcome\":\"UPSTREAM_ERROR\",\"reason\":\"Prometheus did not answer\",\"returned\":0,"
						+ "\"truncated\":false,\"items\":[]}");

		Dossier dossier = new DossierGatherer(lab, CLOCK).gather(10);

		assertThat(dossier.traffic().incident()).isEqualTo(
				new Gathered.Failed<>("traffic_counts", ToolOutcome.UPSTREAM_ERROR, "Prometheus did not answer"));
		assertThat(ok(dossier.traffic().previous())).hasSize(7);
		assertThat(ok(dossier.events().get(0).windows().incident()).total()).isEqualTo(18);
		assertThat(dossier.calls()).hasSize(29);
		assertThat(dossier.calls().get(6).outcome()).isEqualTo(ToolOutcome.UPSTREAM_ERROR);
	}

	@Test
	void aCallThatDoesNotReachMcpToolsIsRecordedAsAClientError() {
		RecordedLab lab = new RecordedLab("f3")
			.answer("service_restarts", "mcp-tools call failed: ResourceAccessException: Connection refused")
			.fail("status_summary-orders-incident", new IllegalStateException("no MCP session"));

		Dossier dossier = new DossierGatherer(lab, CLOCK).gather(10);

		assertThat(dossier.services()).isEqualTo(new Gathered.Failed<>("service_restarts", ToolOutcome.CLIENT_ERROR,
				"mcp-tools call failed: ResourceAccessException: Connection refused"));
		assertThat(dossier.states().get(0).windows().incident()).isEqualTo(new Gathered.Failed<>("status_summary",
				ToolOutcome.CLIENT_ERROR, "the call failed: IllegalStateException: no MCP session"));
		assertThat(dossier.calls().get(2).outcome()).isEqualTo(ToolOutcome.CLIENT_ERROR);
		assertThat(ok(dossier.states().get(0).windows().previous())).hasSize(1);
		assertThat(dossier.calls()).hasSize(29);
	}

	@Test
	void anOkOutputThatCannotBeReadIsRecordedAsUnreadable() {
		RecordedLab lab = new RecordedLab("healthy").answer("event_counts-order-incident",
				"{\"outcome\":\"OK\",\"returned\":1,\"truncated\":false,\"items\":[{\"total\":\"many\"}]}");

		Dossier dossier = new DossierGatherer(lab, CLOCK).gather(10);

		assertThat(dossier.events().get(0).windows().incident()).isInstanceOfSatisfying(Gathered.Failed.class,
				failed -> {
					assertThat(failed.outcome()).isEqualTo(ToolOutcome.OK);
					assertThat(failed.reason()).startsWith("the output could not be read");
				});
	}

	@Test
	void aWindowOfLessThanOneMinuteIsRefused() {
		assertThatThrownBy(() -> new DossierGatherer(new RecordedLab("healthy"), CLOCK).gather(0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static String line(String timestamp, String message) {
		return "{\"timestamp\":\"" + timestamp + "\",\"service\":\"payment-service\",\"level\":\"WARN\","
				+ "\"untrustedText\":\"" + timestamp + "  WARN 1 --- [payment-service] [exec-1] d.k.Gateway : "
				+ message + "\"}";
	}

	private static <T> T ok(Gathered<T> gathered) {
		assertThat(gathered).isInstanceOf(Gathered.Ok.class);
		return ((Gathered.Ok<T>) gathered).value();
	}

}
