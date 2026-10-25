package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/** Unit coverage for {@link CimdMetadataClient}, with a stub in place of the network. */
class CimdMetadataClientTest {

    private static final String CLIENT_ID = "https://claude.ai/oauth/mcp-oauth-client-metadata";
    private static final String REDIRECT = "https://claude.ai/api/mcp/auth_callback";

    private final AtomicInteger fetches = new AtomicInteger();
    private final AtomicReference<String> body = new AtomicReference<>(document(CLIENT_ID, "[\"" + REDIRECT + "\"]", null));
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private final MutableClock clock = new MutableClock();

    private final CimdMetadataClient client = new CimdMetadataClient(
            new CimdClientAllowlist(CLIENT_ID),
            uri -> {
                fetches.incrementAndGet();
                if (failure.get() != null) {
                    throw failure.get();
                }
                return body.get();
            },
            clock);

    @Test
    void returnsTheRedirectUrisOfAValidDocument() {
        assertThat(client.redirectUrisFor(CLIENT_ID)).containsExactly(REDIRECT);
    }

    @Test
    void rejectsAClientThatIsNotAllowlistedWithoutFetching() {
        assertThatThrownBy(() -> client.redirectUrisFor("https://evil.example.com/client.json"))
                .isInstanceOf(InvalidCimdClientException.class);
        assertThat(fetches.get()).isZero();
    }

    @Test
    void rejectsADocumentWhoseClientIdDoesNotMatchItsUrl() {
        body.set(document("https://evil.example.com/client.json", "[\"" + REDIRECT + "\"]", null));

        assertThatThrownBy(() -> client.redirectUrisFor(CLIENT_ID))
                .isInstanceOf(InvalidCimdClientException.class);
    }

    @Test
    void rejectsMissingEmptyOrMalformedRedirectUris() {
        for (String redirectUris : new String[] {"null", "[]", "\"not-an-array\"", "[\"\"]", "[1]"}) {
            body.set(document(CLIENT_ID, redirectUris, null));

            assertThatThrownBy(() -> client.redirectUrisFor(CLIENT_ID))
                    .as(redirectUris)
                    .isInstanceOf(InvalidCimdClientException.class);
        }
    }

    @Test
    void rejectsAnAuthMethodOtherThanNone() {
        body.set(document(CLIENT_ID, "[\"" + REDIRECT + "\"]", "client_secret_basic"));

        assertThatThrownBy(() -> client.redirectUrisFor(CLIENT_ID))
                .isInstanceOf(InvalidCimdClientException.class);
    }

    @Test
    void acceptsAnAuthMethodOfNone() {
        body.set(document(CLIENT_ID, "[\"" + REDIRECT + "\"]", "none"));

        assertThat(client.redirectUrisFor(CLIENT_ID)).containsExactly(REDIRECT);
    }

    @Test
    void rejectsADocumentThatIsNotJson() {
        body.set("<html>not json</html>");

        assertThatThrownBy(() -> client.redirectUrisFor(CLIENT_ID))
                .isInstanceOf(InvalidCimdClientException.class);
    }

    @Test
    void wrapsAFetchFailure() {
        failure.set(new IOException("boom"));

        assertThatThrownBy(() -> client.redirectUrisFor(CLIENT_ID))
                .isInstanceOf(InvalidCimdClientException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void cachesWithinTheTtlAndRefetchesAfterIt() {
        client.redirectUrisFor(CLIENT_ID);
        client.redirectUrisFor(CLIENT_ID);
        assertThat(fetches.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(6));
        client.redirectUrisFor(CLIENT_ID);

        assertThat(fetches.get()).isEqualTo(2);
    }

    @Test
    void doesNotCacheFailures() {
        failure.set(new IOException("boom"));
        assertThatThrownBy(() -> client.redirectUrisFor(CLIENT_ID))
                .isInstanceOf(InvalidCimdClientException.class);

        failure.set(null);

        assertThat(client.redirectUrisFor(CLIENT_ID)).containsExactly(REDIRECT);
        assertThat(fetches.get()).isEqualTo(2);
    }

    private static String document(final String clientId, final String redirectUrisJson, final String authMethod) {
        String method = authMethod == null ? "" : ",\"token_endpoint_auth_method\":\"" + authMethod + "\"";
        return "{\"client_id\":\"" + clientId + "\",\"client_name\":\"Test\",\"redirect_uris\":"
                + redirectUrisJson + method + "}";
    }

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-10-24T00:00:00Z");

        void advance(final Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
