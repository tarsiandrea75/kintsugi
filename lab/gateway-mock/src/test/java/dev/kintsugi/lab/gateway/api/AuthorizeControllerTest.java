package dev.kintsugi.lab.gateway.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import dev.kintsugi.lab.gateway.behavior.BehaviorStore;

@WebMvcTest(AuthorizeController.class)
@Import(BehaviorStore.class)
class AuthorizeControllerTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void validOrderIsAuthorized() throws Exception {
		mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId": "11111111-1111-1111-1111-111111111111"}
				"""))
			.andExpect(status().isOk())
			.andExpect(content().json("""
					{"status": "AUTHORIZED"}
					""", true));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"orderId\": null}", "{\"orderId\": \"not-a-uuid\"}", "{\"orderId\": \"" })
	void invalidBodyIsRejected(String body) throws Exception {
		mvc.perform(post("/authorize").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
	}

}
