package dev.kintsugi.lab.inventory.persistence;

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

import dev.kintsugi.lab.inventory.TestcontainersConfiguration;
import dev.kintsugi.lab.inventory.domain.ReservationEvent;

@DataJdbcTest
@Import({ TestcontainersConfiguration.class, ReservationEventRepository.class })
class ReservationEventRepositoryTest {

	@Autowired
	private ReservationEventRepository repository;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void insertedEventIsReadBackByOrderId() {
		UUID orderId = UUID.randomUUID();
		ReservationEvent event = new ReservationEvent(UUID.randomUUID(), orderId,
				Instant.now().truncatedTo(ChronoUnit.MICROS), ReservationEvent.RESERVATION_ATTEMPT, "RESERVED",
				"[{\"sku\":\"SKU-1\",\"quantity\":2}]", 12L);

		repository.insert(event);

		assertThat(repository.findByOrderId(orderId)).containsExactly(event);
		assertThat(repository.findByOrderId(UUID.randomUUID())).isEmpty();
	}

	@Test
	void eventsOfAnOrderAreInTimeOrderAndLatestIsNewestFirst() {
		UUID orderId = UUID.randomUUID();
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		ReservationEvent older = event(orderId, now.minusSeconds(1));
		ReservationEvent newer = event(orderId, now);

		repository.insert(newer);
		repository.insert(older);

		assertThat(repository.findByOrderId(orderId)).containsExactly(older, newer);
		assertThat(repository.latest(1)).containsExactly(newer);
	}

	@Test
	void unknownTypeIsRejectedByTheSchema() {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc
			.sql("insert into reservation_events (id, order_id, occurred_at, type) values (?, ?, now(), 'OTHER')")
			.params(UUID.randomUUID(), UUID.randomUUID())
			.update());
	}

	/** The event table is append-only: the repository offers insert and reads, never an update or a delete. */
	@Test
	void repositoryExposesNoUpdateOrDelete() {
		List<String> publicMethods = Arrays.stream(ReservationEventRepository.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
			.map(Method::getName)
			.toList();

		assertThat(publicMethods).containsExactlyInAnyOrder("insert", "findByOrderId", "latest");
		assertThat(publicMethods)
			.noneMatch(name -> name.matches("(?i).*(update|delete|remove|save|merge|upsert|truncate|clear).*"));
	}

	private static ReservationEvent event(UUID orderId, Instant occurredAt) {
		return new ReservationEvent(UUID.randomUUID(), orderId, occurredAt, ReservationEvent.RESERVATION_ATTEMPT,
				"INSUFFICIENT_STOCK", "[]", 1L);
	}

}
