package dev.kintsugi.mcp.config;

import java.time.Clock;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import dev.kintsugi.mcp.knowledge.KnowledgeIndex;
import dev.kintsugi.mcp.knowledge.OllamaDigests;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The knowledge store's pool and the Ollama digest cache behind {@code search_runbooks} (ADR-002). The embedding model
 * and the {@link OllamaApi} come from Spring AI's Ollama auto-configuration ({@code spring.ai.model.embedding=ollama},
 * chat off, no model pull at startup), so mcp-tools starts while Ollama or the store is down, and only
 * {@code search_runbooks} answers {@code UPSTREAM_ERROR} until they are back.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeStoreProperties.class)
public class KnowledgeConfiguration {

	/**
	 * pgvector's iterative index scan (0.8+): with a {@code service} filter, an HNSW scan otherwise stops after
	 * {@code hnsw.ef_search} (40) candidates and can return fewer than {@code limit} chunks; {@code strict_order} keeps
	 * scanning until enough rows pass the filter, still in exact distance order. A plain {@code SET}, so it runs on
	 * the read-only connection.
	 */
	static final String ITERATIVE_SCAN = "SET hnsw.iterative_scan = strict_order";

	/**
	 * A read-only pool for the {@code knowledge} database, as {@code kintsugi_agent}, built like the lab pools: read
	 * only on two levels, no connection at startup, short timeouts.
	 */
	@Bean
	HikariDataSource knowledgeDataSource(KnowledgeStoreProperties properties) {
		return dataSource(properties);
	}

	public static HikariDataSource dataSource(KnowledgeStoreProperties properties) {
		return EvidenceConfiguration.dataSource(properties.connection(), KnowledgeIndex.DATABASE, ITERATIVE_SCAN);
	}

	@Bean
	KnowledgeIndex knowledgeIndex(KnowledgeStoreProperties properties,
			@Qualifier("knowledgeDataSource") HikariDataSource dataSource) {
		return index(properties, dataSource);
	}

	/** The index on {@code dataSource}; without a password every search fails naming it, and nothing connects. */
	public static KnowledgeIndex index(KnowledgeStoreProperties properties, DataSource dataSource) {
		if (properties.password() == null || properties.password().isBlank()) {
			return KnowledgeIndex.unusable(dataSource, "password is not set (KNOWLEDGE_AGENT_DB_PASSWORD)");
		}
		return new KnowledgeIndex(dataSource);
	}

	@Bean
	OllamaDigests ollamaDigests(OllamaApi ollamaApi, Clock clock) {
		return new OllamaDigests(ollamaApi, clock);
	}

}
