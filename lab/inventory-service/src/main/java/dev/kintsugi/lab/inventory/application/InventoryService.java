package dev.kintsugi.lab.inventory.application;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import dev.kintsugi.lab.inventory.domain.Reservation;
import dev.kintsugi.lab.inventory.domain.ReservationStatus;
import dev.kintsugi.lab.inventory.domain.Stock;
import dev.kintsugi.lab.inventory.persistence.StockRepository;

/** Reserves stock for orders, all lines or none, and reads stock. */
@Service
public class InventoryService {

	private final StockRepository repository;

	private final TransactionTemplate transaction;

	private final Duration reservationDelay;

	public InventoryService(StockRepository repository, PlatformTransactionManager transactionManager,
			InventoryProperties properties) {
		this.repository = repository;
		this.transaction = new TransactionTemplate(transactionManager);
		this.reservationDelay = properties.reservationDelay();
	}

	/**
	 * Takes every line in one transaction. Lines for the same SKU are merged and the SKUs taken in ascending order
	 * ({@link Reservation#quantitiesBySku()}), so concurrent reservations lock rows in the same order and cannot
	 * deadlock. The first short SKU (or unknown SKU) marks the transaction rollback-only, so the units already taken
	 * are given back and the result is {@link ReservationStatus#INSUFFICIENT_STOCK}. A merged total beyond an
	 * {@code int} is short too: no stock row can hold that much.
	 */
	public ReservationStatus reserve(Reservation reservation) {
		return transaction.execute(status -> {
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
			return ReservationStatus.RESERVED;
		});
	}

	public Optional<Stock> stock(String sku) {
		return repository.find(sku);
	}

}
