package bflow.mcp.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The closed set of exact {@code client_id} URLs this proxy accepts as
 * OAuth Client ID Metadata Documents (CIMD, MCP authorization spec
 * 2025-11-25).
 *
 * <p>Same idea as {@link RedirectUriAllowlist}: an exact-match list, one
 * config entry per platform. It is what keeps CIMD safe here. The proxy
 * only ever downloads a metadata document from a URL an operator
 * approved, so a client cannot make it fetch an arbitrary address
 * (SSRF), and it only trusts clients whose identity the operator chose
 * to trust. Open it to arbitrary HTTPS URLs only after adding SSRF
 * protection to the fetcher (see ADR-0004).</p>
 *
 * <p>An empty list, the default, disables CIMD: nothing is advertised
 * and no {@code https://} {@code client_id} is accepted.</p>
 */
@Component
public class CimdClientAllowlist {

    private static final String HTTPS = "https";

    private final Set<String> allowedClientIds;

    /**
     * Creates the allowlist.
     * @param allowedClientIdsCsv {@code bflow.mcp.proxy.cimd.allowed-client-ids}:
     *      comma-separated exact {@code client_id} URLs; may be empty.
     * @throws IllegalStateException if an entry is not a valid
     *      {@code client_id} URL (HTTPS, with a path, no fragment, no
     *      credentials, no dot segments).
     */
    public CimdClientAllowlist(
            @Value("${bflow.mcp.proxy.cimd.allowed-client-ids:}") final String allowedClientIdsCsv) {
        List<String> parsed = Arrays.stream(allowedClientIdsCsv.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .collect(Collectors.toList());
        parsed.forEach(CimdClientAllowlist::requireValidClientIdUrl);
        this.allowedClientIds = Set.copyOf(parsed);
    }

    /**
     * @return {@code true} if at least one client is allowlisted, i.e.
     *      CIMD is on and should be advertised.
     */
    public boolean isEnabled() {
        return !allowedClientIds.isEmpty();
    }

    /**
     * @param clientId the {@code client_id} a request presented.
     * @return {@code true} if it is an exact match against the allowlist.
     */
    public boolean isAllowed(final String clientId) {
        return clientId != null && allowedClientIds.contains(clientId);
    }

    /**
     * @param clientId the {@code client_id} a request presented.
     * @return {@code true} if it has the shape of a CIMD {@code client_id}
     *      (an {@code https://} URL) rather than one of this proxy's own
     *      DCR tokens. Says nothing about whether it is allowed.
     */
    public static boolean isCimdClientId(final String clientId) {
        return clientId != null && clientId.startsWith(HTTPS + "://");
    }

    private static void requireValidClientIdUrl(final String clientId) {
        URI uri;
        try {
            uri = new URI(clientId);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "bflow.mcp.proxy.cimd.allowed-client-ids entry is not a valid URL: " + clientId, e);
        }
        String path = uri.getRawPath();
        boolean valid = HTTPS.equals(uri.getScheme())
                && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null
                && path != null
                && path.length() > 1
                && Arrays.stream(path.split("/")).noneMatch(s -> ".".equals(s) || "..".equals(s));
        if (!valid) {
            throw new IllegalStateException(
                    "bflow.mcp.proxy.cimd.allowed-client-ids entry must be an https URL with a path,"
                            + " no fragment, no credentials and no dot segments: " + clientId);
        }
    }
}
