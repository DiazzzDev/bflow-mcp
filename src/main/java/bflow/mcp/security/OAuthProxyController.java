package bflow.mcp.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OAuth proxy in front of Cognito's real {@code /oauth2/authorize} and
 * {@code /oauth2/token} endpoints, plus the callback leg of bflow-mcp's
 * own stateless DCR shim (ADR-0002).
 *
 * <p>Cognito does not implement RFC 8707 (Resource Indicators): it
 * rejects any request carrying a {@code resource} parameter, which
 * every spec-compliant MCP client sends unconditionally. This class
 * strips that parameter on the way through, same as before.</p>
 *
 * <p>It now also does one more substitution on {@code /oauth/authorize}
 * and {@code /oauth/token}: the {@code client_id} an MCP client
 * presents is one of bflow-mcp's own signed {@code client_id} tokens
 * (see {@link ClientRegistrationController}), not a Cognito client id.
 * {@link #authorize} decodes it, checks the requested
 * {@code redirect_uri} matches what was registered, and forwards to
 * Cognito with the one real, fixed {@code cognitoAppClientId} instead —
 * Cognito itself still only ever sees a single static App Client, same
 * as before DCR existed. The caller's real {@code redirect_uri} and
 * {@code state} travel through Cognito's login/consent UI packed into
 * an "outer state" JWT, since Cognito's own {@code redirect_uri} for
 * this flow is now always bflow-mcp's fixed {@code /oauth/callback};
 * {@link #callback} unpacks that JWT and does the final redirect back
 * to the caller's real, allowlisted destination.</p>
 *
 * <p>Cognito's real token response (and its {@code iss} claim) still
 * passes through {@link #token} unmodified — this proxy mints no
 * tokens of its own. It signs exactly two kinds of JWT
 * ({@link ProxyTokenCodec}), and neither is ever presented to a
 * resource server as an access token.</p>
 */
@RestController
public class OAuthProxyController {

    /** Query/form params this proxy substitutes rather than forwards verbatim. */
    private static final Set<String> SUBSTITUTED_PARAMS =
            Set.of("resource", "client_id", "redirect_uri", "state");

    /** Cognito's Hosted UI domain, e.g. {@code xxx.auth.us-east-1.amazoncognito.com}. */
    private final String hostedUiDomain;

    /** bflow-mcp's own public URL — used to build the fixed Cognito-facing callback. */
    private final String publicBaseUrl;

    /** The one real, static Cognito App Client id every DCR client_id maps to. */
    private final String cognitoAppClientId;

    private final ProxyTokenCodec tokenCodec;
    private final RedirectUriAllowlist allowlist;

    /** Client used to forward the token exchange to Cognito. */
    private final RestClient restClient;

    /**
     * Creates the proxy.
     * @param hostedUiDomain Cognito's Hosted UI domain (not the issuer).
     * @param publicBaseUrl bflow-mcp's own public URL.
     * @param cognitoAppClientId the real Cognito App Client id
     *      ({@code bflow-mcp-agent-client}, provisioned by
     *      {@code infra/17-mcp-cognito-client.sh}).
     * @param tokenCodec signs/verifies this proxy's own JWTs.
     * @param allowlist the redirect URIs {@link #authorize} will accept,
     *      re-checked here even though {@link ClientRegistrationController}
     *      already checked it once at registration time — a
     *      long-lived {@code client_id} token shouldn't outlive a
     *      platform being removed from config.
     * @param oauthProxyRestClient the client used to forward the token
     *      exchange to Cognito — {@code RestClientConfig}'s bean, not
     *      built inline, so a test can bind a mock server to it (same
     *      pattern as {@code BflowApiClient}'s).
     */
    public OAuthProxyController(
            @Value("${bflow.cognito.hosted-ui-domain}") final String hostedUiDomain,
            @Value("${bflow.mcp.public-base-url}") final String publicBaseUrl,
            @Value("${bflow.cognito.app-client-id}") final String cognitoAppClientId,
            final ProxyTokenCodec tokenCodec,
            final RedirectUriAllowlist allowlist,
            final RestClient oauthProxyRestClient) {
        this.hostedUiDomain = hostedUiDomain;
        this.publicBaseUrl = publicBaseUrl;
        this.cognitoAppClientId = cognitoAppClientId;
        this.tokenCodec = tokenCodec;
        this.allowlist = allowlist;
        this.restClient = oauthProxyRestClient;
    }

    /**
     * Proxies the authorization request: decodes the caller's
     * {@code client_id} token, validates {@code redirect_uri} against
     * it and against the allowlist, then 302-redirects the browser to
     * Cognito's real Hosted UI — with the real App Client id, a fixed
     * callback URI, and an outer-state JWT in place of the caller's own.
     *
     * @param params every query parameter the client sent.
     * @return a redirect to Cognito's real authorize endpoint.
     * @throws ResponseStatusException 400 if {@code client_id} doesn't
     *      decode, or {@code redirect_uri} doesn't match the
     *      registration or isn't currently allowlisted.
     */
    @GetMapping("/oauth/authorize")
    public RedirectView authorize(@RequestParam final Map<String, String> params) {
        ProxyTokenCodec.ClientRegistration registration;
        try {
            registration = tokenCodec.decodeClientRegistration(params.get("client_id"));
        } catch (InvalidProxyTokenException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid client_id", e);
        }

        String requestedRedirectUri = params.get("redirect_uri");
        if (!registration.redirectUri().equals(requestedRedirectUri)
                || !allowlist.isAllowed(requestedRedirectUri)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "redirect_uri does not match this client's registration");
        }

        String outerState = tokenCodec.encodeCallbackState(requestedRedirectUri, params.get("state"));

        UriComponentsBuilder target = UriComponentsBuilder
                .fromUriString("https://" + hostedUiDomain + "/oauth2/authorize");

        params.forEach((key, value) -> {
            if (!SUBSTITUTED_PARAMS.contains(key)) {
                target.queryParam(key, value);
            }
        });

        target.queryParam("client_id", cognitoAppClientId);
        target.queryParam("redirect_uri", cognitoFacingCallbackUri());
        target.queryParam("state", outerState);

        return new RedirectView(target.build().toUriString());
    }

    /**
     * The callback leg of the DCR shim: Cognito redirects here (the
     * fixed URI every {@link #authorize} call now requests) with
     * {@code code} and the outer-state JWT as {@code state}. Unpacks
     * that JWT and does the real, final redirect back to the caller's
     * own {@code redirect_uri} with its original {@code state}.
     *
     * @param params query parameters Cognito sent: {@code code} and
     *      {@code state} on success, or {@code error}/{@code error_description}
     *      if the user denied consent or something else went wrong.
     * @return a redirect to the caller's real, allowlisted redirect URI.
     * @throws ResponseStatusException 400 if {@code state} doesn't
     *      decode or has expired — deliberately an error page, never a
     *      redirect to an unverified destination.
     */
    @GetMapping("/oauth/callback")
    public RedirectView callback(@RequestParam final Map<String, String> params) {
        ProxyTokenCodec.CallbackState callbackState;
        try {
            callbackState = tokenCodec.decodeCallbackState(params.get("state"));
        } catch (InvalidProxyTokenException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "invalid or expired outer state", e);
        }

        UriComponentsBuilder target = UriComponentsBuilder
                .fromUriString(callbackState.realRedirectUri());

        if (params.containsKey("code")) {
            target.queryParam("code", params.get("code"));
        }
        if (params.containsKey("error")) {
            target.queryParam("error", params.get("error"));
        }
        if (params.containsKey("error_description")) {
            target.queryParam("error_description", params.get("error_description"));
        }
        if (callbackState.realState() != null) {
            target.queryParam("state", callbackState.realState());
        }

        return new RedirectView(target.build().toUriString());
    }

    /**
     * Proxies the token exchange: forwards the POST to Cognito's real
     * token endpoint, with {@code resource} stripped and, for a DCR
     * client, both {@code client_id} AND {@code redirect_uri} mapped
     * to the fixed values {@link #authorize} actually presented to
     * Cognito. Both have to move together: Cognito ties an
     * authorization code to the exact {@code redirect_uri} used to
     * request it (RFC 6749 §4.1.3), and since {@link #authorize}
     * substitutes {@link #cognitoFacingCallbackUri()} for the caller's
     * real one, presenting the caller's real {@code redirect_uri} back
     * here — even though it's the value the caller itself used when it
     * called OUR {@code /oauth/authorize} — is a mismatch from
     * Cognito's point of view and fails with {@code invalid_redirect}.
     * Same header-rebuilding rationale as before: a fresh, minimal
     * header set rather than Cognito's raw transport headers copied
     * verbatim, since blindly relaying those (e.g.
     * {@code Transfer-Encoding}/{@code Content-Length}) produces a
     * response the browser's {@code fetch} can't reconstruct
     * ("Failed to construct 'Headers': Invalid name"). Uses
     * {@code exchange()} rather than {@code retrieve()} so a 4xx from
     * Cognito (a real grant error the client needs to see) is returned
     * as-is instead of thrown as an exception.
     *
     * @param formParams the form-encoded token request body.
     * @return Cognito's status and body, with only {@code Content-Type}
     *      set explicitly.
     */
    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> token(
            @RequestParam final MultiValueMap<String, String> formParams) {

        // Decided once, up front: whether client_id/redirect_uri get
        // substituted depends on the SAME decision for both, so it
        // can't be made independently per key inside the forEach below
        // (form param iteration order isn't guaranteed to see client_id
        // before redirect_uri).
        String presentedClientId = formParams.getFirst("client_id");
        boolean isDcrClient = presentedClientId != null && isDcrClientId(presentedClientId);

        MultiValueMap<String, String> forwarded = new LinkedMultiValueMap<>();
        formParams.forEach((key, values) -> {
            if ("resource".equals(key)) {
                return;
            }
            if ("client_id".equals(key)) {
                forwarded.put("client_id",
                        List.of(isDcrClient ? cognitoAppClientId : presentedClientId));
                return;
            }
            if ("redirect_uri".equals(key) && isDcrClient) {
                forwarded.put("redirect_uri", List.of(cognitoFacingCallbackUri()));
                return;
            }
            forwarded.put(key, values);
        });

        return restClient.post()
                .uri(URI.create("https://" + hostedUiDomain + "/oauth2/token"))
                .header(HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .body(forwarded)
                .exchange((request, response) -> {
                    String body = new String(
                            response.getBody().readAllBytes(),
                            StandardCharsets.UTF_8);

                    return ResponseEntity.status(response.getStatusCode())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(body);
                });
    }

    /**
     * bflow-mcp's own callback URL, as registered in Cognito's App
     * Client ({@code infra/17-mcp-cognito-client.sh}'s
     * {@code CALLBACK_URLS}) — the single fixed {@code redirect_uri}
     * every {@link #authorize} call now sends to Cognito, regardless of
     * which real platform is behind the DCR {@code client_id}.
     * @return {@code publicBaseUrl + "/oauth/callback"}.
     */
    private String cognitoFacingCallbackUri() {
        return publicBaseUrl + "/oauth/callback";
    }

    /**
     * @param clientId the {@code client_id} a token request presented.
     * @return {@code true} if it decodes as one of our own DCR
     *      registration tokens.
     */
    private boolean isDcrClientId(final String clientId) {
        try {
            tokenCodec.decodeClientRegistration(clientId);
            return true;
        } catch (InvalidProxyTokenException e) {
            return false;
        }
    }
}