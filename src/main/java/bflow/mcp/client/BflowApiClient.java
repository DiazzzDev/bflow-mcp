package bflow.mcp.client;

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
 */
@Component
public class BflowApiClient {

    /** Base URL of BFlow's public API, e.g. https://api.bflow-studio.com. */
    private final String baseUrl;

    /** Underlying HTTP client. */
    private final RestClient restClient;

    /**
     * Creates the client.
     * @param baseUrl BFlow API base URL, from {@code bflow.api.base-url}.
     */
    public BflowApiClient(@Value("${bflow.api.base-url}") final String baseUrl) {
        this.baseUrl = baseUrl;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
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
