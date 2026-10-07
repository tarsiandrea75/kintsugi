package dev.kintsugi.lab.fault.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.fault.TestcontainersConfiguration;
import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.domain.Injection;
import dev.kintsugi.lab.fault.domain.InjectionEvent;
import dev.kintsugi.lab.fault.domain.InjectionEventType;

@DataJdbcTest
@Import({ TestcontainersConfiguration.class, InjectionRepository.class, InjectionEventRepository.class })
class InjectionEventRepositoryTest {

	@Autowired
	private InjectionRepository injections;

	@Autowired
	private InjectionEventRepository repository;

	@Autowired
	private JdbcClient jdbc;

	private UUID injectionId;

	@BeforeEach
	void oneInjection() {
		jdbc.sql("TRUNCATE injection_events, injections").update();
		Injection injection = Injection.start(Fault.F2, "{}", Instant.now().truncatedTo(ChronoUnit.MICROS));
		injections.insert(injection);
		injectionId = injection.id();
	}

	@Test
	void eventsAreReadBackInTheOrderTheyWereAppended() {
		Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);

		repository.append(injectionId, InjectionEventType.ACTIVATION_STARTED, at, null);
		repository.append(injectionId, InjectionEventType.ACTIVATED, at, null);
		repository.append(injectionId, InjectionEventType.RESTORED, at, "{\"note\":\"x\"}");

		List<InjectionEvent> events = repository.findByInjectionId(injectionId);
		assertThat(events).extracting(InjectionEvent::type)
			.containsExactly(InjectionEventType.ACTIVATION_STARTED, InjectionEventType.ACTIVATED,
					InjectionEventType.RESTORED);
		assertThat(events.get(2).detail()).isEqualTo("{\"note\":\"x\"}");
		assertThat(events).allSatisfy(event -> assertThat(event.occurredAt()).isEqualTo(at));
		assertThat(repository.findByInjectionId(UUID.randomUUID())).isEmpty();
	}

	@Test
	void everyEventTypeIsAcceptedByTheSchema() {
		Arrays.stream(InjectionEventType.values())
			.forEach(type -> repository.append(injectionId, type, Instant.now(), null));

		assertThat(repository.findByInjectionId(injectionId)).hasSize(InjectionEventType.values().length);
	}

	@Test
	void unknownTypeIsRejectedByTheSchema() {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc
			.sql("INSERT INTO injection_events (injection_id, occurred_at, type) VALUES (?, now(), 'OTHER')")
			.param(injectionId)
			.update());
	}

	@Test
	void eventOfAnUnknownInjectionIsRejected() {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(
				() -> repository.append(UUID.randomUUID(), InjectionEventType.ACTIVATED, Instant.now(), null));
	}

	/** The event table is append-only: the repository offers an append and a read, never an update or a delete. */
	@Test
	void repositoryExposesNoUpdateOrDelete() {
		List<String> publicMethods = Arrays.stream(InjectionEventRepository.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
			.map(Method::getName)
			.toList();

		assertThat(publicMethods).containsExactlyInAnyOrder("append", "findByInjectionId");
		assertThat(publicMethods)
			.noneMatch(name -> name.matches("(?i).*(update|delete|remove|save|merge|upsert|truncate|clear).*"));
	}

}
