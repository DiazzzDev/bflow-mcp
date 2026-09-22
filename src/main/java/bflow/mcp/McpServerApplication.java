package bflow.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for bflow-mcp.
 *
 * <p>Deliberately depends on nothing from BFlow-Financial-Engine: no
 * shared Maven module, no domain classes, no repositories. It is an
 * independent Spring Boot application whose only relationship to BFlow
 * is being an HTTPS client of its public API (ADR-0009, ADR-0010).</p>
 */
@SpringBootApplication
public final class McpServerApplication {

    /**
     * Private — this class only exposes {@code main} and the implicit
     * class reference {@code SpringApplication.run} scans from, so
     * Checkstyle's {@code HideUtilityClassConstructor} check requires
     * hiding the default public constructor. Spring never needs to
     * instantiate this class itself.
     */
    private McpServerApplication() {
    }

    /**
     * Boots the application.
     * @param args standard Spring Boot command-line arguments.
     */
    public static void main(final String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}