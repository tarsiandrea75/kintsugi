package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.lab.Phase;

/**
 * The two routes' options (agent spec, "Routes"): the local route's are exactly those of every run before the cloud
 * route existed, and the cloud route's carry no temperature, no thinking and the same answer caps.
 */
class ModelRouteTest {

	private static final NumPredict CAPS = new NumPredict(384, 160, 512);

	private static final CallTimeouts DEADLINES = new CallTimeouts(Duration.ofSeconds(420), Duration.ofSeconds(420));

	private static final List<ToolCallback> TOOLS = new FakeLabTools().toolsFor(Phase.TRIAGE);

	private static final String SCHEMA = Schemas.report(AgentMode.EXPLORER);

	private final ScriptedChatModel model = new ScriptedChatModel();

	private final OllamaRoute local = new OllamaRoute("granite4.1:8b", this.model, 0.0, 8192, DEADLINES, CAPS);

	private final AnthropicRoute cloud = new AnthropicRoute("claude-haiku-5-5", this.model,
			new CallTimeouts(Duration.ofSeconds(60), Duration.ofSeconds(90)), CAPS);

	@Test
	void theLocalRoutesCallOptionsAreThoseOfToday() {
		ChatOptions act = this.local.options(384, Duration.ofSeconds(420), TOOLS, null);
		ChatOptions report = this.local.options(512, Duration.ofSeconds(420), List.of(), SCHEMA);

		assertThat(act).isEqualTo(OllamaChatOptions.builder()
			.model("granite4.1:8b")
			.temperature(0.0)
			.numCtx(8192)
			.numPredict(384)
			.toolCallbacks(TOOLS)
			.build());
		assertThat(report).isEqualTo(OllamaChatOptions.builder()
			.model("granite4.1:8b")
			.temperature(0.0)
			.numCtx(8192)
			.numPredict(512)
			.toolCallbacks(List.of())
			.outputSchema(SCHEMA)
			.build());
		OllamaChatOptions options = (OllamaChatOptions) act;
		assertThat(options.getModel()).isEqualTo("granite4.1:8b");
		assertThat(options.getTemperature()).isEqualTo(0.0);
		assertThat(options.getNumCtx()).isEqualTo(8192);
		assertThat(options.getNumPredict()).isEqualTo(384);
		assertThat(options.getOutputSchema()).isNull();
		assertThat(((OllamaChatOptions) report).getOutputSchema()).isEqualTo(SCHEMA);
	}

	@Test
	void theLocalRoutesWarmUpIsThatOfToday() {
		assertThat(this.local.warmUpOptions()).hasValue(OllamaChatOptions.builder()
			.model("granite4.1:8b")
			.temperature(0.0)
			.numCtx(8192)
			.numPredict(1)
			.build());
	}

	@Test
	void theCloudRoutesOptionsHaveNoTemperatureNoThinkingAndTheSameCaps() {
		for (int cap : List.of(CAPS.firstAct(), CAPS.act(), CAPS.report())) {
			AnthropicChatOptions options = (AnthropicChatOptions) this.cloud.options(cap, Duration.ofSeconds(60), TOOLS,
					null);

			assertThat(options.getModel()).isEqualTo("claude-haiku-5-5");
			assertThat(options.getTemperature()).isNull();
			assertThat(options.getThinking()).isNotNull();
			assertThat(options.getThinking().isDisabled()).isTrue();
			assertThat(options.getMaxTokens()).isEqualTo(cap);
			assertThat(options.getToolCallbacks()).isEqualTo(TOOLS);
			assertThat(options.getOutputSchema()).isNull();
			assertThat(options.getTimeout()).isEqualTo(Duration.ofSeconds(61));
		}
	}

	@Test
	void theCloudRoutesReportCarriesTheSchema() {
		AnthropicChatOptions options = (AnthropicChatOptions) this.cloud.options(512, Duration.ofMillis(42_500),
				List.of(), SCHEMA);

		assertThat(options.getMaxTokens()).isEqualTo(512);
		assertThat(options.getThinking().isDisabled()).isTrue();
		assertThat(options.getTemperature()).isNull();
		assertThat(options.getToolCallbacks()).isEmpty();
		// The loop's wait, here the incident's time left, plus the grace: the client cancels the call just after the
		// loop stops waiting for it.
		assertThat(options.getTimeout()).isEqualTo(Duration.ofMillis(43_500));
		JsonNode sent = JsonMapper.shared().readTree(options.getOutputSchema());
		assertThat(sent).isEqualTo(JsonMapper.shared().readTree(AnthropicRoute.cloudSchema(SCHEMA)));
	}

	/**
	 * The schema the cloud route sends has none of the keywords Anthropic's structured outputs reject (the first live
	 * cloud run: HTTP 400 on every report), keeps the rest, and closes every object; ReportCheck enforces the dropped
	 * constraints on both routes.
	 */
	@ParameterizedTest
	@EnumSource(AgentMode.class)
	void theCloudRoutesSchemaHasOnlyWhatAnthropicAccepts(AgentMode mode) {
		String schema = Schemas.report(mode);
		AnthropicChatOptions options = (AnthropicChatOptions) this.cloud.options(512, Duration.ofSeconds(90),
				List.of(), schema);
		JsonNode sent = JsonMapper.shared().readTree(options.getOutputSchema());
		JsonNode original = JsonMapper.shared().readTree(schema);

		assertThat(keywords(original)).contains("minimum", "maximum", "minLength", "maxLength", "maxItems");
		assertThat(keywords(sent)).doesNotContain("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
				"multipleOf", "minLength", "maxLength", "maxItems");
		assertThat(minItems(sent)).allMatch(value -> value <= 1);
		assertThat(objects(sent)).isNotEmpty()
			.allMatch(object -> object.path("additionalProperties").isBoolean()
					&& !object.path("additionalProperties").booleanValue());
		assertThat(sent.path("required")).isEqualTo(original.path("required"));
		assertThat(sent.path("properties").path("rootCauseService").path("enum"))
			.isEqualTo(original.path("properties").path("rootCauseService").path("enum"));
		assertThat(sent.path("properties").path("rootCauseCategory").path("enum"))
			.isEqualTo(original.path("properties").path("rootCauseCategory").path("enum"));
		assertThat(sent.path("properties").path("confidence").path("type").asString()).isEqualTo("number");
		JsonNode item = sent.path("properties").path("evidence").path("items");
		assertThat(sent.path("properties").path("evidence").path("type").asString()).isEqualTo("array");
		assertThat(item.path("required")).isEqualTo(
				original.path("properties").path("evidence").path("items").path("required"));
		// Evidence-by-reference: the item cites a line by its number, of at least 1 only by ReportCheck here.
		assertThat(item.path("properties").path("line").path("type").asString()).isEqualTo("integer");
		assertThat(item.path("properties").has("quote")).isFalse();
		assertThat(item.path("properties").path("line").has("minimum")).isFalse();
		if (mode == AgentMode.DOSSIER) {
			assertThat(item.path("properties").path("toolCallId").path("anyOf").get(1))
				.isEqualTo(original.path("properties").path("evidence").path("items").path("properties")
					.path("toolCallId").path("anyOf").get(1));
		}
		// The local route still sends the schema as it is.
		assertThat(((OllamaChatOptions) this.local.options(512, Duration.ofSeconds(420), List.of(), schema))
			.getOutputSchema()).isEqualTo(schema);
	}

	/**
	 * The assessed report's schema (service-assessment T2) on both routes: the cloud route's keeps the order, the enums
	 * and the required fields, without what Anthropic rejects and with every object closed, the assessment of each
	 * service included; the local route's is the schema as it is.
	 */
	@Test
	void theAssessedSchemaKeepsItsOrderOnBothRoutes() {
		String schema = Schemas.ASSESSED_REPORT;
		JsonNode sent = JsonMapper.shared().readTree(((AnthropicChatOptions) this.cloud.options(768,
				Duration.ofSeconds(90), List.of(), schema)).getOutputSchema());

		assertThat(keywords(sent)).doesNotContain("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
				"multipleOf", "minLength", "maxLength", "maxItems");
		assertThat(objects(sent)).hasSize(6)
			.allMatch(object -> object.path("additionalProperties").isBoolean()
					&& !object.path("additionalProperties").booleanValue());
		assertThat(sent.get("properties").propertyNames()).containsExactly("assessments", "summary",
				"rootCauseService", "rootCauseCategory", "confidence");
		JsonNode assessments = sent.at("/properties/assessments");
		assertThat(assessments.get("properties").propertyNames()).containsExactly("gateway-mock", "inventory-service",
				"payment-service", "order-service");
		assertThat(assessments.get("required")).isEqualTo(JsonMapper.shared().readTree(schema)
			.at("/properties/assessments/required"));
		assertThat(assessments.at("/properties/payment-service/properties").propertyNames()).containsExactly("role",
				"line", "why");
		assertThat(assessments.at("/properties/payment-service/properties/role/enum")).extracting(JsonNode::asString)
			.containsExactly("ORIGIN", "AFFECTED", "UNAFFECTED");
		assertThat(assessments.at("/properties/payment-service/properties/line/type").asString()).isEqualTo("integer");
		assertThat(((OllamaChatOptions) this.local.options(512, Duration.ofSeconds(420), List.of(), schema))
			.getOutputSchema()).isEqualTo(schema);
	}

	private static Set<String> keywords(JsonNode node) {
		Set<String> found = new HashSet<>();
		walk(node, child -> child.propertyNames().forEach(found::add));
		return found;
	}

	private static List<Integer> minItems(JsonNode node) {
		List<Integer> found = new ArrayList<>();
		walk(node, child -> {
			if (child.has("minItems")) {
				found.add(child.get("minItems").intValue());
			}
		});
		return found;
	}

	private static List<JsonNode> objects(JsonNode node) {
		List<JsonNode> found = new ArrayList<>();
		walk(node, child -> {
			if ("object".equals(child.path("type").asString(""))) {
				found.add(child);
			}
		});
		return found;
	}

	/** Visits every object node of a schema, its property maps included. */
	private static void walk(JsonNode node, Consumer<JsonNode> visit) {
		if (node.isObject()) {
			visit.accept(node);
		}
		for (JsonNode child : node) {
			walk(child, visit);
		}
	}

	@Test
	void theCloudRouteHasNoWarmUp() {
		assertThat(this.cloud.warmUpOptions()).isEmpty();
	}

	@Test
	void eachRouteNamesItselfAndItsModel() {
		assertThat(this.local.route()).isEqualTo(AgentRoute.LOCAL);
		assertThat(this.local.model()).isEqualTo("granite4.1:8b");
		assertThat(this.cloud.route()).isEqualTo(AgentRoute.CLOUD);
		assertThat(this.cloud.model()).isEqualTo("claude-haiku-5-5");
	}

}
