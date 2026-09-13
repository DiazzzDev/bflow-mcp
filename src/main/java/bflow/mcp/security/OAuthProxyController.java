package bflow.mcp.security;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Thin OAuth proxy in front of Cognito's real {@code /oauth2/authorize}
 * and {@code /oauth2/token} endpoints.
 *
 * <p>Cognito does not implement RFC 8707 (Resource Indicators): it
 * rejects any request carrying a {@code resource} parameter, which
 * every spec-compliant MCP client sends unconditionally. This class
 * forwards every request through untouched <b>except</b> for that one
 * parameter. Nothing else — {@code code_challenge},
 * {@code code_challenge_method}, {@code state}, {@code redirect_uri},
 * all survive verbatim, or PKCE breaks.</p>
 *
 * <p>Cognito's real token response (and its {@code iss} claim) passes
 * through unmodified — this proxy is a strip-and-forward relay, not an
 * authorization server. It never mints, signs, or inspects tokens.</p>
 */
@RestController
public class OAuthProxyController {

    /** Cognito's Hosted UI domain, e.g. {@code xxx.auth.us-east-1.amazoncognito.com}. */
    private final String hostedUiDomain;

    /** Client used to forward the token exchange to Cognito. */
    private final RestClient restClient;

    /**
     * Creates the proxy.
     * @param hostedUiDomain Cognito's Hosted UI domain (not the issuer).
     */
    public OAuthProxyController(
            @Value("${bflow.cognito.hosted-ui-domain}") final String hostedUiDomain) {
        this.hostedUiDomain = hostedUiDomain;
        this.restClient = RestClient.create();
    }

    /**
     * Proxies the authorization request: 302-redirects the browser to
     * Cognito's real Hosted UI, with {@code resource} stripped.
     *
     * @param params every query parameter the client sent.
     * @return a redirect to Cognito's real authorize endpoint.
     */
    @GetMapping("/oauth/authorize")
    public RedirectView authorize(@RequestParam final Map<String, String> params) {
        UriComponentsBuilder target = UriComponentsBuilder
                .fromUriString("https://" + hostedUiDomain + "/oauth2/authorize");

        params.forEach((key, value) -> {
            if (!"resource".equals(key)) {
                target.queryParam(key, value);
            }
        });

        return new RedirectView(target.build().toUriString());
    }

    /**
     * Proxies the token exchange: forwards the POST to Cognito's real
     * token endpoint, with {@code resource} stripped, and returns
     * Cognito's status and body — but with a fresh, minimal header set,
     * not Cognito's raw transport headers copied verbatim. Blindly
     * relaying those (e.g. {@code Transfer-Encoding}/{@code Content-Length})
     * produces a response the browser's {@code fetch} can't reconstruct
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

        MultiValueMap<String, String> forwarded = new LinkedMultiValueMap<>();
        formParams.forEach((key, values) -> {
            if (!"resource".equals(key)) {
                forwarded.put(key, values);
            }
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
}