package bflow.mcp.security;

import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Local-only synthetic identity for exercising MCP Apps with basic-host. */
@Configuration
@Profile("mcp-apps-dev & !prod & !production")
public class McpAppsDevJwtConfiguration {

    /** The configured bind address, which must stay loopback-only. */
    private final String serverAddress;

    /** Active profiles; the synthetic identity must be the sole profile. */
    private final Environment environment;

    /** Creates the profile guard. */
    public McpAppsDevJwtConfiguration(
            @Value("${server.address:}") final String address,
            final Environment activeProfiles) {
        this.serverAddress = address;
        this.environment = activeProfiles;
    }

    /** Refuse to start the synthetic-identity profile on a non-local bind. */
    @Bean
    SmartInitializingSingleton requireLoopbackForMcpAppsDev() {
        return () -> {
            String[] profiles = environment.getActiveProfiles();
            if (profiles.length != 1 || !"mcp-apps-dev".equals(profiles[0])) {
                throw new IllegalStateException(
                        "mcp-apps-dev must be the only active Spring profile");
            }
            if (!"127.0.0.1".equals(serverAddress)
                    && !"localhost".equals(serverAddress)) {
                throw new IllegalStateException(
                        "mcp-apps-dev requires server.address=127.0.0.1");
            }
        };
    }

    /** Accept only the documented, non-secret local test token. */
    @Bean
    JwtDecoder mcpAppsDevJwtDecoder() {
        return token -> {
            if (!"bflow-mcp-apps-local".equals(token)) {
                throw new BadJwtException("Invalid local MCP Apps test token");
            }
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("mcp-apps-local-user")
                    .claim("scope", "bflow-mcp/budgets.read "
                            + "bflow-mcp/wallets.read bflow-mcp/categories.read "
                            + "bflow-mcp/transactions.read "
                            + "bflow-mcp/transactions.write "
                            + "bflow-mcp/budgets.write "
                            + "bflow-mcp/recurring.read "
                            + "bflow-mcp/recurring.write")
                    .build();
        };
    }

    /** Allow only the official localhost basic-host origin to call /mcp. */
    @Bean
    CorsConfigurationSource mcpAppsDevCorsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("http://localhost:8080"));
        cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept",
                "Mcp-Session-Id", "MCP-Protocol-Version", "Last-Event-ID"));
        cors.setExposedHeaders(List.of("Mcp-Session-Id", "MCP-Protocol-Version"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/mcp", cors);
        return source;
    }
}
