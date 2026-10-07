package dev.kintsugi.lab.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.kintsugi.lab.inventory.TestcontainersConfiguration;
import dev.kintsugi.lab.inventory.domain.Reservation;
import dev.kintsugi.lab.inventory.domain.ReservationLine;
import dev.kintsugi.lab.inventory.domain.ReservationStatus;
import dev.kintsugi.lab.inventory.domain.Stock;
import dev.kintsugi.lab.inventory.persistence.StockRepository;

/**
 * The service against PostgreSQL 18.6. The test methods run outside a test transaction, so a rollback inside the
 * service is really a rollback; each test uses its own SKUs, so no cleanup is needed.
 */
@DataJdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(InventoryProperties.class)
@Import({ TestcontainersConfiguration.class, StockRepository.class, InventoryService.class })
class InventoryServiceTest {

	@Autowired
	private InventoryService service;

	@Autowired
	private StockRepository repository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcClient jdbc;

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
	void delayHookWaitsInsideTheReservation() {
		Duration delay = Duration.ofMillis(300);
		InventoryService delayed = new InventoryService(repository, transactionManager, new InventoryProperties(delay));
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

}
