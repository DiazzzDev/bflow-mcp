package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;

import bflow.mcp.support.AbstractToolIntegrationTest;

/**
 * Exercises the full DCR shim end to end, at the controller-bean level
 * — same style as the tool tests: no MockMvc, the {@code @RestController}
 * beans are called directly, with {@link #mockBflowApi} standing in for
 * Cognito on the one leg ({@code /oauth/token}) that makes a real HTTP
 * call (see {@code RestClientConfig#oauthProxyRestClient}, which shares
 * the same overridden builder {@link #mockBflowApi} is bound to).
 *
 * <p>Allowlisted redirect URI for these tests, per
 * {@code AbstractToolIntegrationTest}'s shared properties:
 * {@code https://claude.ai/api/mcp/auth_callback}.</p>
 */
class DcrShimIntegrationTest extends AbstractToolIntegrationTest {

    private static final String ALLOWED_REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";
    private static final String DISALLOWED_REDIRECT_URI = "https://evil.example.com/callback";

    @Autowired
    private ClientRegistrationController registrationController;

    @Autowired
    private OAuthProxyController proxyController;

    @Test
    void registerIssuesAClientIdForAnAllowlistedRedirectUri() {
        var response = registrationController.register(
                new ClientRegistrationController.ClientRegistrationRequest(
                        "Claude", List.of(ALLOWED_REDIRECT_URI)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("client_name", "Claude");
        assertThat(response.getBody().get("client_id")).isNotNull();
    }

    @Test
    void registerRejectsARedirectUriNotOnTheAllowlist() {
        var response = registrationController.register(
                new ClientRegistrationController.ClientRegistrationRequest(
                        "Attacker", List.of(DISALLOWED_REDIRECT_URI)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_redirect_uri");
    }

    @Test
    void registerRejectsMoreThanOneRedirectUri() {
        var response = registrationController.register(
                new ClientRegistrationController.ClientRegistrationRequest(
                        "Claude", List.of(ALLOWED_REDIRECT_URI, DISALLOWED_REDIRECT_URI)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "invalid_client_metadata");
    }

    @Test
    void authorizeRedirectsToCognitoWithTheRealClientIdAndAnOuterState() {
        String clientId = registerAndGetClientId(ALLOWED_REDIRECT_URI);

        RedirectView redirect = proxyController.authorize(Map.of(
                "client_id", clientId,
                "redirect_uri", ALLOWED_REDIRECT_URI,
                "state", "callers-original-state",
                "resource", "https://mcp.bflow-studio.com",
                "code_challenge", "abc123",
                "code_challenge_method", "S256"));

        String location = redirect.getUrl();
        assertThat(location).startsWith("https://example.auth.us-east-1.amazoncognito.com/oauth2/authorize");
        assertThat(location).contains("client_id=test-real-cognito-app-client-id");
        assertThat(location).contains("redirect_uri=http://localhost:8081/oauth/callback");
        assertThat(location).contains("code_challenge=abc123");
        assertThat(location).contains("code_challenge_method=S256");
        assertThat(location).doesNotContain("resource=");
        // The caller's own state never reaches Cognito directly — only
        // the outer-state JWT does.
        assertThat(location).doesNotContain("state=callers-original-state");
    }

    @Test
    void authorizeRejectsARedirectUriThatDoesNotMatchTheRegistration() {
        String clientId = registerAndGetClientId(ALLOWED_REDIRECT_URI);

        assertThatThrownBy(() -> proxyController.authorize(Map.of(
                "client_id", clientId,
                "redirect_uri", DISALLOWED_REDIRECT_URI)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void authorizeRejectsAnUndecodableClientId() {
        assertThatThrownBy(() -> proxyController.authorize(Map.of(
                "client_id", "not-a-valid-token",
                "redirect_uri", ALLOWED_REDIRECT_URI)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void callbackDecodesTheOuterStateAndRedirectsToTheRealCaller() {
        String clientId = registerAndGetClientId(ALLOWED_REDIRECT_URI);
        RedirectView authorizeRedirect = proxyController.authorize(Map.of(
                "client_id", clientId,
                "redirect_uri", ALLOWED_REDIRECT_URI,
                "state", "callers-original-state"));
        String outerState = extractQueryParam(authorizeRedirect.getUrl(), "state");

        RedirectView callbackRedirect = proxyController.callback(Map.of(
                "code", "cognito-issued-code",
                "state", outerState));

        String location = callbackRedirect.getUrl();
        assertThat(location).startsWith(ALLOWED_REDIRECT_URI);
        assertThat(location).contains("code=cognito-issued-code");
        assertThat(location).contains("state=callers-original-state");
    }

    @Test
    void callbackRejectsAnInvalidOuterState() {
        assertThatThrownBy(() -> proxyController.callback(Map.of(
                "code", "cognito-issued-code",
                "state", "not-a-valid-outer-state")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void tokenMapsTheDcrClientIdToTheRealCognitoClientIdBeforeForwarding() {
        String clientId = registerAndGetClientId(ALLOWED_REDIRECT_URI);

        mockBflowApi.expect(requestTo("https://example.auth.us-east-1.amazoncognito.com/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("client_id=test-real-cognito-app-client-id")))
                .andExpect(content().string(not(containsString(clientId))))
                .andExpect(content().string(not(containsString("resource="))))
                .andRespond(withSuccess("{\"access_token\":\"real-token\"}", MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", "cognito-issued-code");
        form.add("client_id", clientId);
        form.add("resource", "https://mcp.bflow-studio.com");

        var response = proxyController.token(form);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("real-token");
        mockBflowApi.verify();
    }

    @Test
    void tokenForwardsANonDcrClientIdUntouched() {
        mockBflowApi.expect(requestTo("https://example.auth.us-east-1.amazoncognito.com/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("client_id=test-real-cognito-app-client-id")))
                .andRespond(withSuccess("{\"access_token\":\"real-token\"}", MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", "test-real-cognito-app-client-id");

        var response = proxyController.token(form);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        mockBflowApi.verify();
    }

    @SuppressWarnings("unchecked")
    private String registerAndGetClientId(final String redirectUri) {
        var response = registrationController.register(
                new ClientRegistrationController.ClientRegistrationRequest(
                        "Test Client", List.of(redirectUri)));
        return (String) ((Map<String, Object>) response.getBody()).get("client_id");
    }

    private String extractQueryParam(final String url, final String name) {
        return org.springframework.web.util.UriComponentsBuilder.fromUriString(url)
                .build()
                .getQueryParams()
                .getFirst(name);
    }
}