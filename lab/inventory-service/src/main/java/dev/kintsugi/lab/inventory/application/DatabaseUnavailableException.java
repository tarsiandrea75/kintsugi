package dev.kintsugi.lab.inventory.application;

/**
 * A reservation could not reach the database: the pool is exhausted (fault F4) or the database is unreachable. The
 * attempt could not be stored, so {@link InventoryService} has already logged it as one structured WARN line.
 */
public class DatabaseUnavailableException extends RuntimeException {

	public DatabaseUnavailableException(Throwable cause) {
		super("No database connection available", cause);
	}

}
