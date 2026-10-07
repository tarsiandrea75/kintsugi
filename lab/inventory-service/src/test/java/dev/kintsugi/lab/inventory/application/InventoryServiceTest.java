package dev.kintsugi.lab.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.kintsugi.lab.inventory.TestcontainersConfiguration;
import dev.kintsugi.lab.inventory.domain.Reservation;
import dev.kintsugi.lab.inventory.domain.ReservationEvent;
import dev.kintsugi.lab.inventory.domain.ReservationLine;
import dev.kintsugi.lab.inventory.domain.ReservationStatus;
import dev.kintsugi.lab.inventory.domain.Stock;
import dev.kintsugi.lab.inventory.persistence.ReservationEventRepository;
import dev.kintsugi.lab.inventory.persistence.StockRepository;

/**
 * The service against PostgreSQL 18.6. The test methods run outside a test transaction, so a rollback inside the
 * service is really a rollback; each test uses its own SKUs, so no cleanup is needed.
 */
@DataJdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(InventoryProperties.class)
@Import({ TestcontainersConfiguration.class, StockRepository.class, ReservationEventRepository.class,
		InventoryService.class })
class InventoryServiceTest {

	@Autowired
	private InventoryService service;

	@Autowired
	private StockRepository repository;

	@Autowired
	private ReservationEventRepository events;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

	@Test
	void everyLineIsReserved() {
		String a = newSku(5);
		String b = newSku(1);

		ReservationStatus status = service.reserve(reservationOf(new ReservationLine(a, 2), new ReservationLine(b, 1)));

		assertThat(status).isEqualTo(ReservationStatus.RESERVED);
		assertThat(available(a)).isEqualTo(3);
		assertThat(available(b)).isZero();
	}

	@Test
	void reservedAttemptLeavesExactlyOneEvent() {
		String a = newSku(5);
		Reservation reservation = reservationOf(new ReservationLine(a, 2));

		assertThat(service.reserve(reservation)).isEqualTo(ReservationStatus.RESERVED);

		assertThat(events.findByOrderId(reservation.orderId())).singleElement().satisfies(event -> {
			assertThat(event.type()).isEqualTo(ReservationEvent.RESERVATION_ATTEMPT);
			assertThat(event.outcome()).isEqualTo("RESERVED");
			assertThat(event.detail()).isEqualTo("[{\"sku\":\"" + a + "\",\"quantity\":2}]");
			assertThat(event.durationMs()).isNotNegative();
		});
	}

	/** The stock decrement is rolled back, but the attempt is recorded in its own transaction and survives. */
	@Test
	void insufficientAttemptLeavesOneEventAndTheStockUnchanged() {
		String a = newSku(5);
		String b = newSku(1);
		Reservation reservation = reservationOf(new ReservationLine(a, 2), new ReservationLine(b, 3));

		assertThat(service.reserve(reservation)).isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);

		assertThat(available(a)).isEqualTo(5);
		assertThat(available(b)).isEqualTo(1);
		assertThat(events.findByOrderId(reservation.orderId())).singleElement().satisfies(event -> {
			assertThat(event.outcome()).isEqualTo("INSUFFICIENT_STOCK");
			assertThat(event.detail()).isEqualTo(
					"[{\"sku\":\"" + a + "\",\"quantity\":2},{\"sku\":\"" + b + "\",\"quantity\":3}]");
		});
	}

	/**
	 * The rejected attempt rolls back to a savepoint and records its event in the same transaction, so it borrows one
	 * connection from the pool, not two. Under fault F4 a second borrow would wait the whole connection timeout. The
	 * service runs here on a data source that counts every connection it hands out.
	 */
	@Test
	void insufficientAttemptBorrowsOneConnection() {
		CountingDataSource counting = new CountingDataSource(dataSource);
		JdbcClient countingJdbc = JdbcClient.create(counting);
		InventoryService counted = new InventoryService(new StockRepository(countingJdbc),
				new ReservationEventRepository(countingJdbc), new JdbcTransactionManager(counting),
				new InventoryProperties(Duration.ZERO));
		String a = newSku(5);
		String b = newSku(1);
		Reservation reservation = reservationOf(new ReservationLine(a, 2), new ReservationLine(b, 3));

		assertThat(counted.reserve(reservation)).isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);

		assertThat(counting.borrowed()).isEqualTo(1);
		assertThat(available(a)).isEqualTo(5);
		assertThat(available(b)).isEqualTo(1);
		assertThat(events.findByOrderId(reservation.orderId())).singleElement()
			.satisfies(event -> assertThat(event.outcome()).isEqualTo("INSUFFICIENT_STOCK"));
	}

	@Test
	void shortSecondLineLeavesTheFirstLineUnchanged() {
		String a = newSku(5);
		String b = newSku(1);

		ReservationStatus status = service.reserve(reservationOf(new ReservationLine(a, 2), new ReservationLine(b, 3)));

		assertThat(status).isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);
		assertThat(available(a)).isEqualTo(5);
		assertThat(available(b)).isEqualTo(1);
	}

	@Test
	void unknownSkuIsInsufficientAndLeavesTheOtherLinesUnchanged() {
		String a = newSku(5);

		ReservationStatus status = service
			.reserve(reservationOf(new ReservationLine(a, 1), new ReservationLine("NO-SUCH-SKU", 1)));

		assertThat(status).isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);
		assertThat(available(a)).isEqualTo(5);
	}

	@Test
	void repeatedSkuLinesAddUp() {
		String a = newSku(3);

		assertThat(service.reserve(reservationOf(new ReservationLine(a, 2), new ReservationLine(a, 2))))
			.isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);
		assertThat(available(a)).isEqualTo(3);
	}

	@Test
	void repeatedSkuLinesBeyondAnIntAreInsufficient() {
		String a = newSku(5);

		assertThat(service.reserve(reservationOf(new ReservationLine(a, Integer.MAX_VALUE), new ReservationLine(a, 1))))
			.isEqualTo(ReservationStatus.INSUFFICIENT_STOCK);
		assertThat(available(a)).isEqualTo(5);
	}

	/**
	 * Two reservations name the same SKUs in opposite orders. If rows were locked in request order, one transaction
	 * would hold the first half and wait for the second while the other did the reverse, and PostgreSQL would abort
	 * one with a deadlock. Locking in SKU order makes both succeed every time. Forty SKUs per reservation make the
	 * two transactions cross on almost every run when the order is not fixed.
	 */
	@Test
	@Timeout(10)
	void reservationsWithOppositeSkuOrdersDoNotDeadlock() throws Exception {
		int skuCount = 40;
		int iterations = 20;
		List<String> skus = IntStream.range(0, skuCount).mapToObj(i -> newSku(1_000)).toList();
		List<ReservationLine> forward = skus.stream().map(sku -> new ReservationLine(sku, 1)).toList();
		List<ReservationLine> backward = forward.reversed();

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			for (int i = 0; i < iterations; i++) {
				CyclicBarrier start = new CyclicBarrier(2);
				Future<ReservationStatus> first = pool.submit(() -> reserveAfter(start, forward));
				Future<ReservationStatus> second = pool.submit(() -> reserveAfter(start, backward));

				assertThat(List.of(first.get(), second.get())).containsOnly(ReservationStatus.RESERVED);
			}
		}
		assertThat(skus).allSatisfy(sku -> assertThat(available(sku)).isEqualTo(1_000 - 2 * iterations));
	}

	private ReservationStatus reserveAfter(CyclicBarrier start, List<ReservationLine> lines) throws Exception {
		start.await();
		return service.reserve(new Reservation(UUID.randomUUID(), lines));
	}

	@Test
	void delayHookWaitsInsideTheReservation() {
		Duration delay = Duration.ofMillis(300);
		InventoryService delayed = new InventoryService(repository, events, transactionManager,
				new InventoryProperties(delay));
		String a = newSku(1);

		long start = System.nanoTime();
		ReservationStatus status = delayed.reserve(reservationOf(new ReservationLine(a, 1)));
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(status).isEqualTo(ReservationStatus.RESERVED);
		assertThat(elapsed).isGreaterThanOrEqualTo(delay);
		assertThat(available(a)).isZero();
	}

	@Test
	void stockIsReadBySku() {
		String a = newSku(7);

		assertThat(service.stock(a)).contains(new Stock(a, 7));
		assertThat(service.stock("NO-SUCH-SKU")).isEmpty();
	}

	private static Reservation reservationOf(ReservationLine... lines) {
		return new Reservation(UUID.randomUUID(), List.of(lines));
	}

	private String newSku(int available) {
		String sku = "TEST-" + UUID.randomUUID();
		jdbc.sql("insert into stock (sku, available) values (?, ?)").params(sku, available).update();
		return sku;
	}

	private int available(String sku) {
		return jdbc.sql("select available from stock where sku = ?").param(sku).query(Integer.class).single();
	}

	/** Counts the connections handed out, so a test can tell one borrow from two. */
	private static final class CountingDataSource extends DelegatingDataSource {

		private final AtomicInteger borrowed = new AtomicInteger();

		CountingDataSource(DataSource target) {
			super(target);
		}

		@Override
		public java.sql.Connection getConnection() throws java.sql.SQLException {
			borrowed.incrementAndGet();
			return super.getConnection();
		}

		int borrowed() {
			return borrowed.get();
		}

	}

}
