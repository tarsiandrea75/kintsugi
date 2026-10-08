package dev.kintsugi.mcp.evidence;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
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

	/** PostgreSQL's {@code invalid_password}: wrong password, or no such role (the server does not say which). */
	private static final String INVALID_PASSWORD = "28P01";

	/** PostgreSQL's {@code invalid_authorization_specification}, for example no {@code pg_hba.conf} entry. */
	private static final String INVALID_AUTHORIZATION = "28000";

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
			throw new UpstreamException("PostgreSQL " + database.databaseName() + connectionFailure(ex), ex);
		}
		catch (DataAccessException ex) {
			String reason = QUERY_CANCELED.equals(sqlState(ex)) ? " did not answer in time" : " query failed";
			throw new UpstreamException("PostgreSQL " + database.databaseName() + reason, ex);
		}
	}

	/**
	 * Why no connection was obtained, read from the cause. Hikari wraps every failure of {@code getConnection} in a
	 * {@link SQLTransientConnectionException} once its connection timeout passes, and copies the SQLState of the last
	 * failed connection attempt, if any, onto it. So:
	 * <ul>
	 * <li>an authentication SQLState means the role or its password is wrong;</li>
	 * <li>any other SQLState (connection refused, unknown host, missing database) means the database cannot be
	 * reached;</li>
	 * <li>a pool timeout with no SQLState had no failed attempt: every connection was in use, so the pool is busy.</li>
	 * </ul>
	 */
	static String connectionFailure(Throwable ex) {
		String state = sqlState(ex);
		if (INVALID_PASSWORD.equals(state) || INVALID_AUTHORIZATION.equals(state)) {
			return " authentication failed";
		}
		if (state == null && hasCause(ex, SQLTransientConnectionException.class)) {
			return " connection pool busy";
		}
		return " is unreachable";
	}

	private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (type.isInstance(cause)) {
				return true;
			}
		}
		return false;
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
