package bflow.mcp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for bflow-mcp.
 *
 * <p>Same shape as BFlow's {@code SecurityConfig}: stateless, JWT-only,
 * CSRF disabled for the same reason (no cookies, no sessions). This
 * service does not authorize resources — that stays inside the BFlow API
 * (ADR-0009 §4). The only local authorization decision is the scope gate,
 * applied by {@link RequiresScopeAspect} on top of whatever Spring
 * Security's JWT decoding already put in the {@code Authentication}.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** This instance's own public URL (see {@link OAuthProxyController}). */
    private final String publicBaseUrl;

    /**
     * Creates the config.
     * @param publicBaseUrl this instance's public URL, from
     *      {@code bflow.mcp.public-base-url}.
     */
    public SecurityConfig(
            @Value("${bflow.mcp.public-base-url}") final String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * MCP endpoints (default {@code /mcp}) require authentication;
     * actuator health, the OAuth proxy, and its metadata document stay
     * open — they're the pre-authentication plumbing itself.
     *
     * <p>The Protected Resource Metadata endpoint (RFC 9728, served at
     * {@code /.well-known/oauth-protected-resource}) declares
     * <b>bflow-mcp itself</b> as the authorization server, not Cognito
     * directly. Cognito doesn't implement RFC 8707 (the {@code resource}
     * parameter every spec-compliant MCP client sends) and rejects the
     * request outright, so {@link OAuthProxyController} sits in front of
     * Cognito's real {@code /authorize} and {@code /token}, stripping
     * only that one parameter. {@link AuthorizationServerMetadataController}
     * publishes the metadata document pointing at that proxy.</p>
     *
     * @param http the security object to configure.
     * @return the built filter chain.
     * @throws Exception if configuration fails.
     */
    @Bean
    public SecurityFilterChain filterChain(final HttpSecurity http)
            throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sess -> sess.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/oauth/**",
                                "/.well-known/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .protectedResourceMetadata(prm -> prm
                                .protectedResourceMetadataCustomizer(
                                        customizer -> customizer
                                                .authorizationServer(
                                                        publicBaseUrl))))
                .build();
    }
}
