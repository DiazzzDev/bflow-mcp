package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import bflow.mcp.support.AbstractToolIntegrationTest;

/**
 * CIMD (ADR-0004) end to end at the controller-bean level, in its own
 * context because the allowlist is a startup property. The metadata
 * document download is stubbed through {@link CimdDocumentFetcher}; the
 * Cognito token call goes through {@link #mockBflowApi}, as in
 * {@link DcrShimIntegrationTest}.
 */
class CimdIntegrationTest extends AbstractToolIntegrationTest {

    private static final String CLIENT_ID = "https://claude.ai/oauth/mcp-oauth-client-metadata";
    private static final String REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";
    private static final String COGNITO_TOKEN_URL =
            "https://example.auth.us-east-1.amazoncognito.com/oauth2/token";

    @DynamicPropertySource
    static void cimdProperties(final DynamicPropertyRegistry registry) {
        registry.add("bflow.mcp.proxy.cimd.allowed-client-ids", () -> CLIENT_ID);
    }

    @MockitoBean
    private CimdDocumentFetcher cimdFetcher;

    @Autowired
    private OAuthProxyController proxyController;

    @Autowired
    private ClientRegistrationController registrationController;

    @Autowired
    private AuthorizationServerMetadataController metadataController;

    @Autowired
    private CimdMetadataClient cimdMetadataClient;

    /** The metadata cache lives as long as the context, so reset it for every test. */
    @BeforeEach
    void clearMetadataCache() {
        cimdMetadataClient.clearCache();
    }

    @Test
    void authorizeAcceptsAnAllowlistedCimdClientAndForwardsToCognitoWithTheRealClientId() throws IOException {
        stubDocument(CLIENT_ID, REDIRECT_URI);

        RedirectView redirect = proxyController.authorize(Map.of(
                "client_id", CLIENT_ID,
                "redirect_uri", REDIRECT_URI,
                "state", "callers-original-state",
                "code_challenge", "abc123",
                "code_challenge_method", "S256"));

        assertThat(redirect.getUrl()).startsWith("https://example.auth.us-east-1.amazoncognito.com/oauth2/authorize");
        assertThat(redirect.getUrl()).contains("client_id=test-real-cognito-app-client-id");
        assertThat(redirect.getUrl()).contains("redirect_uri=http://localhost:8081/oauth/callback");
        assertThat(redirect.getUrl()).contains("code_challenge=abc123");
        assertThat(redirect.getUrl()).doesNotContain("mcp-oauth-client-metadata");
        assertThat(redirect.getUrl()).doesNotContain("state=callers-original-state");
    }

    @Test
    void theCallbackStillReturnsTheCallerToItsRealRedirectUri() throws IOException {
        stubDocument(CLIENT_ID, REDIRECT_URI);
        RedirectView authorize = proxyController.authorize(Map.of(
                "client_id", CLIENT_ID,
                "redirect_uri", REDIRECT_URI,
                "state", "callers-original-state"));
        String outerState = UriComponentsBuilder.fromUriString(authorize.getUrl())
                .build().getQueryParams().getFirst("state");

        RedirectView callback = proxyController.callback(Map.of(
                "code", "cognito-issued-code",
                "state", outerState));

        assertThat(callback.getUrl()).startsWith(REDIRECT_URI);
        assertThat(callback.getUrl()).contains("code=cognito-issued-code");
        assertThat(callback.getUrl()).contains("state=callers-original-state");
    }

    @Test
    void authorizeRejectsARedirectUriTheMetadataDocumentDoesNotDeclare() throws IOException {
        // Allowlisted by the proxy, but not declared by this client's own document.
        stubDocument(CLIENT_ID, "http://127.0.0.1:6274/oauth/callback");

        assertThatThrownBy(() -> proxyController.authorize(Map.of(
                "client_id", CLIENT_ID,
                "redirect_uri", REDIRECT_URI)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void authorizeRejectsARedirectUriDeclaredByTheDocumentButNotAllowlistedByTheProxy() throws IOException {
        stubDocument(CLIENT_ID, "https://evil.example.com/callback");

        assertThatThrownBy(() -> proxyController.authorize(Map.of(
                "client_id", CLIENT_ID,
                "redirect_uri", "https://evil.example.com/callback")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void authorizeRejectsAnHttpsClientIdThatIsNotAllowlistedWithoutFetching() {
        assertThatThrownBy(() -> proxyController.authorize(Map.of(
                "client_id", "https://evil.example.com/client.json",
                "redirect_uri", REDIRECT_URI)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(cimdFetcher);
    }

    @Test
    void authorizeRejectsAClientWhoseDocumentCannotBeFetched() throws IOException {
        when(cimdFetcher.fetch(any())).thenThrow(new IOException("unreachable"));

        assertThatThrownBy(() -> proxyController.authorize(Map.of(
                "client_id", CLIENT_ID,
                "redirect_uri", REDIRECT_URI)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void authorizeStillAcceptsADcrClientWhileCimdIsEnabled() {
        var registration = registrationController.register(
                new ClientRegistrationController.ClientRegistrationRequest(
                        "Test Client", List.of(REDIRECT_URI)));
        @SuppressWarnings("unchecked")
        String dcrClientId = (String) ((Map<String, Object>) registration.getBody()).get("client_id");

        RedirectView redirect = proxyController.authorize(Map.of(
                "client_id", dcrClientId,
                "redirect_uri", REDIRECT_URI));

        assertThat(redirect.getUrl()).contains("client_id=test-real-cognito-app-client-id");
        verifyNoInteractions(cimdFetcher);
    }

    @Test
    void tokenMapsTheCimdClientIdAndRedirectUriBeforeForwardingToCognito() {
        mockBflowApi.expect(requestTo(COGNITO_TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("client_id=test-real-cognito-app-client-id")))
                .andExpect(content().string(
                        containsString("redirect_uri=http%3A%2F%2Flocalhost%3A8081%2Foauth%2Fcallback")))
                .andExpect(content().string(not(containsString("mcp-oauth-client-metadata"))))
                .andExpect(content().string(not(containsString("resource="))))
                .andRespond(withSuccess("{\"access_token\":\"real-token\"}", MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", "cognito-issued-code");
        form.add("code_verifier", "abc123");
        form.add("client_id", CLIENT_ID);
        form.add("redirect_uri", REDIRECT_URI);
        form.add("resource", "https://mcp.bflow-studio.com");

        var response = proxyController.token(form);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("real-token");
        mockBflowApi.verify();
    }

    @Test
    void tokenDoesNotSubstituteAnHttpsClientIdThatIsNotAllowlisted() {
        mockBflowApi.expect(requestTo(COGNITO_TOKEN_URL))
                .andExpect(content().string(not(containsString("test-real-cognito-app-client-id"))))
                .andRespond(withSuccess("{\"error\":\"invalid_client\"}", MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", "cognito-issued-code");
        form.add("client_id", "https://evil.example.com/client.json");

        proxyController.token(form);

        mockBflowApi.verify();
    }

    @Test
    void theMetadataDocumentAdvertisesCimdAndStillAdvertisesDcr() {
        Map<String, Object> metadata = metadataController.metadata();

        assertThat(metadata).containsEntry("client_id_metadata_document_supported", true);
        assertThat(metadata).containsKey("registration_endpoint");
    }

    private void stubDocument(final String clientId, final String redirectUri) throws IOException {
        when(cimdFetcher.fetch(any())).thenReturn(
                "{\"client_id\":\"" + clientId + "\",\"client_name\":\"Claude\","
                        + "\"redirect_uris\":[\"" + redirectUri + "\"],"
                        + "\"token_endpoint_auth_method\":\"none\"}");
    }
}
