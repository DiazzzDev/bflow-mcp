package bflow.mcp.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@code RestClient} {@link BflowApiClient} uses, as its own
 * bean rather than inline in {@code BflowApiClient}'s constructor.
 *
 * <p>Splitting this out is what makes the client's HTTP boundary
 * testable: a test overrides this one bean (see
 * {@code AbstractToolIntegrationTest.MockServerTestConfig}) with a
 * version whose request factory is already bound to a
 * {@code MockRestServiceServer} <em>before</em> the client ever touches
 * it — {@code RestClient.Builder.build()} freezes an immutable client,
 * so any mock binding attempted after that point has no effect.</p>
 */
@Configuration
public class RestClientConfig {

    /**
     * Builds the BFlow API client.
     * @param restClientBuilder Spring Boot's auto-configured builder.
     * @param baseUrl BFlow API base URL, from {@code bflow.api.base-url}.
     * @return the built, base-URL-configured client.
     */
    @Bean
    public RestClient bflowApiRestClient(final RestClient.Builder restClientBuilder,
            @Value("${bflow.api.base-url}") final String baseUrl) {
        return restClientBuilder.baseUrl(baseUrl).build();
    }

    /**
     * Builds the {@code RestClient} {@code OAuthProxyController} uses to
     * forward the token exchange to Cognito. No base URL: the proxy
     * builds Cognito's full URL itself from {@code hosted-ui-domain}.
     * Split out for the same reason as {@link #bflowApiRestClient} —
     * so a test can bind a {@code MockRestServiceServer} to it before
     * the controller ever calls {@code .build()} on the shared builder.
     * @param restClientBuilder Spring Boot's auto-configured builder.
     * @return the built client.
     */
    @Bean
    public RestClient oauthProxyRestClient(final RestClient.Builder restClientBuilder) {
        return restClientBuilder.build();
    }
}