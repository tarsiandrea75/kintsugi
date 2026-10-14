package dev.kintsugi.agent.loop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The checks code makes on a tool call's arguments before it runs (T5c9; agent spec, "Phases and their tools"): a
 * placeholder is not a value, and a call identical to an earlier one would only return the same result.
 */
class ToolArgsTest {

	@Test
	void aWholeValueInAngleBracketsIsAPlaceholder() {
		assertThat(ToolArgs.placeholder("{\"orderId\":\"<SELECT_A_PENDING_ORDER_ID>\"}")).isEqualTo("orderId");
		assertThat(ToolArgs.placeholder("{\"limit\":5,\"orderId\":\" <order id> \"}")).isEqualTo("orderId");
		assertThat(ToolArgs.placeholder("{\"filters\":{\"ids\":[\"x\",\"<ID_1>\"]}}")).isEqualTo("filters.ids[1]");
	}

	@Test
	void aRealValueIsNotAPlaceholder() {
		assertThat(ToolArgs.placeholder("{\"orderId\":\"01935dff-d281-47b0-943d-28b4bbfc0b5c\"}")).isNull();
		assertThat(ToolArgs.placeholder("{\"contains\":\"a <b> tag\"}")).isNull();
		assertThat(ToolArgs.placeholder("{\"contains\":\"<>\"}")).isNull();
		assertThat(ToolArgs.placeholder("{\"contains\":\"<a-b>\"}")).isNull();
		assertThat(ToolArgs.placeholder("{}")).isNull();
		assertThat(ToolArgs.placeholder(null)).isNull();
		assertThat(ToolArgs.placeholder("not json")).isNull();
	}

	@Test
	void argumentsAreComparedAsJsonWhateverTheKeyOrderOrSpacing() {
		assertThat(ToolArgs.same("{\"source\":\"payment\",\"limit\":20}", "{ \"limit\": 20, \"source\": \"payment\" }"))
			.isTrue();
		assertThat(ToolArgs.same("{\"a\":{\"x\":1,\"y\":2}}", "{\"a\":{\"y\":2,\"x\":1}}")).isTrue();
		assertThat(ToolArgs.same("", "{}")).isTrue();
		assertThat(ToolArgs.same(null, "  ")).isTrue();
	}

	@Test
	void differentValuesOrKeysAreDifferentCalls() {
		assertThat(ToolArgs.same("{\"limit\":20}", "{\"limit\":50}")).isFalse();
		assertThat(ToolArgs.same("{\"limit\":20}", "{\"limit\":\"20\"}")).isFalse();
		assertThat(ToolArgs.same("{\"limit\":20}", "{\"limit\":20,\"level\":\"WARN\"}")).isFalse();
		assertThat(ToolArgs.same("{\"ids\":[1,2]}", "{\"ids\":[2,1]}")).isFalse();
		assertThat(ToolArgs.same("not json", "not json")).isTrue();
		assertThat(ToolArgs.same("not json", "{}")).isFalse();
	}

}
