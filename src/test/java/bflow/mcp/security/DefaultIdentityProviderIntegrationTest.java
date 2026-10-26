package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

import bflow.mcp.support.AbstractToolIntegrationTest;

/** Verifies the opt-in default IdP without changing the normal proxy contract. */
class DefaultIdentityProviderIntegrationTest extends AbstractToolIntegrationTest {

    private static final String ALLOWED_REDIRECT_URI = "https://claude.ai/api/mcp/auth_callback";

    @DynamicPropertySource
    static void defaultIdentityProvider(final DynamicPropertyRegistry registry) {
        registry.add("bflow.cognito.default-identity-provider", () -> "Google");
    }

    @Autowired
    private ClientRegistrationController registrationController;

    @Autowired
    private OAuthProxyController proxyController;

    @Test
    void authorizeAddsTheConfiguredProviderOnceWhenTheClientDoesNotChooseOne() {
        RedirectView redirect = proxyController.authorize(Map.of(
                "client_id", registerAndGetClientId(),
                "redirect_uri", ALLOWED_REDIRECT_URI,
                "state", "callers-original-state"));

        var query = queryParams(redirect);
        assertThat(query.get("identity_provider")).containsExactly("Google");
        assertThat(query.getFirst("redirect_uri"))
                .isEqualTo("http://localhost:8081/oauth/callback");
        assertThat(query.getFirst("state")).isNotEqualTo("callers-original-state");
    }

    @Test
    void authorizePreservesAClientSelectedProviderInsteadOfApplyingTheDefault() {
        RedirectView redirect = proxyController.authorize(Map.of(
                "client_id", registerAndGetClientId(),
                "redirect_uri", ALLOWED_REDIRECT_URI,
                "identity_provider", "COGNITO"));

        assertThat(queryParams(redirect).get("identity_provider")).containsExactly("COGNITO");
    }

    @SuppressWarnings("unchecked")
    private String registerAndGetClientId() {
        var response = registrationController.register(
                new ClientRegistrationController.ClientRegistrationRequest(
                        "Test Client", List.of(ALLOWED_REDIRECT_URI)));
        return (String) ((Map<String, Object>) response.getBody()).get("client_id");
    }

    private static org.springframework.util.MultiValueMap<String, String> queryParams(
            final RedirectView redirect) {
        return UriComponentsBuilder.fromUriString(redirect.getUrl()).build().getQueryParams();
    }
}
