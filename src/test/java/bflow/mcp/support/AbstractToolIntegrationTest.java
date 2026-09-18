package bflow.mcp.support;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Shared setup for tool integration tests: a fake authenticated JWT
 * (configurable scopes/subject, ADR-0010 §3-4) and a
 * {@link MockRestServiceServer} bound to the {@code RestClient.Builder}
 * that {@code BflowApiClient} actually uses.
 *
 * <p>Two things this deliberately does NOT rely on, after both proved
 * unreliable in practice against Boot 4.1's autoconfiguration:</p>
 * <ul>
 * <li>Autowiring the framework's own autoconfigured {@code RestClient.Builder}
 * directly — it's prototype-scoped, so the test's injection point and
 * {@code BflowApiClient}'s each get a separately cloned instance, and
 * binding the mock server to the test's clone does nothing to the
 * other one.</li>
 * <li>{@code MockServerRestClientCustomizer} as a {@code RestClientCustomizer}
 * bean, relying on {@code RestClientBuilderConfigurer} to apply it to
 * every clone automatically — this did not take effect against the
 * instance {@code BflowApiClient} actually built from, for reasons not
 * fully diagnosed (likely a bean-creation-order issue between the
 * configurer resolving its customizer list and this test's
 * {@code @TestConfiguration} bean becoming available).</li>
 * </ul>
 *
 * <p>Instead: {@link MockServerTestConfig} overrides the
 * {@code RestClient.Builder} with a single, explicit {@code @Primary}
 * singleton, and binds the mock server <em>inside that same factory
 * method</em> — see that class's javadoc for why this sidesteps
 * bean-creation-order problems entirely, rather than relying on a
 * {@code @BeforeEach} or on overriding {@code bflowApiRestClient}
 * directly (both tried, both proved unreliable).</p>
 */
@SpringBootTest
@Import(AbstractToolIntegrationTest.MockServerTestConfig.class)
public abstract class AbstractToolIntegrationTest {

    /**
     * Registers test config via {@code @DynamicPropertySource} instead
     * of {@code @SpringBootTest(properties = ...)} — the latter proved
     * unreliable against these specific keys in practice (a real
     * {@code COGNITO_APP_CLIENT_ID} in the host environment won out
     * over the string-literal test property for reasons not fully
     * root-caused). {@code @DynamicPropertySource} is Spring's
     * documented highest-precedence property source, registered
     * directly against the environment right before context refresh —
     * nothing, including OS environment variables however they get
     * into the test JVM, can outrank it.
     * @param registry Spring's property registry for this mechanism.
     */
    @DynamicPropertySource
    static void dcrShimTestProperties(final DynamicPropertyRegistry registry) {
        registry.add("bflow.api.base-url", () -> "http://bflow-api-under-test");
        registry.add("bflow.mcp.public-base-url", () -> "http://localhost:8081");
        registry.add("bflow.cognito.hosted-ui-domain",
                () -> "example.auth.us-east-1.amazoncognito.com");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "https://example.com/dummy");
        // Required by ProxyTokenCodec/RedirectUriAllowlist/OAuthProxyController
        // (DCR shim, ADR-0002) — every Spring context load needs these,
        // not just DCR-specific tests, since the beans are wired at startup.
        registry.add("bflow.mcp.proxy.signing-secret",
                () -> "test-signing-secret-at-least-32-bytes-long");
        registry.add("bflow.mcp.proxy.allowed-redirect-uris",
                () -> "http://127.0.0.1:6274/oauth/callback,https://claude.ai/api/mcp/auth_callback");
        registry.add("bflow.cognito.app-client-id", () -> "test-real-cognito-app-client-id");
    }

    /** Prevents the real Nimbus JWT decoder from being built at startup. */
    @MockitoBean
    protected JwtDecoder jwtDecoder;

    /**
     * Intercepts calls {@code BflowApiClient} makes, in place of the
     * real API. Bound inside {@link MockServerTestConfig}'s
     * {@code restClientBuilder} bean, as part of constructing that
     * singleton — see that bean's javadoc for why binding has to
     * happen there rather than in a {@code @BeforeEach}.
     */
    @Autowired
    protected MockRestServiceServer mockBflowApi;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Resets {@code mockBflowApi}'s expectation manager after every
     * test. Necessary because it's now a context-scoped singleton
     * (see {@link MockServerTestConfig}), which Spring's test context
     * cache reuses across every test method — in this class and in
     * every other class sharing this same {@code @SpringBootTest}
     * configuration — rather than getting rebuilt per test. Without
     * this, the first real request made anywhere flips the manager
     * from recording into replay mode for good, and every subsequent
     * test's {@code mockBflowApi.expect(...)} call fails with
     * {@code IllegalStateException: Cannot add more expectations after
     * actual requests are made}. {@code reset()} clears that state
     * without touching the underlying {@code RestClient} or its bound
     * request factory, so the mocking itself stays intact.
     */
    @AfterEach
    void resetMockServer() {
        mockBflowApi.reset();
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
     * Overrides the app's {@code RestClient.Builder} with a single,
     * explicit singleton for the duration of the test context, so
     * there's exactly one instance in play — no prototype cloning, no
     * customizer-ordering to depend on — and binds a
     * {@link MockRestServiceServer} to it as part of that same
     * singleton's construction, so production's {@code bflowApiRestClient}
     * bean (in {@code RestClientConfig}, untouched by this class) ends
     * up building its {@code RestClient} around the already-mocked
     * request factory no matter when it runs.
     *
     * <p>Previously the binding happened in a {@code @BeforeEach},
     * which runs long after the production {@code @Bean} had already
     * called the builder's {@code .build()} and frozen an immutable
     * client around the real request factory — the mock ended up bound
     * to a builder nothing downstream ever read from again, so every
     * call from {@code BflowApiClient} went out over the real network
     * and failed against the fake {@code bflow.api.base-url} host.</p>
     *
     * <p>A later attempt tried forcing bind-before-build by having a
     * second {@code @Bean} named {@code bflowApiRestClient} (depending
     * on a {@code MockRestServiceServer} bean, to force creation order)
     * override the production one from {@code RestClientConfig}. That
     * relies on which of two same-named {@code @Bean} definitions gets
     * registered last — Spring Boot's bean-definition overriding
     * replaces purely by registration order and ignores {@code @Primary}
     * entirely when doing so, and that order isn't guaranteed between a
     * component-scanned production {@code @Configuration} and a
     * {@code @TestConfiguration} pulled in via {@code @Import}. In
     * practice the production definition won, silently un-mocking every
     * test.</p>
     *
     * <p>This version sidesteps bean-creation order altogether: the
     * mock is bound <em>inside</em> the {@code restClientBuilder}
     * factory method itself, as part of constructing that singleton.
     * Since Spring calls a given {@code @Bean} factory method at most
     * once per context and caches the result, there is no window in
     * which any consumer — production's {@code bflowApiRestClient} or
     * anything else — could observe an unbound builder, regardless of
     * which bean asks for it first. {@code bflowApiRestClient} itself
     * is never redefined, so there's nothing to race or override.</p>
     */
    @TestConfiguration
    static class MockServerTestConfig {

        /**
         * Holds the {@link MockRestServiceServer} produced as a side
         * effect of building {@link #restClientBuilder}, so it can be
         * exposed as its own bean afterwards. Exists purely to move
         * that reference out of the builder factory method without
         * needing consumers to depend on the builder bean itself.
         */
        private static final class MockServerHolder {
            private MockRestServiceServer server;
        }

        @Bean
        MockServerHolder mockServerHolder() {
            return new MockServerHolder();
        }

        /**
         * The overriding builder bean — and the only place binding
         * happens. Binding here, rather than in a separate bean or a
         * test lifecycle method, guarantees it happens exactly once,
         * before this singleton is ever handed to anyone, including
         * production's {@code bflowApiRestClient} bean.
         * @param holder populated with the resulting mock server, so
         *      {@link #mockRestServiceServer} can expose it.
         * @return a singleton {@code RestClient.Builder} already bound
         *      to a {@code MockRestServiceServer}.
         */
        @Bean
        @Primary
        RestClient.Builder restClientBuilder(final MockServerHolder holder) {
            RestClient.Builder builder = RestClient.builder();
            holder.server = MockRestServiceServer.bindTo(builder).build();
            return builder;
        }

        /**
         * Exposes the {@link MockRestServiceServer} bound inside
         * {@link #restClientBuilder} for tests (via
         * {@link AbstractToolIntegrationTest#mockBflowApi}) to set
         * request expectations on. Depending on {@code restClientBuilder}
         * (rather than just on the holder) forces Spring to run that
         * factory — and therefore populate the holder — first.
         * @param holder the holder populated by {@code restClientBuilder}.
         * @param restClientBuilder unused directly; present purely to
         *      force bean-creation order (see method javadoc).
         * @return the bound mock server.
         */
        @Bean
        MockRestServiceServer mockRestServiceServer(final MockServerHolder holder,
                final RestClient.Builder restClientBuilder) {
            return holder.server;
        }
    }
}