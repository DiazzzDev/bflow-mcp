package bflow.mcp.support;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Shared setup for tool integration tests: a fake authenticated JWT
 * (configurable scopes/subject, ADR-0010 §3-4) and a
 * {@link MockRestServiceServer} bound to the real, Spring-managed
 * {@link RestClient.Builder} that {@code BflowApiClient} uses — so
 * tests exercise the actual AOP chain (scope gate, error handling,
 * observability, confirmation) with only the network boundary to
 * Cognito/BFlow stubbed out, per ADR-0010 §1-2's structural boundary.
 */
@SpringBootTest(properties = {
        "bflow.api.base-url=http://bflow-api-under-test",
        "bflow.mcp.public-base-url=http://localhost:8081",
        "bflow.cognito.hosted-ui-domain=example.auth.us-east-1.amazoncognito.com",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.com/dummy"
})
public abstract class AbstractToolIntegrationTest {

    /** Prevents the real Nimbus JWT decoder from being built at startup. */
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    /** The same builder {@code BflowApiClient} was constructed with. */
    @Autowired
    private RestClient.Builder restClientBuilder;

    /** Intercepts calls BflowApiClient makes, in place of the real API. */
    protected MockRestServiceServer mockBflowApi;

    @BeforeEach
    void bindMockServer() {
        mockBflowApi = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Authenticates the current thread as a user with the given scopes,
     * matching what {@link bflow.mcp.security.RequiresScopeAspect} reads
     * (the standard {@code SCOPE_*} authorities Spring Security derives).
     *
     * @param subject the JWT subject (BFlow user id).
     * @param scopes granted scopes, without the {@code SCOPE_} prefix,
     *      e.g. {@code "bflow-mcp/wallets.read"}.
     */
    protected void authenticateAs(final String subject, final String... scopes) {
        List<GrantedAuthority> authorities = List.of(scopes).stream()
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority("SCOPE_" + scope))
                .collect(Collectors.toList());

        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("scope", String.join(" ", scopes))
                .build();

        JwtAuthenticationToken authentication =
                new JwtAuthenticationToken(jwt, authorities);

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /**
     * Authenticates as a user with no granted scopes at all — for
     * asserting a tool call is denied.
     * @param subject the JWT subject (BFlow user id).
     */
    protected void authenticateWithNoScopes(final String subject) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(subject, "n/a", List.of()));
    }
}