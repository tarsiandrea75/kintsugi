package dev.kintsugi.agent.loop;

import static dev.kintsugi.agent.dossier.LabTopology.LAB;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.Phase;

/**
 * Pins what the model reads and what code checks against for the lab topology (odd/tasks/topology-data.md T4): the
 * system message, every report schema that holds the cause enum, and the enum errors of the plan and report checks.
 * The expected values were captured from the code before T4 derived them from the topology, under
 * {@code src/test/resources/topology-pins}; they change only when a text changes on purpose, by that text alone (the
 * system message gained the resource warrant in odd/tasks/resource-warrant.md T3; the message and the category enums
 * gained NETWORK and CODE_DEFECT in odd/tasks/decoy-categories.md T2). Only the helpers at the bottom name the API, so a
 * signature change touches them and nothing else.
 */
class LabTopologyPinsTest {

	private static final JsonMapper JSON = JsonMapper.shared();

	@Test
	void theSystemMessageIsTodays() {
		assertThat(system("orders are slow", 15)).isEqualTo(pin("system-orders-are-slow-15.txt"));
		assertThat(system("checkout fails: 100% \"errors\" on %s", 30)).isEqualTo(pin("system-quoted-percent-30.txt"));
	}

	@Test
	void theReportSchemasAreTodays() {
		assertThat(reportSchema(AgentMode.EXPLORER)).isEqualTo(pin("schema-report-explorer.json"));
		assertThat(reportSchema(AgentMode.DOSSIER)).isEqualTo(pin("schema-report-dossier.json"));
		assertThat(reportSchema(AgentMode.DECISION)).isEqualTo(pin("schema-report-decision.json"));
		assertThat(assessedSchema()).isEqualTo(pin("schema-assessed-report.json"));
	}

	@Test
	void theCauseEnumErrorsAreTodays() {
		String plan = "{\"hypotheses\":[{\"rootCauseService\":\"db\",\"rootCauseCategory\":\"CONFIGURATION\","
				+ "\"why\":\"x\"}],\"nextTools\":[]}";
		assertThat(planErrors(JSON.readTree(plan))).containsExactly("hypotheses[0].rootCauseService must be one of "
				+ "gateway-mock, payment-service, order-service, inventory-service, postgres, UNKNOWN; got \"db\"");
		String report = "{\"summary\":\"s\",\"evidence\":[],\"rootCauseService\":\"db\","
				+ "\"rootCauseCategory\":\"UNKNOWN\",\"confidence\":0.5}";
		assertThat(reportErrors(JSON.readTree(report))).contains("rootCauseService must be one of "
				+ "gateway-mock, payment-service, order-service, inventory-service, postgres, UNKNOWN; got \"db\"");
	}

	private static String pin(String name) {
		try (InputStream in = LabTopologyPinsTest.class.getResourceAsStream("/topology-pins/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String system(String symptom, int sinceMinutes) {
		return Prompts.system(LAB, symptom, sinceMinutes);
	}

	private static String reportSchema(AgentMode mode) {
		return Schemas.report(mode, LAB);
	}

	private static String assessedSchema() {
		return Schemas.assessedReport(LAB);
	}

	private static List<String> planErrors(JsonNode plan) {
		return PlanCheck.check(plan, Phase.INVESTIGATE, LAB).errors();
	}

	private static List<String> reportErrors(JsonNode report) {
		return ReportCheck.check(report, Map.of(), LAB).errors();
	}

}
