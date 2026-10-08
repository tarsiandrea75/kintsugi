package dev.kintsugi.knowledge;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the runbooks are and which embedding model indexes them. The model is configurable, the data is not: every
 * value of {@link Model} is recorded in {@code index_metadata}, and a change re-embeds every file (ADR-002).
 *
 * @param runbooksDir the read-only mount of {@code docs/runbooks/}; only its top-level {@code *.md} files are read
 * @param model the embedding model
 */
@ConfigurationProperties("kintsugi.knowledge")
public record KnowledgeProperties(@DefaultValue("/runbooks") Path runbooksDir, @DefaultValue Model model) {

	/**
	 * @param name the Ollama model name; without a tag it means {@code :latest}
	 * @param dimensions the length of every embedding, equal to the {@code vector} column's
	 * @param documentPrefix sent before each chunk's text, never stored (Nomic's task prefix)
	 * @param queryPrefix not used by the indexer, recorded so {@code search_runbooks} can check it
	 */
	public record Model(@DefaultValue("nomic-embed-text") String name, @DefaultValue("768") int dimensions,
			@DefaultValue("search_document: ") String documentPrefix,
			@DefaultValue("search_query: ") String queryPrefix) {
	}

}
