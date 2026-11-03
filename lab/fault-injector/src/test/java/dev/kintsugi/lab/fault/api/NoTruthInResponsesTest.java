package dev.kintsugi.lab.fault.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import com.jayway.jsonpath.JsonPath;

import dev.kintsugi.lab.fault.TestcontainersConfiguration;
import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.control.FakeLabControl;
import dev.kintsugi.lab.fault.control.FakeLabControlConfiguration;

/**
 * The truth never leaves through HTTP: every endpoint is driven through every answer it can give, for every fault, and
 * each serialized body is searched for any part of a true cause (spec, "The truth stays invisible to the agent").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, FakeLabControlConfiguration.class, ComposeHostMockMvc.class })
class NoTruthInResponsesTest {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private FakeLabControl lab;

	private final List<String> answers = new ArrayList<>();

	@BeforeEach
	void emptyLabAndTables() {
		jdbc.sql("TRUNCATE injection_events, injections").update();
		lab.reset();
	}

	@Test
	void noResponseBodyContainsAnyPartOfATrueCause() throws Exception {
		call(get("/faults/catalog"), 200);
		call(get("/faults/active"), 204);
		for (String body : List.of("{\"fault\": \"F9\"}", "{}", "{\"fault\": ", "",
				"{\"fault\": \"F1\", \"parameters\": {\"gatewayLatencyMs\": 3000}}",
				"{\"fault\": \"F3\", \"parameters\": {\"gatewayLatencyMs\": 5000}}",
				"{\"fault\": \"F2\", \"parameters\": [429]}")) {
			call(post("/faults").contentType(MediaType.APPLICATION_JSON).content(body), 400);
		}
		call(post("/faults/{id}/restore", UUID.randomUUID()), 404);
		call(post("/faults/{id}/restore", "not-a-uuid"), 404);

		for (String drawn : List.of("{\"fault\": \"F1\", \"parameters\": {\"gatewayLatencyMs\": 20000}}",
				"{\"fault\": \"F2\", \"parameters\": {\"gatewayErrorStatus\": 503}}")) {
			UUID id = id(call(post("/faults").contentType(MediaType.APPLICATION_JSON).content(drawn), 201));
			call(get("/faults/active"), 200);
			call(post("/faults/{id}/restore", id), 200);
		}

		for (Fault fault : Fault.values()) {
			UUID id = id(call(activate(fault), 201));
			call(activate(fault), 409);
			call(get("/faults/active"), 200);

			lab.failRestore(true);
			call(post("/faults/{id}/restore", id), 500);
			call(get("/faults/active"), 200);
			lab.failRestore(false);
			call(post("/faults/{id}/restore", id), 200);
			call(post("/faults/{id}/restore", id), 200);

			lab.failActivate(true);
			UUID failed = id(call(activate(fault), 500));
			call(post("/faults/{id}/restore", failed), 200);
			lab.failRestore(true);
			UUID pending = id(call(activate(fault), 500));
			lab.reset();
			call(post("/faults/{id}/restore", pending), 200);
		}

		assertThat(answers).hasSizeGreaterThan(40);
		answers.forEach(TruthLeaks::assertNoTruthIn);
	}

	/** Guards the guard: a body carrying any part of a true cause is caught. */
	@Test
	void leakDetectorCatchesEachKindOfTruth() {
		for (String leak : List.of("{\"target\":\"x\"}", "{\"x\":\"gateway-mock\"}", "{\"x\":\"CONFIGURATION\"}",
				"{\"x\":\"http://payment-service:9999\"}", "{\"x\":\"INVENTORY_POOL_SIZE\"}")) {
			assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> TruthLeaks.assertNoTruthIn(leak));
		}
	}

	private MockHttpServletResponse call(RequestBuilder request, int expectedStatus) throws Exception {
		MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
		assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
		answers.add(response.getContentAsString());
		response.getHeaderNames().forEach(name -> answers.add(name + ": " + response.getHeaders(name)));
		return response;
	}

	private static RequestBuilder activate(Fault fault) {
		return post("/faults").contentType(MediaType.APPLICATION_JSON).content("{\"fault\": \"" + fault.id() + "\"}");
	}

	private static UUID id(MockHttpServletResponse response) throws Exception {
		return UUID.fromString(JsonPath.read(response.getContentAsString(), "$.id"));
	}

}
