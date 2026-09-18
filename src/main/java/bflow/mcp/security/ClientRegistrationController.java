package bflow.mcp.security;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A minimal RFC 7591 Dynamic Client Registration endpoint, stateless
 * by construction (ADR-0002): no client record is ever persisted.
 *
 * <p>The returned {@code client_id} IS the registration — a signed JWT
 * ({@link ProxyTokenCodec#encodeClientRegistration}) that
 * {@link OAuthProxyController#authorize} later decodes and trusts based
 * on the signature alone. Only one thing stands between this public,
 * unauthenticated endpoint and an attacker minting a {@code client_id}
 * for an arbitrary redirect URI: {@link RedirectUriAllowlist}. RFC 7591
 * allows an unauthenticated registration endpoint (and Cognito's own
 * {@code /oauth2/authorize} still requires the one real, fixed
 * {@code client_id} bflow-mcp holds, so a forged token here still can't
 * complete a real login) — but skipping the allowlist check would
 * reopen the exact open-redirect / authorization-code-theft risk
 * Cognito's lack of DCR was accidentally preventing (see ADR-0001,
 * ADR-0002).</p>
 */
@RestController
public class ClientRegistrationController {

    private final ProxyTokenCodec tokenCodec;
    private final RedirectUriAllowlist allowlist;

    /**
     * Creates the controller.
     * @param tokenCodec signs the {@code client_id} token.
     * @param allowlist the only allowed {@code redirect_uris} values.
     */
    public ClientRegistrationController(final ProxyTokenCodec tokenCodec,
            final RedirectUriAllowlist allowlist) {
        this.tokenCodec = tokenCodec;
        this.allowlist = allowlist;
    }

    /**
     * Registers a client and returns a {@code client_id} scoped to a
     * single, allowlisted redirect URI.
     * @param request the RFC 7591 registration request. Only
     *      {@code client_name} and a single-element {@code redirect_uris}
     *      are supported — anything else in a real request body is
     *      ignored, per the spec this shim intentionally trims down to.
     * @return 201 with the registration response, or 400 with an
     *      RFC 7591 {@code error}/{@code error_description} body if
     *      {@code redirect_uris} doesn't hold exactly one allowlisted URI.
     */
    @PostMapping("/oauth/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody final ClientRegistrationRequest request) {

        List<String> redirectUris = request.redirectUris();
        if (redirectUris == null || redirectUris.size() != 1) {
            return badRequest("invalid_client_metadata",
                    "redirect_uris must contain exactly one URI");
        }

        String redirectUri = redirectUris.get(0);
        if (!allowlist.isAllowed(redirectUri)) {
            return badRequest("invalid_redirect_uri",
                    "redirect_uri is not on the allowlist for this server");
        }

        String clientId = tokenCodec.encodeClientRegistration(redirectUri, request.clientName());

        Map<String, Object> body = Map.of(
                "client_id", clientId,
                "client_name", request.clientName() == null ? "" : request.clientName(),
                "redirect_uris", List.of(redirectUri),
                "token_endpoint_auth_method", "none",
                "grant_types", List.of("authorization_code", "refresh_token"),
                "response_types", List.of("code"));

        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private ResponseEntity<Map<String, Object>> badRequest(final String error, final String description) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", error, "error_description", description));
    }

    /**
     * The subset of RFC 7591's registration request this endpoint
     * supports.
     * @param clientName optional display name.
     * @param redirectUris must contain exactly one URI to be accepted.
     */
    public record ClientRegistrationRequest(
            @JsonProperty("client_name") String clientName,
            @JsonProperty("redirect_uris") List<String> redirectUris) {
    }
}