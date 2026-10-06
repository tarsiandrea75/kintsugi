package dev.kintsugi.lab.gateway.api;

/** Body of a {@code POST /authorize} answer. Only {@code AUTHORIZED} exists until forced errors arrive (fault F2). */
record AuthorizeResponse(String status) {

	static final AuthorizeResponse AUTHORIZED = new AuthorizeResponse("AUTHORIZED");

}
