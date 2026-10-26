package bflow.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Smoke test: the application context must start with no real Cognito
 * issuer, Hosted UI, or BFlow API reachable. {@code JwtDecoder} is
 * mocked so the OAuth2 resource server autoconfiguration doesn't try to
 * fetch real issuer metadata during the test run.
 */
@SpringBootTest
class McpServerApplicationTests {

    /** Prevents the real Nimbus JWT decoder from being built at startup. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    /**
     * See {@code AbstractToolIntegrationTest#dcrShimTestProperties} for
     * why this uses {@code @DynamicPropertySource} rather than
     * {@code @SpringBootTest(properties = ...)}: the latter was
     * observed losing to a real {@code COGNITO_APP_CLIENT_ID} present
     * in the host environment for this specific key.
     * @param registry Spring's property registry for this mechanism.
     */
    @DynamicPropertySource
    static void dcrShimTestProperties(final DynamicPropertyRegistry registry) {
        registry.add("bflow.api.base-url", () -> "http://localhost:8080");
        registry.add("bflow.mcp.public-base-url", () -> "http://localhost:8081");
        registry.add("bflow.cognito.hosted-ui-domain",
                () -> "example.auth.us-east-1.amazoncognito.com");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "https://example.com/dummy");
        // Required by ProxyTokenCodec/RedirectUriAllowlist/OAuthProxyController
        // (DCR shim, ADR-0002).
        registry.add("bflow.mcp.proxy.signing-secret",
                () -> "test-signing-secret-at-least-32-bytes-long");
        registry.add("bflow.mcp.proxy.allowed-redirect-uris",
                () -> "https://claude.ai/api/mcp/auth_callback");
        registry.add("bflow.cognito.app-client-id", () -> "test-real-cognito-app-client-id");
    }

    @Test
    void contextLoads() {
        // Intentionally empty: a failed context load fails this test.
    }
}