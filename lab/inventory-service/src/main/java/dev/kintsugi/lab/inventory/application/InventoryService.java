package dev.kintsugi.lab.inventory.application;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>Every attempt leaves one {@code reservation_events} row, written in the reservation's own transaction on its one
 * connection. {@code RESERVED} is inserted with the stock decrement, so one never exists without the other.
 * {@code INSUFFICIENT_STOCK} is inserted after rolling back to a savepoint taken before the first line, so the stock is
 * unchanged and the event survives; no second connection is borrowed, which under fault F4 would wait the whole
 * connection timeout. When the attempt cannot be stored (the database is unavailable, or recording fails in any other
 * way) it becomes one structured WARN line with the order id instead. Nothing is dropped silently.
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
	 * deadlock. The first short SKU (or unknown SKU) rolls back to the savepoint taken before the first line, so the
	 * units already taken are given back, records the rejection and answers {@link ReservationStatus#INSUFFICIENT_STOCK}.
	 * A merged total beyond an {@code int} is short too: no stock row can hold that much.
	 * <p>A rejection whose event cannot be stored is still answered: the stock is unchanged either way, and the attempt
	 * is logged. A reservation whose event cannot be stored fails, because the event and the decrement roll back
	 * together; it is logged too.
	 * @throws DatabaseUnavailableException when no database connection is available; the attempt is logged instead
	 */
	public ReservationStatus reserve(Reservation reservation) {
		long start = System.nanoTime();
		AtomicReference<ReservationEvent> recording = new AtomicReference<>();
		try {
			return transaction.execute(status -> {
				if (reservationDelay.isPositive()) {
					repository.sleep(reservationDelay);
				}
				Object beforeLines = status.createSavepoint();
				for (Map.Entry<String, Long> entry : reservation.quantitiesBySku().entrySet()) {
					long quantity = entry.getValue();
					if (quantity > Integer.MAX_VALUE || !repository.take(entry.getKey(), (int) quantity)) {
						status.rollbackToSavepoint(beforeLines);
						return record(recording, attempt(reservation, ReservationStatus.INSUFFICIENT_STOCK, start));
					}
				}
				return record(recording, attempt(reservation, ReservationStatus.RESERVED, start));
			});
		}
		catch (RuntimeException ex) {
			ReservationEvent event = recording.get();
			if (event != null) {
				log.warn("Reservation event not stored: {} record_failure={} cause={}", logFields(event),
						ex.getClass().getName(), cause(ex));
				if (ReservationStatus.INSUFFICIENT_STOCK.name().equals(event.outcome())) {
					return ReservationStatus.INSUFFICIENT_STOCK;
				}
			}
			else if (isDatabaseUnavailable(ex)) {
				log.warn("No database connection available: {} cause={}",
						logFields(attempt(reservation, "DATABASE_UNAVAILABLE", start)), cause(ex));
			}
			throw isDatabaseUnavailable(ex) ? new DatabaseUnavailableException(ex) : ex;
		}
	}

	public Optional<Stock> stock(String sku) {
		return repository.find(sku);
	}

	/**
	 * Inserts {@code event} in the current transaction, first remembering it in {@code recording} so a failure of the
	 * insert or of the commit can still be logged with it.
	 */
	private ReservationStatus record(AtomicReference<ReservationEvent> recording, ReservationEvent event) {
		recording.set(event);
		events.insert(event);
		return ReservationStatus.valueOf(event.outcome());
	}

	private ReservationEvent attempt(Reservation reservation, ReservationStatus outcome, long startNanos) {
		return attempt(reservation, outcome.name(), startNanos);
	}

	private ReservationEvent attempt(Reservation reservation, String outcome, long startNanos) {
		return new ReservationEvent(UUID.randomUUID(), reservation.orderId(),
				clock.instant().truncatedTo(ChronoUnit.MICROS), ReservationEvent.RESERVATION_ATTEMPT, outcome,
				JsonMapper.shared().writeValueAsString(reservation.lines()),
				Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
	}

	private static boolean isDatabaseUnavailable(RuntimeException ex) {
		return ex instanceof CannotCreateTransactionException || ex instanceof DataAccessResourceFailureException;
	}

	private static String logFields(ReservationEvent event) {
		return "event=" + event.type() + " order=" + event.orderId() + " outcome=" + event.outcome() + " duration_ms="
				+ event.durationMs() + " detail=" + LogFmt.quote(event.detail());
	}

	private static String cause(Exception ex) {
		return LogFmt.quote(NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
	}

}
