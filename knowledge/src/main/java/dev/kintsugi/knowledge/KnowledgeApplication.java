package dev.kintsugi.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The knowledge indexer: a one-shot job that embeds {@code docs/runbooks/*.md} into the knowledge store and exits
 * (ADR-002; openspec/specs/knowledge-base/spec.md). The exit code is the run's outcome: 0 when the index is up to
 * date, 1 on any failure, with the previous index left intact.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeApplication {

	public static void main(String[] args) {
		System.exit(SpringApplication.exit(SpringApplication.run(KnowledgeApplication.class, args)));
	}

}
