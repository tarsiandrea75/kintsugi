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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.fault.TestcontainersConfiguration;
import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.domain.Injection;
import dev.kintsugi.lab.fault.domain.InjectionStatus;

@DataJdbcTest
@Import({ TestcontainersConfiguration.class, InjectionRepository.class })
class InjectionRepositoryTest {

	@Autowired
	private InjectionRepository repository;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void emptyTables() {
		jdbc.sql("TRUNCATE injection_events, injections").update();
	}

	@Test
	void insertedInjectionIsReadBackWithItsTrueCause() {
		Injection injection = injection(Fault.F3, InjectionStatus.ACTIVE);

		repository.insert(injection);

		assertThat(repository.findById(injection.id())).hasValueSatisfying(read -> {
			assertThat(read).usingRecursiveComparison().ignoringFields("parametersJson").isEqualTo(injection);
			assertThat(read.parametersJson()).contains("\"ORDER_PAYMENT_BASEURL\"", "http://payment-service:9999");
		});
		assertThat(repository.findById(UUID.randomUUID())).isEmpty();
	}

	@Test
	void parametersAreStoredAsJson() {
		Injection injection = injection(Fault.F4, InjectionStatus.ACTIVE);
		repository.insert(injection);

		String poolSize = jdbc.sql("SELECT parameters ->> 'INVENTORY_POOL_SIZE' FROM injections WHERE id = ?")
			.param(injection.id())
			.query(String.class)
			.single();

		assertThat(poolSize).isEqualTo("2");
	}

	@Test
	void findBlockingReturnsTheActiveOrRestorePendingRow() {
		repository.insert(injection(Fault.F1, InjectionStatus.RESTORED));
		repository.insert(injection(Fault.F2, InjectionStatus.FAILED));
		assertThat(repository.findBlocking()).isEmpty();

		Injection pending = injection(Fault.F3, InjectionStatus.RESTORE_PENDING);
		repository.insert(pending);

		assertThat(repository.findBlocking()).map(Injection::id).contains(pending.id());
	}

	@Test
	void secondActiveRowIsRejectedByThePartialUniqueIndex() {
		repository.insert(injection(Fault.F1, InjectionStatus.ACTIVE));

		assertThatExceptionOfType(DuplicateKeyException.class)
			.isThrownBy(() -> repository.insert(injection(Fault.F2, InjectionStatus.ACTIVE)));
	}

	/** An index on {@code status} would admit one ACTIVE and one RESTORE_PENDING row; the constant index does not. */
	@Test
	void activeAndRestorePendingCannotCoexist() {
		repository.insert(injection(Fault.F1, InjectionStatus.RESTORE_PENDING));

		assertThatExceptionOfType(DuplicateKeyException.class)
			.isThrownBy(() -> repository.insert(injection(Fault.F2, InjectionStatus.ACTIVE)));
	}

	@Test
	void finishedRowsDoNotBlockANewActiveRow() {
		repository.insert(injection(Fault.F1, InjectionStatus.RESTORED));
		repository.insert(injection(Fault.F1, InjectionStatus.FAILED));

		repository.insert(injection(Fault.F2, InjectionStatus.ACTIVE));

		assertThat(jdbc.sql("SELECT count(*) FROM injections").query(Long.class).single()).isEqualTo(3);
	}

	@Test
	void unknownStatusIsRejectedByTheSchema() {
		repository.insert(injection(Fault.F1, InjectionStatus.ACTIVE));
		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> jdbc.sql("UPDATE injections SET status = 'OTHER'").update());
	}

	@Test
	void updateStatusMovesABlockingRowAndSetsRestoredAt() {
		Injection injection = injection(Fault.F1, InjectionStatus.ACTIVE);
		repository.insert(injection);
		Instant restoredAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

		assertThat(repository.updateStatus(injection.id(), InjectionStatus.RESTORED, restoredAt)).isTrue();

		assertThat(repository.findById(injection.id())).hasValueSatisfying(read -> {
			assertThat(read.status()).isEqualTo(InjectionStatus.RESTORED);
			assertThat(read.restoredAt()).isEqualTo(restoredAt);
		});
	}

	@Test
	void updateStatusLeavesAFinishedRowUnchanged() {
		Injection injection = injection(Fault.F1, InjectionStatus.RESTORED);
		repository.insert(injection);

		assertThat(repository.updateStatus(injection.id(), InjectionStatus.RESTORE_PENDING, null)).isFalse();

		assertThat(repository.findById(injection.id())).map(Injection::status).contains(InjectionStatus.RESTORED);
	}

	/** The truth is written once: the repository can insert, read and move the status, and nothing else. */
	@Test
	void repositoryExposesNoDeleteAndNoTruthUpdate() {
		List<String> publicMethods = Arrays.stream(InjectionRepository.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
			.map(Method::getName)
			.toList();

		assertThat(publicMethods).containsExactlyInAnyOrder("insert", "findById", "findBlocking", "updateStatus");
	}

	private static Injection injection(Fault fault, InjectionStatus status) {
		Instant injectedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
		Instant restoredAt = status == InjectionStatus.RESTORED || status == InjectionStatus.FAILED ? injectedAt : null;
		return new Injection(UUID.randomUUID(), fault.id(), fault.targetService(), fault.rootCauseService(),
				fault.rootCauseCategory().name(), fault.rootCause(), json(fault), injectedAt, restoredAt, status);
	}

	private static String json(Fault fault) {
		StringBuilder json = new StringBuilder("{");
		fault.parameters().forEach((name, value) -> json.append(json.length() > 1 ? ", " : "")
			.append('"').append(name).append("\": ")
			.append(value instanceof String ? "\"" + value + "\"" : value));
		return json.append('}').toString();
	}

}
