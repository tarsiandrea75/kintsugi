package dev.kintsugi.lab.fault.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import dev.kintsugi.lab.fault.control.FakeLabControl;
import dev.kintsugi.lab.fault.control.FakeLabControlConfiguration;

/**
 * The truth is written before the lab is touched: with the {@code faults} database gone, {@code POST /faults} answers
 * 503 and never calls the lab. This class owns its PostgreSQL container, so stopping it affects no other test.
 */
@SpringBootTest(properties = "spring.datasource.hikari.connection-timeout=1000")
@AutoConfigureMockMvc
@Import({ FakeLabControlConfiguration.class, ComposeHostMockMvc.class })
@DirtiesContext
class DatabaseDownTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@AfterAll
	static void stopDatabase() {
		POSTGRES.stop();
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private FakeLabControl lab;

	@Test
	void activationIsRefusedWith503AndTheLabIsNotTouched() throws Exception {
		POSTGRES.stop();

		String body = mvc.perform(post("/faults").contentType(MediaType.APPLICATION_JSON).content("{\"fault\": \"F1\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.error").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(lab.calls()).isEmpty();
		TruthLeaks.assertNoTruthIn(body);
	}

}
