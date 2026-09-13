package bflow.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Smoke test: the application context must start with no real Cognito
 * issuer or BFlow API reachable. {@code JwtDecoder} is mocked so the
 * OAuth2 resource server autoconfiguration doesn't try to fetch real
 * issuer metadata during the test run.
 */
@SpringBootTest(properties = {
        "bflow.api.base-url=http://localhost:8080",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.com/dummy"
})
class McpServerApplicationTests {

    /** Prevents the real Nimbus JWT decoder from being built at startup. */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void contextLoads() {
        // Intentionally empty: a failed context load fails this test.
    }
}
