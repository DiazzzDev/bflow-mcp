package bflow.mcp.security;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the OAuth 2.0 Authorization Server Metadata document (RFC
 * 8414) that {@link OAuthProxyController} promises.
 *
 * <p>{@code issuer} here is bflow-mcp's OWN public URL — not Cognito's.
 * RFC 8414 §3.3 requires the metadata document's {@code issuer} to
 * exactly equal the URL a client used to discover it (the value we put
 * in {@code authorization_servers}), and spec-compliant clients (the
 * MCP Inspector included) enforce this and reject a mismatch outright.
 * This is unrelated to JWT validation: {@code SecurityConfig}'s
 * resource-server check reads Cognito's issuer straight from
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, never
 * from this document, so it's unaffected by what we publish here
 * (ADR-0009 §5 addendum, same Cognito-vs-MCP friction as
 * {@link OAuthProxyController}).</p>
 */
@RestController
public class AuthorizationServerMetadataController {

    /** This instance's own public URL — also the published {@code issuer}. */
    private final String publicBaseUrl;

    /** Cognito's real issuer — used only to derive its JWKS URI. */
    private final String cognitoIssuerUri;

    /**
     * Creates the controller.
     * @param publicBaseUrl this instance's public URL.
     * @param cognitoIssuerUri Cognito's issuer URI.
     */
    public AuthorizationServerMetadataController(
            @Value("${bflow.mcp.public-base-url}") final String publicBaseUrl,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
            final String cognitoIssuerUri) {
        this.publicBaseUrl = publicBaseUrl;
        this.cognitoIssuerUri = cognitoIssuerUri;
    }

    /**
     * Serves the AS metadata document.
     * @return the metadata as a plain map (Spring serializes it to JSON).
     */
    @GetMapping("/.well-known/oauth-authorization-server")
    public Map<String, Object> metadata() {
        return Map.of(
                "issuer", publicBaseUrl,
                "authorization_endpoint", publicBaseUrl + "/oauth/authorize",
                "token_endpoint", publicBaseUrl + "/oauth/token",
                "jwks_uri", cognitoIssuerUri + "/.well-known/jwks.json",
                "response_types_supported", List.of("code"),
                "grant_types_supported",
                        List.of("authorization_code", "refresh_token"),
                "code_challenge_methods_supported", List.of("S256"),
                "token_endpoint_auth_methods_supported", List.of("none")
        );
    }
}
