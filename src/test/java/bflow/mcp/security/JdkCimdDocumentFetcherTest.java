package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Exercises {@link JdkCimdDocumentFetcher} against a local HTTP server.
 * The fetcher itself does not insist on HTTPS: that is
 * {@link CimdClientAllowlist}'s job, so plain HTTP on loopback is fine here.
 */
class JdkCimdDocumentFetcherTest {

    private final JdkCimdDocumentFetcher fetcher = new JdkCimdDocumentFetcher();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void returnsTheBodyOfAJsonResponse() throws IOException {
        URI uri = serve("/ok", 200, "application/json", "{\"client_id\":\"x\"}");

        assertThat(fetcher.fetch(uri)).isEqualTo("{\"client_id\":\"x\"}");
    }

    @Test
    void acceptsAStructuredJsonContentType() throws IOException {
        URI uri = serve("/ok", 200, "application/client-metadata+json; charset=utf-8", "{}");

        assertThat(fetcher.fetch(uri)).isEqualTo("{}");
    }

    @Test
    void rejectsANon200Status() {
        URI uri = serve("/missing", 404, "application/json", "{}");

        assertThatThrownBy(() -> fetcher.fetch(uri)).isInstanceOf(IOException.class);
    }

    @Test
    void doesNotFollowRedirects() {
        server.createContext("/target", exchange -> respond(exchange, 200, "application/json", "{}"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", base() + "/target");
            respond(exchange, 302, "application/json", "");
        });
        server.start();

        assertThatThrownBy(() -> fetcher.fetch(URI.create(base() + "/redirect")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("302");
    }

    @Test
    void rejectsANonJsonContentType() {
        URI uri = serve("/html", 200, "text/html", "<html></html>");

        assertThatThrownBy(() -> fetcher.fetch(uri)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsADocumentOverTheSizeCap() {
        URI uri = serve("/big", 200, "application/json", "x".repeat(JdkCimdDocumentFetcher.MAX_BYTES + 1));

        assertThatThrownBy(() -> fetcher.fetch(uri)).isInstanceOf(IOException.class);
    }

    @Test
    void acceptsADocumentExactlyAtTheSizeCap() throws IOException {
        URI uri = serve("/limit", 200, "application/json", "x".repeat(JdkCimdDocumentFetcher.MAX_BYTES));

        assertThat(fetcher.fetch(uri)).hasSize(JdkCimdDocumentFetcher.MAX_BYTES);
    }

    private URI serve(final String path, final int status, final String contentType, final String body) {
        server.createContext(path, exchange -> respond(exchange, status, contentType, body));
        server.start();
        return URI.create(base() + path);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(
            final com.sun.net.httpserver.HttpExchange exchange,
            final int status,
            final String contentType,
            final String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
