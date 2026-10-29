package dev.kintsugi.agent.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.Dossier.Compared;
import dev.kintsugi.agent.dossier.Dossier.LatencyPoints;
import dev.kintsugi.agent.dossier.Dossier.LatencySeries;
import dev.kintsugi.agent.dossier.Dossier.LevelLogs;
import dev.kintsugi.agent.dossier.Dossier.LogLines;
import dev.kintsugi.agent.dossier.Dossier.ServiceLatency;
import dev.kintsugi.agent.dossier.Dossier.ServiceLogs;
import dev.kintsugi.agent.dossier.Dossier.ServiceStatus;
import dev.kintsugi.agent.dossier.Dossier.TrafficRow;
import dev.kintsugi.agent.dossier.Dossier.UriLatency;
import dev.kintsugi.agent.dossier.Dossier.Window;
import dev.kintsugi.agent.dossier.DossierGatherer;
import dev.kintsugi.agent.dossier.DossierView;
import dev.kintsugi.agent.dossier.Gathered;
import dev.kintsugi.agent.dossier.RecordedLab;
import dev.kintsugi.agent.lab.ToolOutcome;

/**
 * Config 0, the rules-only diagnosis (ADR-016; odd/tasks/rules-baseline.md AC2, AC3): the golden F1, F3 and healthy
 * dossiers give their verdicts; the verdict reads only the record, so neither view changes it; and synthetic dossiers
 * pin the definitions at their edges: the slow and refusing thresholds, timeouts, connection failures, actuator URIs,
 * failed sections, the fallback to callers' client rows, the rule order and the tie-breaks.
 */
class RulesDiagnosisTest {

	private static final Instant NOW = Instant.parse("2026-10-16T10:00:00Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void theF1GoldenDossierIsGatewayMockLatencyByTheDownRule() {
		RulesVerdict verdict = RulesDiagnosis.diagnose(golden("f1", "healthy"));
		assertThat(verdict.service()).isEqualTo("gateway-mock");
		assertThat(verdict.category()).isEqualTo("DEPENDENCY_LATENCY");
		assertThat(verdict.rule()).isEqualTo("R-DOWN");
		assertThat(verdict.facts()).isNotEmpty().anySatisfy(fact -> assertThat(fact).contains("gateway-mock"));
	}

	@Test
	void theF3GoldenDossierIsOrderServiceConfigurationByTheReachRule() {
		RulesVerdict verdict = RulesDiagnosis.diagnose(golden("f3"));
		assertThat(verdict.service()).isEqualTo("order-service");
		assertThat(verdict.category()).isEqualTo("CONFIGURATION");
		assertThat(verdict.rule()).isEqualTo("R-REACH");
		assertThat(verdict.facts()).isNotEmpty().anySatisfy(fact -> assertThat(fact).contains("ConnectException"));
	}

	@Test
	void theHealthyGoldenDossierIsUnknownByTheNoneRule() {
		RulesVerdict verdict = RulesDiagnosis.diagnose(golden("healthy"));
		assertThat(verdict.service()).isEqualTo("UNKNOWN");
		assertThat(verdict.category()).isEqualTo("UNKNOWN");
		assertThat(verdict.rule()).isEqualTo("R-NONE");
		assertThat(verdict.facts()).isNotEmpty();
	}

	@Test
	void theVerdictReadsOnlyTheRecordSoNeitherViewChangesIt() {
		// diagnose takes the record and no view; rendering the same record in either view leaves the verdict as it was.
		for (String set : List.of("f1", "f3", "healthy")) {
			Dossier dossier = golden(set, "healthy");
			RulesVerdict before = RulesDiagnosis.diagnose(dossier);
			for (DossierView view : DossierView.values()) {
				assertThat(view.render(dossier)).isNotBlank();
				assertThat(RulesDiagnosis.diagnose(dossier)).as("%s after %s", set, view).isEqualTo(before);
			}
		}
	}

	@Test
	void aUriAtExactlyTwiceItsBaselineAndHalfASecondIsSlow() {
		RulesVerdict verdict = RulesDiagnosis.diagnose(new Lab().latency("payment-service", "/payments", 0.25, 0.5)
			.dossier());
		assertThat(verdict).extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("payment-service", "DEPENDENCY_LATENCY", "R-DOWN");
		assertThat(verdict.facts()).anySatisfy(fact -> assertThat(fact).contains("/payments"));
	}

	@Test
	void aUriJustUnderEitherLatencyThresholdIsNotSlow() {
		// More than twice the baseline, but under 0.5 s.
		assertNone(new Lab().latency("payment-service", "/payments", 0.2, 0.4999).dossier());
		// Over 0.5 s, but just under twice the baseline.
		assertNone(new Lab().latency("payment-service", "/payments", 0.2501, 0.5).dossier());
	}

	@Test
	void aUriWithNoBaselineOrAZeroBaselineCannotBeSlow() {
		assertNone(new Lab().latency("payment-service", "/payments", null, 9.0).dossier());
		assertNone(new Lab().latency("payment-service", "/payments", 0.0, 9.0).dossier());
	}

	@Test
	void threeTimeoutsInTheCallersRowsMakeTheCalleeSlowAndTwoDoNot() {
		assertThat(RulesDiagnosis.diagnose(new Lab().clientRow("order-service", "payment-service", "/payments",
				"CLIENT_ERROR", "HttpTimeoutException", 3)
			.serverRow("payment-service", "/payments", "201", 3)
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("payment-service", "DEPENDENCY_LATENCY", "R-DOWN");
		assertNone(new Lab().clientRow("order-service", "payment-service", "/payments", "CLIENT_ERROR",
				"HttpTimeoutException", 2)
			.serverRow("payment-service", "/payments", "201", 3)
			.dossier());
	}

	@Test
	void aTimeoutIsNeitherAConnectionFailureNorAnErrorAnswer() {
		// Its name contains Connection, but it is a timeout first: no R-REACH.
		assertNone(new Lab().clientRow("order-service", "payment-service", "/payments", "CLIENT_ERROR",
				"ConnectionTimeoutException", 2)
			.serverRow("payment-service", "/payments", "201", 3)
			.dossier());
		// payment-service has no server rows, so its answers are order-service's rows: timeouts with a 504 status are
		// no error answer, so it is slow by its timeouts and not refusing.
		assertThat(RulesDiagnosis.diagnose(new Lab()
			.clientRow("order-service", "payment-service", "/payments", "504", "HttpTimeoutException", 3)
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category)
			.containsExactly("payment-service", "DEPENDENCY_LATENCY");
	}

	@Test
	void refusingNeedsThreeErrorAnswersAndATwentyPercentShare() {
		// 3 of 15: exactly 20 %.
		assertThat(RulesDiagnosis.diagnose(new Lab().serverRow("payment-service", "/payments", "503", 3)
			.serverRow("payment-service", "/payments", "201", 12)
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("payment-service", "DEPENDENCY_REJECTING", "R-DOWN");
		// 3 of 16: under 20 %.
		assertNone(new Lab().serverRow("payment-service", "/payments", "503", 3)
			.serverRow("payment-service", "/payments", "201", 13)
			.dossier());
		// 2 of 3: a large share, but fewer than 3.
		assertNone(new Lab().serverRow("payment-service", "/payments", "503", 2)
			.serverRow("payment-service", "/payments", "201", 1)
			.dossier());
	}

	@Test
	void aServiceWithNoServerRowsIsJudgedOnItsCallersClientRowsAndANonNumericStatusIsNoErrorAnswer() {
		assertThat(RulesDiagnosis.diagnose(new Lab()
			.clientRow("order-service", "payment-service", "/payments", "503", "none", 3)
			.clientRow("order-service", "payment-service", "/payments", "201", "none", 2)
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category)
			.containsExactly("payment-service", "DEPENDENCY_REJECTING");
		assertNone(new Lab().clientRow("order-service", "payment-service", "/payments", "CLIENT_ERROR", "none", 5)
			.dossier());
	}

	@Test
	void actuatorUrisAreIgnored() {
		assertNone(new Lab().serverRow("payment-service", "/actuator/health", "503", 10)
			.serverRow("payment-service", "/payments", "201", 5)
			.latency("payment-service", "/actuator/prometheus", 0.01, 5.0)
			.dossier());
	}

	@Test
	void aFailedSectionIsNoEvidence() {
		// No traffic and no latency: nothing is slow or refusing.
		assertNone(new Lab().serverRow("payment-service", "/payments", "503", 10)
			.latency("payment-service", "/payments", 0.05, 9.0)
			.failTraffic()
			.failLatency("payment-service")
			.dossier());
		// Failed restarts and log counts do not make a callee unhealthy, so R-REACH still fires.
		assertThat(RulesDiagnosis.diagnose(new Lab()
			.clientRow("order-service", "payment-service", "/payments", "CLIENT_ERROR", "ConnectException", 1)
			.failServices()
			.failLogs("payment-service")
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("order-service", "CONFIGURATION", "R-REACH");
	}

	@Test
	void aConnectionFailureToAnUnhealthyCalleeIsNotAConfigurationOfTheCaller() {
		Lab base = new Lab().clientRow("order-service", "payment-service", "/payments", "CLIENT_ERROR",
				"java.nio.channels.ClosedChannelException", 4);
		assertThat(RulesDiagnosis.diagnose(base.dossier()).rule()).isEqualTo("R-REACH");
		assertNone(base.copy().restart("payment-service").dossier());
		assertNone(base.copy().logs("payment-service", "WARN", 1).dossier());
		assertNone(base.copy().logs("payment-service", "ERROR", 1).dossier());
	}

	@Test
	void theReachRuleComesBeforeTheDownRule() {
		RulesVerdict verdict = RulesDiagnosis.diagnose(new Lab()
			.clientRow("order-service", "payment-service", "/payments", "CLIENT_ERROR", "UnknownHostException", 1)
			.latency("inventory-service", "/reservations", 0.02, 9.0)
			.dossier());
		assertThat(verdict).extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("order-service", "CONFIGURATION", "R-REACH");
	}

	@Test
	void theCallerWithMostConnectionFailuresWinsAndATieGoesToServicesOrder() {
		Lab lab = new Lab()
			.clientRow("order-service", "payment-service", "/payments", "CLIENT_ERROR", "ConnectException", 2)
			.clientRow("payment-service", "gateway-mock", "/authorize", "CLIENT_ERROR", "ConnectException", 3);
		assertThat(RulesDiagnosis.diagnose(lab.dossier()).service()).isEqualTo("payment-service");
		Lab tied = new Lab()
			.clientRow("order-service", "payment-service", "/payments", "CLIENT_ERROR", "ConnectException", 3)
			.clientRow("payment-service", "gateway-mock", "/authorize", "CLIENT_ERROR", "ConnectException", 3);
		// payment-service comes before order-service in CallGraph.SERVICES.
		assertThat(RulesDiagnosis.diagnose(tied.dossier()).service()).isEqualTo("payment-service");
	}

	@Test
	void aComponentBothRefusingAndSlowIsRejecting() {
		assertThat(RulesDiagnosis.diagnose(new Lab().serverRow("payment-service", "/payments", "503", 5)
			.latency("payment-service", "/payments", 0.05, 9.0)
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category)
			.containsExactly("payment-service", "DEPENDENCY_REJECTING");
	}

	@Test
	void theWalkTakesTheCalleeWithTheLargerRefusingShareThenTheLargerLatencyRatio() {
		// inventory-service refuses, payment-service is only slow: the refusing share decides.
		assertThat(RulesDiagnosis.diagnose(new Lab().serverRow("inventory-service", "/reservations", "503", 3)
			.latency("payment-service", "/payments", 0.05, 9.0)
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category)
			.containsExactly("inventory-service", "DEPENDENCY_REJECTING");
		// Both only slow: the larger ratio decides.
		assertThat(RulesDiagnosis.diagnose(new Lab().latency("inventory-service", "/reservations", 0.1, 0.6)
			.latency("payment-service", "/payments", 0.1, 0.9)
			.dossier()).service()).isEqualTo("payment-service");
		assertThat(RulesDiagnosis.diagnose(new Lab().latency("inventory-service", "/reservations", 0.1, 0.9)
			.latency("payment-service", "/payments", 0.1, 0.6)
			.dossier()).service()).isEqualTo("inventory-service");
	}

	@Test
	void aCalleeThatIsNotRefusingHasNoRefusingShareInTheWalk() {
		// payment-service is slow with 2 of 3 error answers (not refusing); inventory-service refuses at 3 of 15.
		assertThat(RulesDiagnosis.diagnose(new Lab().serverRow("payment-service", "/payments", "503", 2)
			.serverRow("payment-service", "/payments", "201", 1)
			.latency("payment-service", "/payments", 0.05, 9.0)
			.serverRow("inventory-service", "/reservations", "503", 3)
			.serverRow("inventory-service", "/reservations", "201", 12)
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category)
			.containsExactly("inventory-service", "DEPENDENCY_REJECTING");
	}

	@Test
	void aConnectionFailureIsNotAnAnswerOfTheCalleeItNeverReached() {
		// The callee has no server rows, and the only client rows to it are connection failures with a numeric status.
		assertThat(RulesDiagnosis.diagnose(new Lab()
			.clientRow("order-service", "payment-service", "/payments", "503", "ConnectException", 3)
			.dossier())).extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("order-service", "CONFIGURATION", "R-REACH");
	}

	@Test
	void theWalkGoesDownToTheLastSlowComponentAndStopsAtTheEntryWhenOnlyItIsSlow() {
		assertThat(RulesDiagnosis.diagnose(new Lab().latency("payment-service", "/payments", 0.05, 9.0)
			.latency("gateway-mock", "/authorize", 0.01, 8.9)
			.dossier()).service()).isEqualTo("gateway-mock");
		// gateway-mock is slow, but payment-service is not, so the walk never reaches it.
		assertNone(new Lab().latency("gateway-mock", "/authorize", 0.01, 8.9).dossier());
		assertThat(RulesDiagnosis.diagnose(new Lab().latency("order-service", "/orders", 0.08, 9.0).dossier()))
			.extracting(RulesVerdict::service, RulesVerdict::category)
			.containsExactly("order-service", "DEPENDENCY_LATENCY");
	}

	private static void assertNone(Dossier dossier) {
		RulesVerdict verdict = RulesDiagnosis.diagnose(dossier);
		assertThat(verdict).extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("UNKNOWN", "UNKNOWN", "R-NONE");
		assertThat(verdict.facts()).isNotEmpty();
	}

	/** A golden dossier, gathered from the recorded outputs as the dossier tests gather it. */
	private static Dossier golden(String set, String... fallbacks) {
		return new DossierGatherer(new RecordedLab(set, fallbacks), CLOCK).gather(10);
	}

	/**
	 * A synthetic dossier: no traffic, flat latency, no restarts and no logs unless set. Only what the rules read is
	 * filled in.
	 */
	private static final class Lab {

		private final List<TrafficRow> rows = new ArrayList<>();

		private final Map<String, List<UriLatency>> latency = new HashMap<>();

		private final Map<String, Long> warn = new HashMap<>();

		private final Map<String, Long> error = new HashMap<>();

		private final List<String> restarted = new ArrayList<>();

		private final List<String> failedLatency = new ArrayList<>();

		private final List<String> failedLogs = new ArrayList<>();

		private boolean failedTraffic;

		private boolean failedServices;

		Lab copy() {
			Lab copy = new Lab();
			copy.rows.addAll(this.rows);
			this.latency.forEach((service, uris) -> copy.latency.put(service, new ArrayList<>(uris)));
			copy.warn.putAll(this.warn);
			copy.error.putAll(this.error);
			copy.restarted.addAll(this.restarted);
			copy.failedLatency.addAll(this.failedLatency);
			copy.failedLogs.addAll(this.failedLogs);
			copy.failedTraffic = this.failedTraffic;
			copy.failedServices = this.failedServices;
			return copy;
		}

		Lab serverRow(String service, String uri, String status, long count) {
			this.rows.add(new TrafficRow("server", service, null, "POST", uri, status, outcome(status), null, count));
			return this;
		}

		Lab clientRow(String caller, String callee, String uri, String status, String exception, long count) {
			this.rows.add(new TrafficRow("client", caller, callee, "POST", uri, status, outcome(status), exception,
					count));
			return this;
		}

		Lab latency(String service, String uri, Double previous, double incident) {
			this.latency.computeIfAbsent(service, s -> new ArrayList<>())
				.add(new UriLatency(uri, points(previous), points(incident)));
			return this;
		}

		Lab restart(String service) {
			this.restarted.add(service);
			return this;
		}

		Lab logs(String service, String level, long count) {
			("WARN".equals(level) ? this.warn : this.error).put(service, count);
			return this;
		}

		Lab failTraffic() {
			this.failedTraffic = true;
			return this;
		}

		Lab failServices() {
			this.failedServices = true;
			return this;
		}

		Lab failLatency(String service) {
			this.failedLatency.add(service);
			return this;
		}

		Lab failLogs(String service) {
			this.failedLogs.add(service);
			return this;
		}

		Dossier dossier() {
			Window incident = new Window(10, 0, NOW.minusSeconds(600), NOW);
			Window previous = new Window(20, 10, NOW.minusSeconds(1200), NOW.minusSeconds(600));
			Instant start = NOW.minus(Duration.ofHours(2));
			Instant restart = NOW.minusSeconds(300);
			List<ServiceStatus> statuses = DossierGatherer.SERVICES.stream()
				.map(service -> this.restarted.contains(service)
						? new ServiceStatus(service, List.of(start, restart), List.of(restart), Duration.ofMinutes(5))
						: new ServiceStatus(service, List.of(start), List.of(), Duration.ofHours(2)))
				.toList();
			Gathered<List<ServiceStatus>> services = this.failedServices ? failed("service_restarts")
					: new Gathered.Ok<>(statuses);
			Gathered<List<TrafficRow>> incidentRows = this.failedTraffic ? failed("traffic_counts")
					: new Gathered.Ok<>(List.copyOf(this.rows));
			List<ServiceLatency> latencies = DossierGatherer.SERVICES.stream()
				.map(service -> new ServiceLatency(service,
						this.failedLatency.contains(service) ? failed("query_metric")
								: new Gathered.Ok<>(new LatencySeries(false,
										this.latency.getOrDefault(service, List.of())))))
				.toList();
			List<ServiceLogs> logs = DossierGatherer.SERVICES.stream()
				.map(service -> new ServiceLogs(service,
						List.of(level(service, "WARN", this.warn), level(service, "ERROR", this.error))))
				.toList();
			return new Dossier(NOW, incident, previous, services, List.of(),
					new Compared<>(new Gathered.Ok<>(List.of()), incidentRows), latencies, logs, List.of(), List.of());
		}

		private LevelLogs level(String service, String level, Map<String, Long> counts) {
			Gathered<Long> count = this.failedLogs.contains(service) ? failed("count_logs")
					: new Gathered.Ok<>(counts.getOrDefault(service, 0L));
			return new LevelLogs(level, count, new Gathered.Ok<>(new LogLines(0, false, List.of())));
		}

		private static LatencyPoints points(Double mean) {
			return mean == null ? new LatencyPoints(10, 0, null, null) : new LatencyPoints(10, 10, mean, mean);
		}

		private static String outcome(String status) {
			return status.startsWith("2") ? "SUCCESS" : status.startsWith("4") ? "CLIENT_ERROR"
					: status.startsWith("5") ? "SERVER_ERROR" : "UNKNOWN";
		}

		private static <T> Gathered<T> failed(String tool) {
			return new Gathered.Failed<>(tool, ToolOutcome.UPSTREAM_ERROR, "synthetic failure");
		}

	}

}
