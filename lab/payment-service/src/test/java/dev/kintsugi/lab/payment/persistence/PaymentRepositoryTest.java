package dev.kintsugi.lab.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.payment.TestcontainersConfiguration;
import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentStatus;

@DataJdbcTest
@Import(TestcontainersConfiguration.class)
class PaymentRepositoryTest {

	@Autowired
	private PaymentRepository repository;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void runsAgainstPostgres18WithTheFlywaySchema() {
		String version = jdbc.sql("select version()").query(String.class).single();
		Integer appliedMigrations = jdbc
			.sql("select count(*) from flyway_schema_history where success and version is not null")
			.query(Integer.class)
			.single();
		List<String> tables = jdbc
			.sql("select table_name from information_schema.tables where table_schema = 'public'")
			.query(String.class)
			.list();

		assertThat(version).startsWith("PostgreSQL 18.6");
		assertThat(appliedMigrations).isPositive();
		assertThat(tables).contains("payments");
	}

	@Test
	void savedPaymentIsReadBack() {
		Payment payment = Payment.create(UUID.randomUUID(), PaymentStatus.DECLINED);

		repository.save(payment);
		Payment found = repository.findById(payment.getId()).orElseThrow();

		assertThat(found.getId()).isEqualTo(payment.getId());
		assertThat(found.getOrderId()).isEqualTo(payment.getOrderId());
		assertThat(found.getStatus()).isEqualTo(PaymentStatus.DECLINED);
		assertThat(found.getCreatedAt()).isEqualTo(payment.getCreatedAt());
	}

	@Test
	void unknownStatusIsRejectedByTheSchema() {
		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> jdbc
			.sql("insert into payments (id, order_id, status, version, created_at) values (?, ?, 'PENDING', 0, now())")
			.params(UUID.randomUUID(), UUID.randomUUID())
			.update());
	}

}
