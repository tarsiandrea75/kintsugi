package dev.kintsugi.lab.gateway.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Full application context: an error status set through the admin API is what {@code POST /authorize} answers. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizeErrorIntegrationTest {

	private static final String AUTHORIZE_BODY = """
			{"orderId": "44444444-4444-4444-4444-444444444444"}
			""";

	@Autowired
	private MockMvc mvc;

	@AfterEach
	void resetBehavior() throws Exception {
		mvc.perform(delete("/admin/behavior")).andExpect(status().isOk());
	}

	@Test
	void authorizeAnswers429WhileRateLimitedAndRecoversAfterAReset() throws Exception {
		mvc.perform(put("/admin/behavior").contentType(MediaType.APPLICATION_JSON).content("""
				{"latencyMs": 0, "errorStatus": 429}
				""")).andExpect(status().isOk());

		mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content(AUTHORIZE_BODY))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.error").value("rate limited"));

		mvc.perform(delete("/admin/behavior")).andExpect(status().isOk());

		mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content(AUTHORIZE_BODY))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("AUTHORIZED"));
	}

}
