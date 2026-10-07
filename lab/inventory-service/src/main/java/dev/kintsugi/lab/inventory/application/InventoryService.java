package dev.kintsugi.lab.inventory.application;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.lab.inventory.domain.Reservation;
import dev.kintsugi.lab.inventory.domain.ReservationEvent;
import dev.kintsugi.lab.inventory.domain.ReservationStatus;
import dev.kintsugi.lab.inventory.domain.Stock;
import dev.kintsugi.lab.inventory.persistence.ReservationEventRepository;
import dev.kintsugi.lab.inventory.persistence.StockRepository;

/**
 * Reserves stock for orders, all lines or none, records every attempt, and reads stock.
 *
 * <p>Every attempt leaves one {@code reservation_events} row: {@code RESERVED} in the same transaction as the stock
 * decrement, so one never exists without the other, and {@code INSUFFICIENT_STOCK} in a new transaction after the
 * rollback, so it survives. When the database itself is unavailable nothing can be stored, so the attempt becomes one
 * structured WARN line with the order id instead. Nothing is dropped silently.
 */
@Service
public class InventoryService {

	private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

	private final StockRepository repository;

	private final ReservationEventRepository events;

	private final TransactionTemplate transaction;

	private final Duration reservationDelay;

	private final Clock clock = Clock.systemUTC();

	public InventoryService(StockRepository repository, ReservationEventRepository events,
			PlatformTransactionManager transactionManager, InventoryProperties properties) {
		this.repository = repository;
		this.events = events;
		this.transaction = new TransactionTemplate(transactionManager);
		this.reservationDelay = properties.reservationDelay();
	}

	/**
	 * Takes every line in one transaction. Lines for the same SKU are merged and the SKUs taken in ascending order
	 * ({@link Reservation#quantitiesBySku()}), so concurrent reservations lock rows in the same order and cannot
	 * deadlock. The first short SKU (or unknown SKU) marks the transaction rollback-only, so the units already taken
	 * are given back and the result is {@link ReservationStatus#INSUFFICIENT_STOCK}. A merged total beyond an
	 * {@code int} is short too: no stock row can hold that much.
	 * @throws DatabaseUnavailableException when no database connection is available; the attempt is logged instead
	 */
	public ReservationStatus reserve(Reservation reservation) {
		long start = System.nanoTime();
		ReservationStatus result;
		try {
			result = transaction.execute(status -> {
				if (reservationDelay.isPositive()) {
					repository.sleep(reservationDelay);
				}
				for (Map.Entry<String, Long> entry : reservation.quantitiesBySku().entrySet()) {
					long quantity = entry.getValue();
					if (quantity > Integer.MAX_VALUE || !repository.take(entry.getKey(), (int) quantity)) {
						status.setRollbackOnly();
						return ReservationStatus.INSUFFICIENT_STOCK;
					}
				}
				events.insert(attempt(reservation, ReservationStatus.RESERVED.name(), start));
				return ReservationStatus.RESERVED;
			});
		}
		catch (CannotCreateTransactionException | DataAccessResourceFailureException ex) {
			log.warn("No database connection available: {} cause={}",
					logFields(attempt(reservation, "DATABASE_UNAVAILABLE", start)), cause(ex));
			throw new DatabaseUnavailableException(ex);
		}
		if (result == ReservationStatus.INSUFFICIENT_STOCK) {
			recordAfterRollback(attempt(reservation, result.name(), start));
		}
		return result;
	}

	public Optional<Stock> stock(String sku) {
		return repository.find(sku);
	}

	/** The rejected attempt still answers 409 when its event cannot be stored; it is logged instead. */
	private void recordAfterRollback(ReservationEvent event) {
		try {
			transaction.executeWithoutResult(status -> events.insert(event));
		}
		catch (CannotCreateTransactionException | DataAccessResourceFailureException ex) {
			log.warn("Reservation event not stored: {} cause={}", logFields(event), cause(ex));
		}
	}

	private ReservationEvent attempt(Reservation reservation, String outcome, long startNanos) {
		return new ReservationEvent(UUID.randomUUID(), reservation.orderId(),
				clock.instant().truncatedTo(ChronoUnit.MICROS), ReservationEvent.RESERVATION_ATTEMPT, outcome,
				JsonMapper.shared().writeValueAsString(reservation.lines()),
				Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
	}

	private static String logFields(ReservationEvent event) {
		return "event=" + event.type() + " order=" + event.orderId() + " outcome=" + event.outcome() + " duration_ms="
				+ event.durationMs() + " detail=" + LogFmt.quote(event.detail());
	}

	private static String cause(Exception ex) {
		return LogFmt.quote(NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
	}

}
