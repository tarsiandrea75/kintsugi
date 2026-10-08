package dev.kintsugi.mcp.evidence;

import java.sql.SQLException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import dev.kintsugi.mcp.tool.UpstreamException;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs the event and summary tools' fixed SQL on one lab database. Every failure becomes an {@link UpstreamException}
 * with a short reason the model can read, like the Loki and Prometheus clients.
 */
public class LabDatabases {

	/** PostgreSQL's {@code query_canceled}: the role's {@code statement_timeout} (3 s) stopped the statement. */
	private static final String QUERY_CANCELED = "57014";

	private final Map<Database, JdbcClient> clients = new EnumMap<>(Database.class);

	public LabDatabases(Map<Database, ? extends DataSource> dataSources) {
		for (Database database : Database.values()) {
			DataSource dataSource = dataSources.get(database);
			if (dataSource == null) {
				throw new IllegalArgumentException("No DataSource for database " + database.databaseName());
			}
			this.clients.put(database, JdbcClient.create(dataSource));
		}
	}

	/** Runs {@code sql}, a fixed template of the caller, with named {@code params}. */
	<T> List<T> query(Database database, String sql, Map<String, ?> params, RowMapper<T> rows) {
		try {
			return this.clients.get(database).sql(sql).params(params).query(rows).list();
		}
		catch (CannotGetJdbcConnectionException ex) {
			throw new UpstreamException("PostgreSQL " + database.databaseName() + " is unreachable", ex);
		}
		catch (DataAccessException ex) {
			String reason = QUERY_CANCELED.equals(sqlState(ex)) ? " did not answer in time" : " query failed";
			throw new UpstreamException("PostgreSQL " + database.databaseName() + reason, ex);
		}
	}

	private static String sqlState(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && sql.getSQLState() != null) {
				return sql.getSQLState();
			}
		}
		return null;
	}

}
