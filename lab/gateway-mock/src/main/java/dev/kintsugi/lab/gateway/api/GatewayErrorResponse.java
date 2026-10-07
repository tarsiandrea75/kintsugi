package dev.kintsugi.lab.gateway.api;

/** Body of a forced {@code POST /authorize} error, such as {@code {"error": "rate limited"}} for a 429. */
record GatewayErrorResponse(String error) {

	/** The body for one of the allowed error statuses; any other status gets a generic message. */
	static GatewayErrorResponse forStatus(int status) {
		return new GatewayErrorResponse(switch (status) {
			case 429 -> "rate limited";
			case 500 -> "internal error";
			case 503 -> "unavailable";
			default -> "error";
		});
	}

}
