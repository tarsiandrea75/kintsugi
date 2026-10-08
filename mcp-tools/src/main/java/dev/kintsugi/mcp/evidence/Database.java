package dev.kintsugi.mcp.evidence;

/**
 * The lab databases the event and summary tools may read. There is no constant for {@code faults}, and the names are
 * not configurable, so no tool can be pointed at the ground truth.
 */
public enum Database {

	ORDERS("orders"), PAYMENTS("payments"), INVENTORY("inventory");

	private final String databaseName;

	Database(String databaseName) {
		this.databaseName = databaseName;
	}

	/** The PostgreSQL database name. */
	public String databaseName() {
		return this.databaseName;
	}

}
