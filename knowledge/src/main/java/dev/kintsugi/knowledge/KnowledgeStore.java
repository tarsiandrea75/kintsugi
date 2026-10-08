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
 * {@code infra/knowledge-db/init/01-knowledge.sh} created: {@code runbook_chunks}, the single-row
 * {@code index_metadata} and {@code indexed_files}. Spring AI's {@code PgVectorStore} is not used: it embeds each document's stored text itself,
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

	/**
	 * The dimension of the {@code embedding} column, fixed by the schema. A store without the column, or with a
	 * column of no fixed dimension, was not built by the init script, so the run stops with that cause.
	 */
	public int columnDimensions() {
		List<Integer> dimensions = this.jdbc.queryForList("SELECT atttypmod FROM pg_attribute"
				+ " WHERE attrelid = to_regclass('runbook_chunks') AND attname = 'embedding' AND NOT attisdropped",
				Integer.class);
		if (dimensions.isEmpty() || dimensions.getFirst() == null || dimensions.getFirst() < 1) {
			throw new IndexingException("The knowledge store has no runbook_chunks.embedding column of a fixed"
					+ " dimension: the schema is missing; the store must be created by"
					+ " infra/knowledge-db/init/01-knowledge.sh");
		}
		return dimensions.getFirst();
	}

	public Optional<IndexModel> indexModel() {
		return this.jdbc
			.query("SELECT model_name, model_digest, dimensions, document_prefix, query_prefix FROM index_metadata",
					(row, i) -> new IndexModel(row.getString(1), row.getString(2), row.getInt(3), row.getString(4),
							row.getString(5)))
			.stream()
			.findFirst();
	}

	/**
	 * The content hash of each file the last run indexed, from {@code indexed_files}. It lists a file that yielded no
	 * chunk too, so such a file is skipped like any other unchanged one.
	 */
	public Map<String, String> contentHashes() {
		Map<String, String> hashes = new HashMap<>();
		this.jdbc.query("SELECT source, content_hash FROM indexed_files",
				row -> {
					hashes.put(row.getString(1), row.getString(2));
				});
		return hashes;
	}

	/**
	 * Writes a run in one transaction: deletes every chunk and file row ({@code everything}) or those of
	 * {@code replacedSources}, inserts {@code chunks} and the content hash of each file in {@code indexedFiles}, and
	 * records {@code model}. Any failure rolls all of it back.
	 */
	public void write(boolean everything, Set<String> replacedSources, List<EmbeddedChunk> chunks,
			Map<String, String> indexedFiles, IndexModel model) {
		this.transactions.executeWithoutResult(status -> {
			if (everything) {
				this.jdbc.update("DELETE FROM runbook_chunks");
				this.jdbc.update("DELETE FROM indexed_files");
			}
			else {
				for (String source : replacedSources) {
					this.jdbc.update("DELETE FROM runbook_chunks WHERE metadata->>'source' = ?", source);
					this.jdbc.update("DELETE FROM indexed_files WHERE source = ?", source);
				}
			}
			indexedFiles.forEach((source, hash) -> this.jdbc
				.update("INSERT INTO indexed_files (source, content_hash) VALUES (?, ?)", source, hash));
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
