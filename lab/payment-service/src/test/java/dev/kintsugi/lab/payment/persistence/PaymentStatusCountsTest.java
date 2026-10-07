package dev.kintsugi.lab.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;

import dev.kintsugi.lab.payment.TestcontainersConfiguration;
import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentStatus;

@DataJdbcTest
@Import(TestcontainersConfiguration.class)
class PaymentStatusCountsTest {

	@Autowired
	private PaymentRepository repository;

	@Autowired
	private DataSource dataSource;

	@Test
	void countsPaymentsPerStatusAndOmitsStatusesWithNoPayments() {
		repository.save(Payment.create(UUID.randomUUID(), PaymentStatus.FAILED));
		repository.save(Payment.create(UUID.randomUUID(), PaymentStatus.FAILED));
		repository.save(Payment.create(UUID.randomUUID(), PaymentStatus.AUTHORIZED));

		// Same thread, so the query joins the test transaction and sees the uncommitted rows.
		List<PaymentStatusCount> counts = new PaymentStatusCounts(dataSource).countPaymentsPerStatus();

		assertThat(counts).containsExactlyInAnyOrder(new PaymentStatusCount(PaymentStatus.FAILED, 2),
				new PaymentStatusCount(PaymentStatus.AUTHORIZED, 1));
	}

}
