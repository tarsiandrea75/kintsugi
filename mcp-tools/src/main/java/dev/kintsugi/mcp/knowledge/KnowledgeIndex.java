package dev.kintsugi.mcp.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Supplier;

import javax.sql.DataSource;

import dev.kintsugi.mcp.evidence.LabDatabases;
import dev.kintsugi.mcp.tool.UpstreamException;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code search_runbooks}' read-only access to the knowledge store, as {@code kintsugi_agent}, through fixed SQL on the
 * schema of {@code infra/knowledge-db/init/01-knowledge.sh}. Plain SQL, not Spring AI's {@code PgVectorStore}, as in
 * the indexer: the store would embed the query itself, without the query prefix, and could not be held to a read-only
 * role and the model guard. Failures are classified like the lab databases', with {@code knowledge} as the name.
 */
public class KnowledgeIndex {

	/** The database name, fixed in code like the lab's: no setting can point the search at another database. */
	public static final String DATABASE = "knowledge";

	private final JdbcClient jdbc;

	/** Why the store cannot be used at all, such as a missing password; {@code null} when it can. */
	private final String unusable;

	public KnowledgeIndex(DataSource dataSource) {
		this(dataSource, null);
	}

	private KnowledgeIndex(DataSource dataSource, String unusable) {
		this.jdbc = JdbcClient.create(dataSource);
		this.unusable = unusable;
	}

	/**
	 * An index that answers every search with {@code reason} without connecting. pgjdbc reports an empty password as
	 * a rejected connection (SQLState 08004), which would read as "unreachable"; the cause is named instead.
	 */
	public static KnowledgeIndex unusable(DataSource dataSource, String reason) {
		return new KnowledgeIndex(dataSource, reason);
	}

	/** A chunk found by a search: its metadata, its text, and its cosine distance to the query. */
	record Hit(String service, String section, String content, double distance) {
	}

	/** The model the index was built with; empty when no run has written the metadata row yet. */
	Optional<IndexModel> model() {
		return guarded(() -> this.jdbc
			.sql("SELECT model_name, model_digest, dimensions, document_prefix, query_prefix FROM index_metadata")
			.query((row, i) -> new IndexModel(row.getString(1), row.getString(2), row.getInt(3), row.getString(4),
					row.getString(5)))
			.optional());
	}

	/**
	 * The {@code limit} chunks nearest to {@code vector} by cosine distance, nearest first, only those of
	 * {@code service} when it is not {@code null}. The filter is a fixed clause with a bind parameter.
	 */
	List<Hit> nearest(float[] vector, String service, int limit) {
		String filter = service == null ? "" : " WHERE metadata->>'service' = :service";
		Map<String, Object> params = new java.util.HashMap<>(Map.of("vector", literal(vector), "limit", limit));
		if (service != null) {
			params.put("service", service);
		}
		return guarded(() -> this.jdbc
			.sql("SELECT metadata->>'service' AS service, metadata->>'section' AS section, content,"
					+ " embedding <=> CAST(:vector AS vector) AS distance FROM runbook_chunks" + filter
					+ " ORDER BY embedding <=> CAST(:vector AS vector) LIMIT :limit")
			.params(params)
			.query((row, i) -> new Hit(row.getString("service"), row.getString("section"), row.getString("content"),
					row.getDouble("distance")))
			.list());
	}

	private <T> T guarded(Supplier<T> query) {
		if (this.unusable != null) {
			throw new UpstreamException("PostgreSQL " + DATABASE + " " + this.unusable);
		}
		try {
			return query.get();
		}
		catch (CannotGetJdbcConnectionException ex) {
			throw new UpstreamException("PostgreSQL " + DATABASE + LabDatabases.connectionFailure(ex), ex);
		}
		catch (DataAccessException ex) {
			throw new UpstreamException("PostgreSQL " + DATABASE + LabDatabases.queryFailure(ex), ex);
		}
	}

	private static String literal(float[] values) {
		StringJoiner literal = new StringJoiner(",", "[", "]");
		for (float value : values) {
			literal.add(Float.toString(value));
		}
		return literal.toString();
	}

}
