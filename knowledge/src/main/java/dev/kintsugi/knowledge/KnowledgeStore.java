package dev.kintsugi.knowledge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The indexer's access to the knowledge store, as {@code knowledge_indexer}, through plain SQL on the schema that
 * {@code infra/knowledge-db/init/01-knowledge.sh} created: {@code runbook_chunks} and the single-row
 * {@code index_metadata}. Spring AI's {@code PgVectorStore} is not used: it embeds each document's stored text itself,
 * so the document prefix could not be sent without being stored, and it cannot embed a whole run before writing it.
 */
@Component
public class KnowledgeStore {

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	public KnowledgeStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	/** A chunk ready to store: its text without the prefix, its metadata and its embedding. */
	public record EmbeddedChunk(String content, Map<String, Object> metadata, float[] embedding) {
	}

	/** The dimension of the {@code embedding} column, fixed by the schema. */
	public int columnDimensions() {
		Integer dimensions = this.jdbc.queryForObject("SELECT atttypmod FROM pg_attribute"
				+ " WHERE attrelid = 'runbook_chunks'::regclass AND attname = 'embedding'", Integer.class);
		return dimensions == null ? -1 : dimensions;
	}

	public Optional<IndexModel> indexModel() {
		return this.jdbc
			.query("SELECT model_name, model_digest, dimensions, document_prefix, query_prefix FROM index_metadata",
					(row, i) -> new IndexModel(row.getString(1), row.getString(2), row.getInt(3), row.getString(4),
							row.getString(5)))
			.stream()
			.findFirst();
	}

	/** The content hash each indexed file had; a file with more than one hash maps to an empty one, so it re-embeds. */
	public Map<String, String> contentHashes() {
		Map<String, String> hashes = new HashMap<>();
		this.jdbc.query("SELECT DISTINCT metadata->>'source', metadata->>'content_hash' FROM runbook_chunks",
				row -> {
					hashes.merge(row.getString(1), row.getString(2), (first, second) -> "");
				});
		return hashes;
	}

	/**
	 * Writes a run in one transaction: deletes every chunk ({@code everything}) or those of {@code replacedSources},
	 * inserts {@code chunks}, and records {@code model}. Any failure rolls all of it back.
	 */
	public void write(boolean everything, Set<String> replacedSources, List<EmbeddedChunk> chunks, IndexModel model) {
		this.transactions.executeWithoutResult(status -> {
			if (everything) {
				this.jdbc.update("DELETE FROM runbook_chunks");
			}
			else {
				for (String source : replacedSources) {
					this.jdbc.update("DELETE FROM runbook_chunks WHERE metadata->>'source' = ?", source);
				}
			}
			this.jdbc.batchUpdate("INSERT INTO runbook_chunks (content, metadata, embedding) VALUES (?, ?::json, ?::vector)",
					chunks, 100, (statement, chunk) -> {
						statement.setString(1, chunk.content());
						statement.setString(2, JsonMapper.shared().writeValueAsString(chunk.metadata()));
						statement.setString(3, vector(chunk.embedding()));
					});
			this.jdbc.update("INSERT INTO index_metadata (id, model_name, model_digest, dimensions, document_prefix,"
					+ " query_prefix, indexed_at) VALUES (1, ?, ?, ?, ?, ?, now()) ON CONFLICT (id) DO UPDATE SET"
					+ " model_name = excluded.model_name, model_digest = excluded.model_digest,"
					+ " dimensions = excluded.dimensions, document_prefix = excluded.document_prefix,"
					+ " query_prefix = excluded.query_prefix, indexed_at = excluded.indexed_at", model.name(),
					model.digest(), model.dimensions(), model.documentPrefix(), model.queryPrefix());
		});
	}

	private static String vector(float[] values) {
		StringJoiner literal = new StringJoiner(",", "[", "]");
		for (float value : values) {
			literal.add(Float.toString(value));
		}
		return literal.toString();
	}

}
