package dev.kintsugi.lab.payment.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.kintsugi.lab.payment.application.PaymentService;
import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.gateway.GatewayUnavailableException;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

	private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private PaymentService service;

	@ParameterizedTest
	@EnumSource(PaymentStatus.class)
	void validPaymentIsCreated(PaymentStatus outcome) throws Exception {
		Payment payment = Payment.create(ORDER_ID, outcome);
		given(service.pay(ORDER_ID)).willReturn(payment);

		mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
				{"orderId": "%s"}
				""".formatted(ORDER_ID)))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", endsWith("/payments/" + payment.getId())))
			.andExpect(jsonPath("$.id").value(payment.getId().toString()))
			.andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
			.andExpect(jsonPath("$.status").value(outcome.name()))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.version").doesNotExist());
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"orderId\": null}", "{\"orderId\": \"not-a-uuid\"}", "{\"orderId\": 42}",
			"{\"orderId\": \"" })
	void invalidBodyIsRejected(String body) throws Exception {
		mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
		verify(service, never()).pay(any());
	}

	@Test
	void unavailableGatewayIsServiceUnavailable() throws Exception {
		given(service.pay(ORDER_ID)).willThrow(new GatewayUnavailableException("gateway timed out", null));

		mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isServiceUnavailable());
	}

	@Test
	void otherFailuresAreNotServiceUnavailable() {
		given(service.pay(ORDER_ID)).willThrow(new IllegalStateException("bug"));

		// No handler maps it, so MockMvc rethrows it; a running server answers 500, not 503.
		assertThatThrownBy(
				() -> mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(validBody())))
			.hasRootCauseInstanceOf(IllegalStateException.class);
	}

	@Test
	void existingPaymentIsReturned() throws Exception {
		Payment payment = Payment.create(ORDER_ID, PaymentStatus.AUTHORIZED);
		given(service.find(payment.getId())).willReturn(Optional.of(payment));

		mvc.perform(get("/payments/{id}", payment.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(payment.getId().toString()))
			.andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andExpect(jsonPath("$.createdAt").isNotEmpty());
	}

	@Test
	void unknownPaymentIsNotFound() throws Exception {
		UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000000");
		given(service.find(unknown)).willReturn(Optional.empty());

		mvc.perform(get("/payments/{id}", unknown)).andExpect(status().isNotFound());
	}

	@Test
	void nonUuidIdIsRejected() throws Exception {
		mvc.perform(get("/payments/not-a-uuid")).andExpect(status().isBadRequest());
		verify(service, never()).find(any());
	}

	private static String validBody() {
		return """
				{"orderId": "%s"}
				""".formatted(ORDER_ID);
	}

}
