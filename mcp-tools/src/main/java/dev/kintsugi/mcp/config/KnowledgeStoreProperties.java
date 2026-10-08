package dev.kintsugi.mcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where {@code search_runbooks} finds the knowledge store, as whom it connects, and which embedding model the index
 * must have been built with (knowledge-base spec, ADR-002). The database name, {@code knowledge}, is fixed in code.
 * Defaults are in {@code application.properties}.
 *
 * <p>
 * The password has no default, but unlike the lab's it is not required at startup: the knowledge store is a separate
 * server, and a missing password must not stop the seven evidence tools. Without it, {@code search_runbooks} answers
 * {@code UPSTREAM_ERROR} naming the missing password, and never connects.
 *
 * @param host the knowledge store's host
 * @param port its port
 * @param username the role search connects as: the store's read-only role, {@code kintsugi_agent} in
 *        {@code application.properties} and in the init script
 * @param password that role's password on the knowledge store, set separately from the lab server's
 * @param connectionTimeout how long a search waits for a connection, from the pool or a new one
 * @param maximumPoolSize connections to the store
 * @param model the embedding model the configuration expects the index to have
 */
@ConfigurationProperties("kintsugi.mcp.knowledge")
public record KnowledgeStoreProperties(String host, int port, String username, String password,
		Duration connectionTimeout, int maximumPoolSize, @DefaultValue Model model) {

	/** The connection settings in the shape the lab pools use. */
	DatabaseProperties connection() {
		return new DatabaseProperties(this.host, this.port, this.username, this.password, this.connectionTimeout,
				this.maximumPoolSize);
	}

	/**
	 * The embedding model. The prefixes keep Nomic's defaults here, since their trailing space is significant and easy
	 * to lose in a properties file.
	 *
	 * @param name the Ollama model name; without a tag it means {@code :latest}
	 * @param dimensions the length of every embedding, equal to the index's
	 * @param documentPrefix not used to search, compared with the index's to refuse a mismatch
	 * @param queryPrefix sent before the query text
	 */
	public record Model(@DefaultValue("nomic-embed-text") String name, @DefaultValue("768") int dimensions,
			@DefaultValue("search_document: ") String documentPrefix,
			@DefaultValue("search_query: ") String queryPrefix) {
	}

}
