package dev.kintsugi.lab.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

import dev.kintsugi.lab.inventory.domain.Reservation;
import dev.kintsugi.lab.inventory.domain.ReservationLine;
import dev.kintsugi.lab.inventory.domain.ReservationStatus;
import dev.kintsugi.lab.inventory.persistence.ReservationEventRepository;
import dev.kintsugi.lab.inventory.persistence.StockRepository;

/**
 * When an attempt cannot be stored, it becomes one structured WARN line with the order id: the database is unavailable,
 * or recording the event fails in any other way. No database is needed: the transaction manager and the repositories
 * are mocks.
 */
@ExtendWith(OutputCaptureExtension.class)
class InventoryServiceDatabaseUnavailableTest {

	private static final UUID ORDER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

	private final StockRepository stock = mock(StockRepository.class);

	private final ReservationEventRepository events = mock(ReservationEventRepository.class);

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private final InventoryService service = new InventoryService(stock, events, transactionManager,
			new InventoryProperties(Duration.ZERO));

	private final Reservation reservation = new Reservation(ORDER_ID, List.of(new ReservationLine("SKU-1", 2)));

	@Test
	void noConnectionForTheReservationIsOneStructuredWarnLine(CapturedOutput output) {
		given(transactionManager.getTransaction(any())).willThrow(poolTimeout());

		assertThatExceptionOfType(DatabaseUnavailableException.class).isThrownBy(() -> service.reserve(reservation));

		verify(events, never()).insert(any());
		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("No database connection available: ")
				.contains("event=RESERVATION_ATTEMPT")
				.contains("order=" + ORDER_ID)
				.contains("outcome=DATABASE_UNAVAILABLE")
				.contains("duration_ms=")
				.contains("detail=\"[{\\\"sku\\\":\\\"SKU-1\\\",\\\"quantity\\\":2}]\"")
				.contains("cause=\"HikariPool-1 - Connection is not available, request timed out after 250ms\""));
	}

	@Test
	void insufficientStockWhoseEventCannotBeStoredIsStillAnsweredAndLogged(CapturedOutput output) {
		given(transactionManager.getTransaction(any())).willReturn(mock(TransactionStatus.class));
		given(stock.take(anyString(), anyInt())).willReturn(false);
		willThrow(new DataAccessResourceFailureException("connection lost")).given(events).insert(any());

		assertThat(service.reserve(reservation)).isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);

		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("Reservation event not stored: ")
				.contains("event=RESERVATION_ATTEMPT")
				.contains("order=" + ORDER_ID)
				.contains("outcome=INSUFFICIENT_STOCK")
				.contains("record_failure=org.springframework.dao.DataAccessResourceFailureException"));
	}

	/** Any failure to record the rejection, not only a missing connection, still answers 409 and is logged. */
	@Test
	void unexpectedFailureRecordingARejectionKeepsTheRejection(CapturedOutput output) {
		given(transactionManager.getTransaction(any())).willReturn(mock(TransactionStatus.class));
		given(stock.take(anyString(), anyInt())).willReturn(false);
		willThrow(new IllegalStateException("boom")).given(events).insert(any());

		assertThat(service.reserve(reservation)).isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);

		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("Reservation event not stored: ")
				.contains("order=" + ORDER_ID)
				.contains("outcome=INSUFFICIENT_STOCK")
				.contains("record_failure=java.lang.IllegalStateException"));
	}

	@Test
	void failedCommitOfARejectionKeepsTheRejection(CapturedOutput output) {
		given(transactionManager.getTransaction(any())).willReturn(mock(TransactionStatus.class));
		given(stock.take(anyString(), anyInt())).willReturn(false);
		willThrow(new TransactionSystemException("commit failed")).given(transactionManager).commit(any());

		assertThat(service.reserve(reservation)).isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);

		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("Reservation event not stored: ")
				.contains("outcome=INSUFFICIENT_STOCK")
				.contains("record_failure=org.springframework.transaction.TransactionSystemException"));
	}

	/**
	 * The RESERVED event shares the transaction of the stock decrement, so a failure to store it rolls both back and
	 * the request fails. It is still logged, with the order id.
	 */
	@Test
	void failureRecordingAReservationFailsTheRequestAndIsLogged(CapturedOutput output) {
		given(transactionManager.getTransaction(any())).willReturn(mock(TransactionStatus.class));
		given(stock.take(anyString(), anyInt())).willReturn(true);
		IllegalStateException failure = new IllegalStateException("boom");
		willThrow(failure).given(events).insert(any());

		assertThatThrownBy(() -> service.reserve(reservation)).isSameAs(failure);

		verify(transactionManager).rollback(any());
		assertThat(warnLines(output)).singleElement()
			.satisfies(line -> assertThat(line).contains("Reservation event not stored: ")
				.contains("order=" + ORDER_ID)
				.contains("outcome=RESERVED")
				.contains("record_failure=java.lang.IllegalStateException"));
	}

	private static CannotCreateTransactionException poolTimeout() {
		return new CannotCreateTransactionException("Could not open JDBC Connection", new SQLTransientConnectionException(
				"HikariPool-1 - Connection is not available, request timed out after 250ms"));
	}

	private static List<String> warnLines(CapturedOutput output) {
		return output.getAll().lines().filter(line -> line.contains(" WARN ")).toList();
	}

}
