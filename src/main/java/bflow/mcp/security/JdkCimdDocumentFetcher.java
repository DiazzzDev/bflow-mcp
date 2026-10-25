package bflow.mcp.security;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Fetches client metadata documents with the JDK's {@link HttpClient}.
 *
 * <p>Deliberately strict, since the URL is chosen by a third party even
 * when it is allowlisted: redirects are never followed, the call times
 * out quickly, only a {@code 200} with a JSON content type is accepted,
 * and the body is capped. It does not block private or internal
 * addresses: that is unnecessary while every URL it is given comes from
 * {@link CimdClientAllowlist}, and must be added before that restriction
 * is ever relaxed (see ADR-0004).</p>
 */
@Component
public class JdkCimdDocumentFetcher implements CimdDocumentFetcher {

    /** Largest document accepted, in bytes. Real ones are well under 2 KB. */
    static final int MAX_BYTES = 64 * 1024;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final int HTTP_OK = 200;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    @Override
    public String fetch(final URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != HTTP_OK) {
                    throw new IOException("unexpected HTTP status " + response.statusCode());
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.toLowerCase(Locale.ROOT).contains("json")) {
                    throw new IOException("unexpected content type: " + contentType);
                }
                byte[] bytes = body.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new IOException("document larger than " + MAX_BYTES + " bytes");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching client metadata", e);
        }
    }
}
