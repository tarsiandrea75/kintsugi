package dev.kintsugi.lab.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.payment.TestcontainersConfiguration;
import dev.kintsugi.lab.payment.domain.PaymentEvent;

@DataJdbcTest
@Import({ TestcontainersConfiguration.class, PaymentEventRepository.class })
class PaymentEventRepositoryTest {

	@Autowired
	private PaymentEventRepository repository;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void insertedEventIsReadBackByOrderId() {
		UUID orderId = UUID.randomUUID();
		PaymentEvent event = new PaymentEvent(UUID.randomUUID(), orderId, Instant.now().truncatedTo(ChronoUnit.MICROS),
				PaymentEvent.PAYMENT_ATTEMPT, PaymentEvent.GATEWAY_UNAVAILABLE,
				"{\"exception\":\"java.net.http.HttpTimeoutException\",\"url\":\"http://localhost:8082/authorize\"}",
				15_001L);

		repository.insert(event);

		assertThat(repository.findByOrderId(orderId)).containsExactly(event);
		assertThat(repository.findByOrderId(UUID.randomUUID())).isEmpty();
	}

	/**
	 * The table is shared with every other test, so {@code latest} is checked on this order's rows only. They are dated
	 * a year ahead, so no other row can be newer and push them out of the window.
	 */
	@Test
	void eventsOfAnOrderAreInTimeOrderAndLatestIsNewestFirst() {
		UUID orderId = UUID.randomUUID();
		Instant ahead = Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
		PaymentEvent older = event(orderId, ahead.minusSeconds(1));
		PaymentEvent newer = event(orderId, ahead);

		repository.insert(newer);
		repository.insert(older);

		assertThat(repository.findByOrderId(orderId)).containsExactly(older, newer);
		assertThat(repository.latest(2).stream().filter(event -> event.orderId().equals(orderId)))
			.containsExactly(newer, older);
	}

	@Test
	void unknownTypeIsRejectedByTheSchema() {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc
			.sql("insert into payment_events (id, order_id, occurred_at, type) values (?, ?, now(), 'OTHER')")
			.params(UUID.randomUUID(), UUID.randomUUID())
			.update());
	}

	/** The event table is append-only: the repository offers insert and reads, never an update or a delete. */
	@Test
	void repositoryExposesNoUpdateOrDelete() {
		List<String> publicMethods = Arrays.stream(PaymentEventRepository.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
			.map(Method::getName)
			.toList();

		assertThat(publicMethods).containsExactlyInAnyOrder("insert", "findByOrderId", "latest");
		assertThat(publicMethods)
			.noneMatch(name -> name.matches("(?i).*(update|delete|remove|save|merge|upsert|truncate|clear).*"));
	}

	private static PaymentEvent event(UUID orderId, Instant occurredAt) {
		return new PaymentEvent(UUID.randomUUID(), orderId, occurredAt, PaymentEvent.PAYMENT_ATTEMPT, "AUTHORIZED",
				"{}", 1L);
	}

}
