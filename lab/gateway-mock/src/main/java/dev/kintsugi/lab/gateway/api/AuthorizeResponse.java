package dev.kintsugi.lab.gateway.api;

/** Body of a successful {@code POST /authorize} answer. Forced errors use {@link GatewayErrorResponse}. */
record AuthorizeResponse(String status) {

	static final AuthorizeResponse AUTHORIZED = new AuthorizeResponse("AUTHORIZED");

}
