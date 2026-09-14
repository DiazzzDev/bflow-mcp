package bflow.mcp.client;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The only way bflow-mcp talks to BFlow: plain HTTPS calls to the public
 * API, carrying the same bearer token the calling agent presented.
 *
 * <p>This class has no dependency on BFlow's domain module, repositories,
 * or database — by construction, since this is a separate Maven project
 * that never declares that dependency (ADR-0010 §1-2). Resource
 * authorization (can this user touch this wallet?) is decided entirely
 * by the receiving end, exactly as it is for the web client.</p>
 *
 * <p>Every request also carries the actor-identity headers ADR-0010 §5
 * defines ({@code X-BFlow-Actor-Type}, {@code X-BFlow-Channel}) and a
 * fresh correlation ID (§9). These are advisory input for audit and
 * rate-limiting only — the API must never treat them as authorization
 * input on their own; the validated JWT remains the sole source of
 * truth for who the request is allowed to act as.</p>
 */
@Component
public class BflowApiClient {

    /** Header naming the interface a request came through (ADR-0010 §5). */
    private static final String HEADER_CHANNEL = "X-BFlow-Channel";

    /** Header naming the actor type — always AGENT for MCP (ADR-0010 §5). */
    private static final String HEADER_ACTOR_TYPE = "X-BFlow-Actor-Type";

    /** Header carrying a per-request correlation ID (ADR-0010 §9). */
    private static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    /** Base URL of BFlow's public API, e.g. https://api.bflow-studio.com. */
    private final String baseUrl;

    /** Underlying HTTP client. */
    private final RestClient restClient;

    /**
     * Creates the client.
     * @param restClientBuilder Spring Boot's auto-configured builder —
     *      injecting this instead of calling {@code RestClient.builder()}
     *      directly is what lets a test bind {@code MockRestServiceServer}
     *      to it.
     * @param baseUrl BFlow API base URL, from {@code bflow.api.base-url}.
     */
    public BflowApiClient(final RestClient.Builder restClientBuilder,
            @Value("${bflow.api.base-url}") final String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * Issues a GET request against the BFlow API, forwarding the
     * current caller's bearer token, and returns the deserialized body.
     *
     * @param path request path, e.g. {@code "/api/v1/wallets"}.
     * @param responseType the expected response body type.
     * @param <T> response type.
     * @return the deserialized response body.
     */
    public <T> T get(final String path, final Class<T> responseType) {
        return restClient.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentBearerToken())
                .header(HEADER_CHANNEL, "mcp")
                .header(HEADER_ACTOR_TYPE, "AGENT")
                .header(HEADER_CORRELATION_ID, UUID.randomUUID().toString())
                .retrieve()
                .body(responseType);
    }

    /** Header BFlow's IdempotencyFilter requires on protected POSTs
     *  (currently /expenses, /incomes, /transfers — but sent on every
     *  POST here regardless, harmless where the filter doesn't apply,
     *  and future-proof if that list grows). */
    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * Issues a POST request against the BFlow API, forwarding the
     * current caller's bearer token, and returns the deserialized body.
     * A fresh idempotency key is generated per call — callers that want
     * retry-safety (the same logical write, retried after a timeout or
     * uncertain response) should use
     * {@link #post(String, Object, Class, String)} instead, supplying
     * the same key across those retries.
     *
     * <p>Errors (4xx/5xx from the BFlow API) currently propagate as
     * whatever exception {@code RestClient}'s default error handling
     * throws — there is no structured-error translation yet (ADR-0010
     * §8, still an open gap owned by issue #20).</p>
     *
     * @param path request path, e.g. {@code "/api/v1/expenses"}.
     * @param body the request body, serialized as JSON.
     * @param responseType the expected response body type.
     * @param <T> response type.
     * @return the deserialized response body.
     */
    public <T> T post(final String path, final Object body,
            final Class<T> responseType) {
        return post(path, body, responseType, UUID.randomUUID().toString());
    }

    /**
     * Issues a POST request against the BFlow API with an explicit
     * idempotency key (ADR-0010 §19 — required by BFlow's own
     * {@code IdempotencyFilter} on {@code /expenses}/{@code /incomes}/
     * {@code /transfers}; without this header those calls fail with
     * 400 outright, not just "unsafe to retry").
     *
     * <p>Reusing the same {@code idempotencyKey} across calls with the
     * <b>same</b> body lets a retried write return the original result
     * instead of creating a duplicate. Reusing it with a
     * <b>different</b> body is rejected by BFlow with 409 — this class
     * does not work around that, by design.</p>
     *
     * @param path request path, e.g. {@code "/api/v1/expenses"}.
     * @param body the request body, serialized as JSON.
     * @param responseType the expected response body type.
     * @param idempotencyKey the key to send; reuse it across retries of
     *      the same logical write, use a fresh one for a genuinely new one.
     * @param <T> response type.
     * @return the deserialized response body.
     */
    public <T> T post(final String path, final Object body,
            final Class<T> responseType, final String idempotencyKey) {
        return restClient.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentBearerToken())
                .header(HEADER_CHANNEL, "mcp")
                .header(HEADER_ACTOR_TYPE, "AGENT")
                .header(HEADER_CORRELATION_ID, UUID.randomUUID().toString())
                .header(HEADER_IDEMPOTENCY_KEY, idempotencyKey)
                .body(body)
                .retrieve()
                .body(responseType);
    }

    /**
     * Issues a PUT request against the BFlow API, forwarding the
     * current caller's bearer token, and returns the deserialized body.
     *
     * @param path request path, e.g. {@code "/api/v1/expenses/{id}"}.
     * @param body the request body, serialized as JSON.
     * @param responseType the expected response body type.
     * @param <T> response type.
     * @return the deserialized response body.
     */
    public <T> T put(final String path, final Object body,
            final Class<T> responseType) {
        return restClient.put()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + currentBearerToken())
                .header(HEADER_CHANNEL, "mcp")
                .header(HEADER_ACTOR_TYPE, "AGENT")
                .header(HEADER_CORRELATION_ID, UUID.randomUUID().toString())
                .body(body)
                .retrieve()
                .body(responseType);
    }

    /**
     * Resolves the raw bearer token of the currently authenticated
     * caller, so it can be forwarded unchanged to the BFlow API.
     * @return the raw JWT string.
     */
    private String currentBearerToken() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException(
                    "No authenticated JWT to forward to " + baseUrl);
        }

        return jwtAuth.getToken().getTokenValue();
    }
}
