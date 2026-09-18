package bflow.mcp.security;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The closed set of exact redirect URIs {@code POST /oauth/register}
 * (and, defensively, {@code /oauth/authorize}) is allowed to act on.
 *
 * <p>Deliberately an exact-match list, not a domain or wildcard rule.
 * Every platform bflow-mcp has ever connected to — MCP Inspector,
 * ChatGPT, Claude.ai — uses one fixed, documented callback URL with no
 * dynamic path or query component (mirrors {@code CALLBACK_URLS} in
 * {@code infra/17-mcp-cognito-client.sh}). A rule like {@code *.claude.ai}
 * would accept any path under that host, including ones Claude.ai
 * itself never asks for — extra attack surface for zero onboarding
 * benefit, since the real URI is fixed either way. Exact match costs
 * the same one config line per platform and closes the open-redirect
 * hole an unvalidated registration endpoint would have (see ADR-0002).</p>
 *
 * <p>Onboarding a new platform is now: add its documented callback URL
 * to {@code bflow.mcp.proxy.allowed-redirect-uris}, redeploy. Cognito's
 * own App Client still only ever sees bflow-mcp's own fixed
 * {@code /oauth/callback} — see {@code infra/17-mcp-cognito-client.sh}'s
 * updated note — so this is the only place a new platform gets added
 * for the DCR path.</p>
 */
@Component
public class RedirectUriAllowlist {

    private final Set<String> allowedRedirectUris;

    /**
     * Creates the allowlist.
     * @param allowedRedirectUrisCsv {@code bflow.mcp.proxy.allowed-redirect-uris} —
     *      comma-separated exact redirect URIs.
     */
    public RedirectUriAllowlist(
            @Value("${bflow.mcp.proxy.allowed-redirect-uris}") final String allowedRedirectUrisCsv) {
        List<String> parsed = Arrays.stream(allowedRedirectUrisCsv.split(","))
                .map(String::trim)
                .filter(uri -> !uri.isEmpty())
                .collect(Collectors.toList());
        if (parsed.isEmpty()) {
            throw new IllegalStateException(
                    "bflow.mcp.proxy.allowed-redirect-uris must list at least one redirect URI");
        }
        this.allowedRedirectUris = Set.copyOf(parsed);
    }

    /**
     * @param redirectUri the URI a request is asking to register or use.
     * @return {@code true} if it's an exact match against the allowlist.
     */
    public boolean isAllowed(final String redirectUri) {
        return redirectUri != null && allowedRedirectUris.contains(redirectUri);
    }
}