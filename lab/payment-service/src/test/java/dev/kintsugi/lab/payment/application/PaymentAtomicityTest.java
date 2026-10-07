package dev.kintsugi.lab.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.kintsugi.lab.payment.TestcontainersConfiguration;
import dev.kintsugi.lab.payment.domain.PaymentEvent;
import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.gateway.PaymentGateway;
import dev.kintsugi.lab.payment.persistence.PaymentEventRepository;
import dev.kintsugi.lab.payment.persistence.PaymentRepository;

/**
 * The payment row and its event commit or roll back together, against PostgreSQL 18.6. A test-only event repository
 * inserts its event with a type the schema's CHECK constraint rejects, so the insert fails after the payment row was
 * written in the same transaction. The test runs outside a test transaction, so the rollback is real.
 */
@DataJdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(TestcontainersConfiguration.class)
class PaymentAtomicityTest {

	@Autowired
	private PaymentRepository repository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void failedEventInsertLeavesNoPaymentRow() {
		UUID orderId = UUID.randomUUID();
		PaymentGateway gateway = mock(PaymentGateway.class);
		given(gateway.authorize(orderId)).willReturn(PaymentStatus.AUTHORIZED);
		PaymentService service = new PaymentService(gateway, repository, new RejectedByCheck(jdbc), transactionManager);

		assertThatExceptionOfType(DataIntegrityViolationException.class).isThrownBy(() -> service.pay(orderId));

		assertThat(jdbc.sql("select count(*) from payments where order_id = ?").param(orderId).query(Long.class).single())
			.isZero();
		assertThat(jdbc.sql("select count(*) from payment_events where order_id = ?")
			.param(orderId)
			.query(Long.class)
			.single()).isZero();
	}

	/** Inserts every event with a type outside the CHECK constraint, so every insert fails. */
	private static final class RejectedByCheck extends PaymentEventRepository {

		RejectedByCheck(JdbcClient jdbc) {
			super(jdbc);
		}

		@Override
		public void insert(PaymentEvent event) {
			super.insert(new PaymentEvent(event.id(), event.orderId(), event.occurredAt(), "NOT_A_TYPE", event.outcome(),
					event.detail(), event.durationMs()));
		}

	}

}
