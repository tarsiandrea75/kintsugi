package dev.kintsugi.knowledge;

/** A run that cannot complete. Thrown before the store is written, or from inside its rolled-back transaction. */
public class IndexingException extends RuntimeException {

	public IndexingException(String message) {
		super(message);
	}

	public IndexingException(String message, Throwable cause) {
		super(message, cause);
	}

}
