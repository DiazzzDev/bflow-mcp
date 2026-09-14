package bflow.mcp.support;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.test.MockServerRestClientCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;

/**
 * Shared setup for tool integration tests: a fake authenticated JWT
 * (configurable scopes/subject, ADR-0010 §3-4) and a
 * {@link MockRestServiceServer} bound to every {@code RestClient.Builder}
 * created anywhere in the app — including the one
 * {@code BflowApiClient} receives — via {@link MockServerRestClientCustomizer},
 * a {@code RestClientCustomizer} bean.
 *
 * <p>Directly autowiring {@code RestClient.Builder} and binding
 * {@code MockRestServiceServer} to it does NOT work here: that bean is
 * prototype-scoped (Spring Boot 4.x), so the test's injection point and
 * {@code BflowApiClient}'s injection point each get a separately cloned
 * instance. {@code MockServerRestClientCustomizer} is Spring Boot's own
 * answer to that — it's applied to every clone by
 * {@code RestClientBuilderConfigurer}, not just one.</p>
 */
@SpringBootTest(properties = {
        "bflow.api.base-url=http://bflow-api-under-test",
        "bflow.mcp.public-base-url=http://localhost:8081",
        "bflow.cognito.hosted-ui-domain=example.auth.us-east-1.amazoncognito.com",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.com/dummy"
})
@Import(AbstractToolIntegrationTest.MockServerTestConfig.class)
public abstract class AbstractToolIntegrationTest {

    /** Prevents the real Nimbus JWT decoder from being built at startup. */
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @Autowired
    private MockServerRestClientCustomizer mockServerRestClientCustomizer;

    /** Intercepts calls BflowApiClient makes, in place of the real API. */
    protected MockRestServiceServer mockBflowApi;

    @BeforeEach
    void bindMockServer() {
        mockBflowApi = mockServerRestClientCustomizer.getServer();
        mockBflowApi.reset();
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

    /**
     * Registers the {@link MockServerRestClientCustomizer} bean so it's
     * picked up by {@code RestClientBuilderConfigurer} and applied to
     * every {@code RestClient.Builder} instance the app creates.
     */
    @TestConfiguration
    static class MockServerTestConfig {

        /**
         * The customizer bean.
         * @return a fresh customizer, one {@code MockRestServiceServer}
         *      per test context.
         */
        @Bean
        MockServerRestClientCustomizer mockServerRestClientCustomizer() {
            return new MockServerRestClientCustomizer();
        }
    }
}
