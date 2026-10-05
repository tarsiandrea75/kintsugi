package dev.kintsugi.lab.order.api;

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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import dev.kintsugi.lab.order.domain.Order;
import dev.kintsugi.lab.order.domain.OrderItem;
import dev.kintsugi.lab.order.persistence.OrderRepository;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private OrderRepository repository;

	@Test
	void validOrderIsCreated() throws Exception {
		given(repository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

		ResultActions result = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("""
				{"customerId": "customer-1", "items": [{"sku": "SKU-1", "quantity": 2}]}
				"""));

		ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
		verify(repository).save(saved.capture());
		UUID id = saved.getValue().getId();
		result.andExpect(status().isCreated())
			.andExpect(header().string("Location", endsWith("/orders/" + id)))
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.status").value("CREATED"))
			.andExpect(jsonPath("$.customerId").value("customer-1"))
			.andExpect(jsonPath("$.items[0].sku").value("SKU-1"))
			.andExpect(jsonPath("$.items[0].quantity").value(2))
			.andExpect(jsonPath("$.createdAt").isNotEmpty())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());
	}

	@Test
	void emptyItemListIsRejected() throws Exception {
		postExpectingBadRequest("""
				{"customerId": "customer-1", "items": []}
				""");
	}

	@Test
	void missingItemListIsRejected() throws Exception {
		postExpectingBadRequest("""
				{"customerId": "customer-1"}
				""");
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void nonPositiveQuantityIsRejected(int quantity) throws Exception {
		postExpectingBadRequest("""
				{"customerId": "customer-1", "items": [{"sku": "SKU-1", "quantity": %d}]}
				""".formatted(quantity));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   " })
	void blankCustomerIdIsRejected(String customerId) throws Exception {
		postExpectingBadRequest("""
				{"customerId": "%s", "items": [{"sku": "SKU-1", "quantity": 1}]}
				""".formatted(customerId));
	}

	@Test
	void blankSkuIsRejected() throws Exception {
		postExpectingBadRequest("""
				{"customerId": "customer-1", "items": [{"sku": "", "quantity": 1}]}
				""");
	}

	@Test
	void existingOrderIsReturned() throws Exception {
		Order order = Order.create("customer-1", List.of(new OrderItem("SKU-1", 2)));
		given(repository.findById(order.getId())).willReturn(Optional.of(order));

		mvc.perform(get("/orders/{id}", order.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(order.getId().toString()))
			.andExpect(jsonPath("$.customerId").value("customer-1"))
			.andExpect(jsonPath("$.items[0].sku").value("SKU-1"))
			.andExpect(jsonPath("$.status").value("CREATED"));
	}

	@Test
	void unknownOrderIsNotFound() throws Exception {
		UUID unknown = UUID.fromString("00000000-0000-0000-0000-000000000000");
		given(repository.findById(unknown)).willReturn(Optional.empty());

		mvc.perform(get("/orders/{id}", unknown)).andExpect(status().isNotFound());
	}

	private void postExpectingBadRequest(String body) throws Exception {
		mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
		verify(repository, never()).save(any());
	}

}
