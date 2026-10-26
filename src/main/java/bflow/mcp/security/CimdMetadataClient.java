package bflow.mcp.security;

import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.util.JSONObjectUtils;

/**
 * Resolves an allowlisted CIMD {@code client_id} URL to the redirect
 * URIs its metadata document declares, validating the document on the
 * way (MCP authorization spec 2025-11-25, ADR-0004).
 *
 * <p>What it checks: the {@code client_id} is allowlisted before any
 * network call; the document's own {@code client_id} equals the URL
 * exactly; {@code redirect_uris} is a non-empty array of strings; and,
 * if {@code token_endpoint_auth_method} is present, it is
 * {@code none}, because this proxy only supports public clients with
 * PKCE.</p>
 *
 * <p>Results are cached in memory for a fixed five minutes, keyed by the
 * allowlisted URL, so the cache cannot grow beyond the allowlist.
 * Failures are not cached. The cache holds public documents, not
 * transactions or sessions, so the proxy stays stateless in the sense of
 * ADR-0002: losing it, or running several instances, only costs an
 * extra fetch. The spec asks clients' documents to be cached according
 * to their HTTP cache headers; this uses a fixed TTL instead, for
 * simplicity.</p>
 */
@Component
public class CimdMetadataClient {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String AUTH_METHOD_NONE = "none";

    private final CimdClientAllowlist allowlist;
    private final CimdDocumentFetcher fetcher;
    private final Clock clock;
    private final Map<String, CachedRedirectUris> cache = new ConcurrentHashMap<>();

    /**
     * Creates the client with the system clock.
     * @param allowlist the allowlisted {@code client_id} URLs.
     * @param fetcher downloads the documents.
     */
    @Autowired
    public CimdMetadataClient(final CimdClientAllowlist allowlist, final CimdDocumentFetcher fetcher) {
        this(allowlist, fetcher, Clock.systemUTC());
    }

    /**
     * Creates the client with an explicit clock, for tests.
     * @param allowlist the allowlisted {@code client_id} URLs.
     * @param fetcher downloads the documents.
     * @param clock the time source for cache expiry.
     */
    CimdMetadataClient(
            final CimdClientAllowlist allowlist,
            final CimdDocumentFetcher fetcher,
            final Clock clock) {
        this.allowlist = allowlist;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /**
     * @param clientId the {@code client_id} URL a request presented.
     * @return the exact redirect URIs its metadata document declares.
     * @throws InvalidCimdClientException if it is not allowlisted, or
     *      its document cannot be fetched or is invalid.
     */
    public Set<String> redirectUrisFor(final String clientId) {
        if (!allowlist.isAllowed(clientId)) {
            throw new InvalidCimdClientException("client_id is not on the CIMD allowlist");
        }
        Instant now = clock.instant();
        CachedRedirectUris cached = cache.get(clientId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.redirectUris();
        }
        Set<String> redirectUris = fetchAndValidate(clientId);
        cache.put(clientId, new CachedRedirectUris(redirectUris, now.plus(CACHE_TTL)));
        return redirectUris;
    }

    /**
     * Empties the cache. Package-private on purpose: integration tests
     * share one instance per Spring context, so a document cached by one
     * test would otherwise leak into the next.
     */
    void clearCache() {
        cache.clear();
    }

    private Set<String> fetchAndValidate(final String clientId) {
        String json;
        try {
            json = fetcher.fetch(URI.create(clientId));
        } catch (IOException e) {
            throw new InvalidCimdClientException("could not fetch the client metadata document", e);
        }

        Map<String, Object> document;
        try {
            document = JSONObjectUtils.parse(json);
        } catch (ParseException e) {
            throw new InvalidCimdClientException("client metadata document is not valid JSON", e);
        }

        if (!clientId.equals(document.get("client_id"))) {
            throw new InvalidCimdClientException(
                    "client_id in the metadata document does not match its URL");
        }
        Object authMethod = document.get("token_endpoint_auth_method");
        if (authMethod != null && !AUTH_METHOD_NONE.equals(authMethod)) {
            throw new InvalidCimdClientException(
                    "client metadata document asks for an unsupported token_endpoint_auth_method");
        }
        return redirectUris(document);
    }

    private static Set<String> redirectUris(final Map<String, Object> document) {
        Object raw = document.get("redirect_uris");
        if (!(raw instanceof List)) {
            throw new InvalidCimdClientException("client metadata document has no redirect_uris array");
        }
        List<?> items = (List<?>) raw;
        if (items.isEmpty()) {
            throw new InvalidCimdClientException("client metadata document has an empty redirect_uris");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String) || ((String) item).isBlank()) {
                throw new InvalidCimdClientException(
                        "client metadata document has an invalid redirect_uris entry");
            }
            result.add((String) item);
        }
        return Set.copyOf(result);
    }

    private record CachedRedirectUris(Set<String> redirectUris, Instant expiresAt) { }
}
