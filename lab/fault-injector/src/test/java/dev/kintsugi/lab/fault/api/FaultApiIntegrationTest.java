package dev.kintsugi.lab.fault.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import dev.kintsugi.lab.fault.TestcontainersConfiguration;
import dev.kintsugi.lab.fault.control.FakeLabControl;
import dev.kintsugi.lab.fault.control.FakeLabControlConfiguration;

/**
 * Full application context against PostgreSQL 18.6 with a {@link FakeLabControl}: each case calls the API, then checks
 * the lab calls, the {@code injections} row and its {@code injection_events}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeLabControlConfiguration.class })
class FaultApiIntegrationTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private FakeLabControl lab;

	@BeforeEach
	void emptyLabAndTables() {
		jdbc.sql("TRUNCATE injection_events, injections").update();
		lab.reset();
	}

	@Test
	void catalogListsFaultIdsOnly() throws Exception {
		mvc.perform(get("/faults/catalog"))
			.andExpect(status().isOk())
			.andExpect(content().json("[{\"fault\":\"F1\"},{\"fault\":\"F2\"},{\"fault\":\"F3\"},{\"fault\":\"F4\"}]",
					org.springframework.test.json.JsonCompareMode.STRICT));
	}

	@Test
	void activationRecordsTheTruthThenActivatesTheLab() throws Exception {
		String id = activate("F1").andExpect(status().isCreated())
			.andExpect(jsonPath("$.fault").value("F1"))
			.andExpect(jsonPath("$.injectedAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID injectionId = UUID.fromString(JsonPath.read(id, "$.id"));

		assertThat(lab.calls()).containsExactly("activate F1");
		assertThat(row(injectionId, "fault_id")).isEqualTo("F1");
		assertThat(row(injectionId, "root_cause_service")).isEqualTo("gateway-mock");
		assertThat(row(injectionId, "root_cause_category")).isEqualTo("DEPENDENCY_LATENCY");
		assertThat(row(injectionId, "parameters ->> 'latencyMs'")).isEqualTo("10000");
		assertThat(row(injectionId, "status")).isEqualTo("ACTIVE");
		assertThat(events(injectionId)).containsExactly("ACTIVATION_STARTED", "ACTIVATED");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"fault\": \"F9\"}", "{\"fault\": null}", "{}", "{\"fault\": ", "" })
	void unknownOrMissingFaultIsRejectedWithoutTouchingAnything(String body) throws Exception {
		mvc.perform(post("/faults").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").isNotEmpty());

		assertThat(lab.calls()).isEmpty();
		assertThat(injectionCount()).isZero();
	}

	@Test
	void secondActivationIsRefusedWhileOneIsActive() throws Exception {
		activate("F1").andExpect(status().isCreated());

		activate("F2").andExpect(status().isConflict()).andExpect(jsonPath("$.error").isNotEmpty());

		assertThat(lab.calls()).containsExactly("activate F1");
		assertThat(jdbc.sql("SELECT count(*) FROM injections WHERE status = 'ACTIVE'").query(Long.class).single())
			.isEqualTo(1);
	}

	@Test
	void activeAnswers204WhenNothingIsActiveAnd200WithTheInjection() throws Exception {
		mvc.perform(get("/faults/active")).andExpect(status().isNoContent()).andExpect(content().string(""));

		UUID id = idOf(activate("F2"));

		mvc.perform(get("/faults/active"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.fault").value("F2"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.injectedAt").isNotEmpty());
	}

	@Test
	void restoreRestoresTheLabAndIsIdempotent() throws Exception {
		UUID id = idOf(activate("F4"));

		String first = mvc.perform(post("/faults/{id}/restore", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RESTORED"))
			.andExpect(jsonPath("$.restoredAt").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String second = mvc.perform(post("/faults/{id}/restore", id))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(lab.calls()).containsExactly("activate F4", "restore F4");
		assertThat(events(id)).containsExactly("ACTIVATION_STARTED", "ACTIVATED", "RESTORED");
		assertThat(row(id, "restored_at")).isNotNull();
		mvc.perform(get("/faults/active")).andExpect(status().isNoContent());
	}

	@Test
	void restoreOfAnUnknownOrMalformedIdIs404() throws Exception {
		mvc.perform(post("/faults/{id}/restore", UUID.randomUUID())).andExpect(status().isNotFound());
		mvc.perform(post("/faults/{id}/restore", "not-a-uuid")).andExpect(status().isNotFound());
		assertThat(lab.calls()).isEmpty();
	}

	@Test
	void failedActivationIsRestoredAndRecordedAsFailed() throws Exception {
		lab.failActivate(true);

		String body = activate("F3").andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error").value("activation failed"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

		assertThat(lab.calls()).containsExactly("activate F3", "restore F3");
		assertThat(row(id, "status")).isEqualTo("FAILED");
		assertThat(row(id, "restored_at")).isNotNull();
		assertThat(events(id)).containsExactly("ACTIVATION_STARTED", "ACTIVATION_FAILED");
		assertThat(eventDetail(id, "ACTIVATION_FAILED")).contains("fake activation failure", "\"restore\":\"succeeded\"");

		lab.failActivate(false);
		activate("F1").andExpect(status().isCreated());
	}

	@Test
	void failedActivationWithFailedRestoreStaysBlocking() throws Exception {
		lab.failActivate(true);
		lab.failRestore(true);

		UUID id = UUID.fromString(JsonPath.read(activate("F3").andExpect(status().isInternalServerError())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id"));

		assertThat(row(id, "status")).isEqualTo("RESTORE_PENDING");
		assertThat(row(id, "restored_at")).isNull();
		assertThat(events(id)).containsExactly("ACTIVATION_STARTED", "ACTIVATION_FAILED", "RESTORE_FAILED");
		assertThat(eventDetail(id, "ACTIVATION_FAILED")).contains("fake activation failure", "\"restore\":\"failed\"");
		assertThat(eventDetail(id, "RESTORE_FAILED")).contains("fake restore failure");

		lab.reset();
		activate("F1").andExpect(status().isConflict());
		mvc.perform(get("/faults/active"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RESTORE_PENDING"));

		mvc.perform(post("/faults/{id}/restore", id)).andExpect(status().isOk());
		assertThat(row(id, "status")).isEqualTo("RESTORED");
		activate("F1").andExpect(status().isCreated());
	}

	@Test
	void failedRestoreKeepsTheInjectionBlockingAndCanBeRetried() throws Exception {
		UUID id = idOf(activate("F3"));
		lab.failRestore(true);

		mvc.perform(post("/faults/{id}/restore", id))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error").value("restore failed"))
			.andExpect(jsonPath("$.id").value(id.toString()));

		assertThat(row(id, "status")).isEqualTo("RESTORE_PENDING");
		assertThat(events(id)).containsExactly("ACTIVATION_STARTED", "ACTIVATED", "RESTORE_FAILED");
		activate("F1").andExpect(status().isConflict());

		lab.failRestore(false);
		mvc.perform(post("/faults/{id}/restore", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RESTORED"));
		assertThat(events(id)).containsExactly("ACTIVATION_STARTED", "ACTIVATED", "RESTORE_FAILED", "RESTORED");
	}

	@Test
	void restoreOfAFailedInjectionDoesNoWork() throws Exception {
		lab.failActivate(true);
		UUID id = UUID.fromString(JsonPath.read(activate("F2").andReturn().getResponse().getContentAsString(), "$.id"));
		lab.reset();

		mvc.perform(post("/faults/{id}/restore", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"));

		assertThat(lab.calls()).isEmpty();
		assertThat(events(id)).containsExactly("ACTIVATION_STARTED", "ACTIVATION_FAILED");
	}

	private ResultActions activate(String fault) throws Exception {
		return mvc.perform(post("/faults").contentType(MediaType.APPLICATION_JSON).content("{\"fault\": \"" + fault + "\"}"));
	}

	private static UUID idOf(ResultActions created) throws Exception {
		return UUID.fromString(JsonPath.read(created.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.id"));
	}

	private String row(UUID id, String column) {
		List<String> values = jdbc.sql("SELECT (" + column + ")::text FROM injections WHERE id = ?")
			.param(id)
			.query(String.class)
			.list();
		assertThat(values).hasSize(1);
		return values.get(0);
	}

	private List<String> events(UUID id) {
		return jdbc.sql("SELECT type FROM injection_events WHERE injection_id = ? ORDER BY id")
			.param(id)
			.query(String.class)
			.list();
	}

	private String eventDetail(UUID id, String type) {
		return jdbc.sql("SELECT detail FROM injection_events WHERE injection_id = ? AND type = ?")
			.params(id, type)
			.query(String.class)
			.single();
	}

	private long injectionCount() {
		return jdbc.sql("SELECT count(*) FROM injections").query(Long.class).single();
	}

}
