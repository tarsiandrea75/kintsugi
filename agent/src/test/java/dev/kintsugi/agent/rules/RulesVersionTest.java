package dev.kintsugi.agent.rules;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static dev.kintsugi.agent.rules.Resources.allSeries;
import static dev.kintsugi.agent.rules.Resources.bare;
import static dev.kintsugi.agent.rules.Resources.golden;
import static dev.kintsugi.agent.rules.Resources.latency;
import static dev.kintsugi.agent.rules.Resources.metric;
import static dev.kintsugi.agent.rules.Resources.service;
import static dev.kintsugi.agent.rules.Resources.steady;
import static dev.kintsugi.agent.rules.Resources.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import dev.kintsugi.agent.dossier.Dossier;
import dev.kintsugi.agent.dossier.Dossier.ServiceResources;
import dev.kintsugi.agent.dossier.Gathered;
import dev.kintsugi.agent.dossier.Topology;

/**
 * The rules versions (ADR-021, "Selection", "The rule", "Candidates"; odd/tasks/resource-facts.md T5, AC4): {@code v1}
 * is Config 0 exactly; {@code v2} is Config 0R, which answers where Config 0 answers and adds R-RES only where it
 * abstains, and whose candidates are Config 0's list followed by the resource-abnormal services. On the lab goldens,
 * whose resource facts are failed, the two are equal.
 */
class RulesVersionTest {

	private static final String CPU = "processor_usage";

	@Test
	void theVersionIsV1OrV2AndAnyOtherValueFailsNamingTheProperty() {
		assertThat(RulesVersion.of("v1")).isEqualTo(RulesVersion.V1);
		assertThat(RulesVersion.of("v2")).isEqualTo(RulesVersion.V2);
		assertThat(List.of(RulesVersion.values())).extracting(RulesVersion::value).containsExactly("v1", "v2");
		assertThat(List.of(RulesVersion.values())).extracting(RulesVersion::model)
			.containsExactly("rules-v1", "rules-v2");
		for (String value : List.of("v3", "", "V2", "rules-v2")) {
			assertThatThrownBy(() -> RulesVersion.of(value)).as("\"%s\"", value)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("kintsugi.agent.rules must be v1 or v2, got \"" + value + "\"");
		}
		assertThatThrownBy(() -> RulesVersion.of(null)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("kintsugi.agent.rules must be v1 or v2, got null");
	}

	@Test
	void theVersionIsSelectedByItsVariableAndIsV1ByDefault() throws IOException {
		assertThat(RulesVersion.PROPERTY).isEqualTo("kintsugi.agent.rules");
		assertThat(RulesVersion.of(RulesVersion.DEFAULT)).isEqualTo(RulesVersion.V1);
		assertThat(bound(Map.of())).isEqualTo(RulesVersion.DEFAULT);
		assertThat(bound(Map.of("KINTSUGI_AGENT_RULES", "v2"))).isEqualTo("v2");
	}

	@Test
	void v1IsConfig0OnEveryGoldenDossierAndIgnoresTheResources() {
		for (Dossier dossier : labGoldens()) {
			assertThat(RulesVersion.V1.diagnose(dossier, LAB)).isEqualTo(RulesDiagnosis.diagnose(dossier, LAB));
			assertThat(RulesVersion.V1.candidates(dossier, LAB)).isEqualTo(RulesDiagnosis.candidates(dossier, LAB));
		}
		Dossier abnormal = with(golden(LAB, "healthy"), service("payment-service", metric(CPU, steady(0.1, 0.1, 0.9))));
		assertThat(RulesVersion.V1.diagnose(abnormal, LAB).rule()).isEqualTo("R-NONE");
		assertThat(RulesVersion.V1.candidates(abnormal, LAB)).isEmpty();
	}

	@Test
	void onTheLabGoldensWhoseResourceFactsFailedV2EqualsV1() {
		// AC4: the committed lab outputs predate the resource calls, so every resource fact is failed and R-RES is mute.
		for (Dossier dossier : labGoldens()) {
			assertThat(allSeries(dossier)).isNotEmpty().allMatch(series -> series instanceof Gathered.Failed);
			assertThat(RulesVersion.V2.diagnose(dossier, LAB)).isEqualTo(RulesVersion.V1.diagnose(dossier, LAB));
			assertThat(RulesVersion.V2.candidates(dossier, LAB)).isEqualTo(RulesVersion.V1.candidates(dossier, LAB));
		}
	}

	@Test
	void whereV1AnswersV2GivesTheSameVerdictWhateverTheResources() {
		ServiceResources[] abnormal = { service("payment-service", metric(CPU, steady(0.1, 0.1, 0.9))),
				service("gateway-mock", metric(CPU, steady(0.1, 0.1, 0.5))),
				service("order-service", metric(CPU, steady(0.1, 0.1, 0.5))) };
		for (Dossier golden : labGoldens()) {
			RulesVerdict v1 = RulesVersion.V1.diagnose(golden, LAB);
			if (v1.service().equals("UNKNOWN")) {
				continue;
			}
			Dossier dossier = with(golden, abnormal);
			assertThat(RulesVersion.V2.diagnose(dossier, LAB)).isEqualTo(v1).isEqualTo(RulesDiagnosis.diagnose(dossier,
					LAB));
		}
	}

	@Test
	void whereV1AbstainsV2NamesTheResourceAbnormalService() {
		Dossier dossier = with(golden(LAB, "healthy"), service("payment-service", metric(CPU, steady(0.1, 0.1, 0.9))));
		assertThat(RulesVersion.V1.diagnose(dossier, LAB).rule()).isEqualTo("R-NONE");

		assertThat(RulesVersion.V2.diagnose(dossier, LAB))
			.extracting(RulesVerdict::service, RulesVerdict::category, RulesVerdict::rule)
			.containsExactly("payment-service", "RESOURCE_EXHAUSTION", "R-RES");
		assertThat(RulesVersion.V2.candidates(dossier, LAB))
			.extracting(RulesCandidate::service, RulesCandidate::category, RulesCandidate::rule)
			.containsExactly(tuple("payment-service", "RESOURCE_EXHAUSTION", "R-RES"));
	}

	@Test
	void v2CandidatesAreConfig0sListThenTheResourceAbnormalServicesByRatioNoServiceTwice() {
		Dossier f1 = golden(LAB, "f1", "healthy");
		List<RulesCandidate> v1 = RulesVersion.V1.candidates(f1, LAB);
		assertThat(v1).extracting(RulesCandidate::service).contains("gateway-mock").doesNotContain("order-service");
		// gateway-mock is already listed, by R-DOWN: it keeps its place and its category, and is not added again.
		Dossier dossier = with(f1, service("order-service", metric(CPU, steady(0.1, 0.1, 0.25))),
				service("gateway-mock", metric(CPU, steady(0.1, 0.1, 0.9))));

		List<RulesCandidate> v2 = RulesVersion.V2.candidates(dossier, LAB);

		List<RulesCandidate> expected = new ArrayList<>(v1);
		expected.add(new RulesCandidate("order-service", "RESOURCE_EXHAUSTION", "R-RES",
				RulesVersion.V2.diagnose(with(golden(LAB, "healthy"),
						service("order-service", metric(CPU, steady(0.1, 0.1, 0.25)))), LAB).facts()));
		assertThat(v2).isEqualTo(expected.subList(0, Math.min(expected.size(), 5)));
		assertThat(v2).extracting(RulesCandidate::service).doesNotHaveDuplicates();
	}

	@Test
	void whenConfig0AbstainsWithCandidatesV2sVerdictFollowsThemInTheList() {
		// gateway-mock is slow but no walk from order-service reaches it: Config 0 abstains and still lists it
		// (ADR-019). Config 0R's verdict is then R-RES's, and it comes after Config 0's list (ADR-021, "Candidates").
		Dossier dossier = bare(List.of(latency("gateway-mock", "/authorize", 0.01, 8.9)),
				service("inventory-service", metric(CPU, steady(0.1, 0.1, 0.4))));
		assertThat(RulesVersion.V1.diagnose(dossier, LAB).rule()).isEqualTo("R-NONE");

		assertThat(RulesVersion.V2.diagnose(dossier, LAB))
			.extracting(RulesVerdict::service, RulesVerdict::rule)
			.containsExactly("inventory-service", "R-RES");
		assertThat(RulesVersion.V2.candidates(dossier, LAB))
			.extracting(RulesCandidate::service, RulesCandidate::category, RulesCandidate::rule)
			.containsExactly(tuple("gateway-mock", "DEPENDENCY_LATENCY", "R-DOWN"),
					tuple("inventory-service", "RESOURCE_EXHAUSTION", "R-RES"));
	}

	@Test
	void v2CandidatesStayCappedAtFiveLargestRatioFirst() {
		Topology online = Topology.load(new ClassPathResource("topology/re2-ob.json"));
		List<String> services = online.services();
		List<ServiceResources> resources = new ArrayList<>();
		// Ratios 2.1, 2.2, ... 2.7 for the first seven services: the last five, largest first.
		for (int i = 0; i < 7; i++) {
			resources.add(service(services.get(i), metric(CPU, steady(1.0, 1.0, 2.1 + 0.1 * i))));
		}
		Dossier dossier = bare(resources.toArray(ServiceResources[]::new));
		assertThat(RulesVersion.V1.candidates(dossier, online)).isEmpty();

		assertThat(RulesVersion.V2.candidates(dossier, online)).extracting(RulesCandidate::service)
			.containsExactly(services.get(6), services.get(5), services.get(4), services.get(3), services.get(2));
		assertThat(RulesVersion.V2.diagnose(dossier, online).service()).isEqualTo(services.get(6));
	}

	private static List<Dossier> labGoldens() {
		return List.of(golden(LAB, "f1", "healthy"), golden(LAB, "f3"), golden(LAB, "healthy"));
	}

	/** {@code kintsugi.agent.rules} as {@code application.yaml} binds it over these environment variables. */
	private static String bound(Map<String, Object> variables) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("environment", variables));
		new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"))
			.forEach(environment.getPropertySources()::addLast);
		return Binder.get(environment).bind(RulesVersion.PROPERTY, String.class).get();
	}

}
