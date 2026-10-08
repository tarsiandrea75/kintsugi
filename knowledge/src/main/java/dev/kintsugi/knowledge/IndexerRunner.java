package dev.kintsugi.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Runs the index once at startup and keeps the outcome as the exit code that {@code main} hands to
 * {@code System.exit}: 0 on success, 1 on any failure.
 */
@Component
class IndexerRunner implements ApplicationRunner, ExitCodeGenerator {

	private static final Logger log = LoggerFactory.getLogger(IndexerRunner.class);

	private final RunbookIndexer indexer;

	private volatile int exitCode = 1;

	IndexerRunner(RunbookIndexer indexer) {
		this.indexer = indexer;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			this.indexer.index();
			this.exitCode = 0;
		}
		catch (RuntimeException ex) {
			log.error("Indexing failed, the previous index is unchanged: {}", ex.getMessage(), ex);
			this.exitCode = 1;
		}
	}

	@Override
	public int getExitCode() {
		return this.exitCode;
	}

}
