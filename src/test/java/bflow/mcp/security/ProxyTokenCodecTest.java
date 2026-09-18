package bflow.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ProxyTokenCodec}: round-trips both token
 * kinds, and rejects tampering, wrong-secret signatures, and expiry —
 * the three failure modes {@code OAuthProxyController} relies on it to
 * catch before ever redirecting anywhere.
 */
class ProxyTokenCodecTest {

    private static final String SECRET = "unit-test-signing-secret-32-bytes-min";
    private static final String OTHER_SECRET = "a-completely-different-secret-32-byte";

    private final ProxyTokenCodec codec = new ProxyTokenCodec(SECRET);

    @Test
    void roundTripsClientRegistration() {
        String clientId = codec.encodeClientRegistration(
                "https://claude.ai/api/mcp/auth_callback", "Claude");

        ProxyTokenCodec.ClientRegistration decoded = codec.decodeClientRegistration(clientId);

        assertThat(decoded.redirectUri()).isEqualTo("https://claude.ai/api/mcp/auth_callback");
        assertThat(decoded.clientName()).isEqualTo("Claude");
    }

    @Test
    void roundTripsClientRegistrationWithoutClientName() {
        String clientId = codec.encodeClientRegistration(
                "https://claude.ai/api/mcp/auth_callback", null);

        ProxyTokenCodec.ClientRegistration decoded = codec.decodeClientRegistration(clientId);

        assertThat(decoded.redirectUri()).isEqualTo("https://claude.ai/api/mcp/auth_callback");
        assertThat(decoded.clientName()).isNull();
    }

    @Test
    void roundTripsCallbackState() {
        String state = codec.encodeCallbackState(
                "https://claude.ai/api/mcp/auth_callback", "caller-original-state");

        ProxyTokenCodec.CallbackState decoded = codec.decodeCallbackState(state);

        assertThat(decoded.realRedirectUri()).isEqualTo("https://claude.ai/api/mcp/auth_callback");
        assertThat(decoded.realState()).isEqualTo("caller-original-state");
    }

    @Test
    void roundTripsCallbackStateWithoutRealState() {
        String state = codec.encodeCallbackState(
                "https://claude.ai/api/mcp/auth_callback", null);

        ProxyTokenCodec.CallbackState decoded = codec.decodeCallbackState(state);

        assertThat(decoded.realState()).isNull();
    }

    @Test
    void rejectsClientRegistrationSignedWithWrongSecret() {
        ProxyTokenCodec otherCodec = new ProxyTokenCodec(OTHER_SECRET);
        String tokenFromOtherSecret = otherCodec.encodeClientRegistration(
                "https://claude.ai/api/mcp/auth_callback", "Claude");

        assertThatThrownBy(() -> codec.decodeClientRegistration(tokenFromOtherSecret))
                .isInstanceOf(InvalidProxyTokenException.class);
    }

    @Test
    void rejectsCallbackStateSignedWithWrongSecret() {
        ProxyTokenCodec otherCodec = new ProxyTokenCodec(OTHER_SECRET);
        String tokenFromOtherSecret = otherCodec.encodeCallbackState(
                "https://claude.ai/api/mcp/auth_callback", "state");

        assertThatThrownBy(() -> codec.decodeCallbackState(tokenFromOtherSecret))
                .isInstanceOf(InvalidProxyTokenException.class);
    }

    @Test
    void rejectsTamperedClientRegistrationPayload() {
        String clientId = codec.encodeClientRegistration(
                "https://claude.ai/api/mcp/auth_callback", "Claude");

        // Flip one character of the payload segment without re-signing
        // — simulates an attacker editing the redirect_uri claim in a
        // decoded JWT and hoping the signature still checks out.
        String[] parts = clientId.split("\\.");
        char[] payloadChars = parts[1].toCharArray();
        payloadChars[0] = payloadChars[0] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payloadChars) + "." + parts[2];

        assertThatThrownBy(() -> codec.decodeClientRegistration(tampered))
                .isInstanceOf(InvalidProxyTokenException.class);
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> codec.decodeClientRegistration("not-a-jwt"))
                .isInstanceOf(InvalidProxyTokenException.class);
    }

    @Test
    void rejectsExpiredClientRegistration() throws Exception {
        String expiredToken = signWithExplicitExpiry(
                Instant.now().minusSeconds(60), "https://claude.ai/api/mcp/auth_callback");

        assertThatThrownBy(() -> codec.decodeClientRegistration(expiredToken))
                .isInstanceOf(InvalidProxyTokenException.class);
    }

    @Test
    void rejectsExpiredCallbackState() throws Exception {
        String expiredToken = signCallbackStateWithExplicitExpiry(
                Instant.now().minusSeconds(60), "https://claude.ai/api/mcp/auth_callback");

        assertThatThrownBy(() -> codec.decodeCallbackState(expiredToken))
                .isInstanceOf(InvalidProxyTokenException.class);
    }

    @Test
    void constructorRejectsTooShortSecret() {
        assertThatThrownBy(() -> new ProxyTokenCodec("too-short"))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Signs a client-registration-shaped token with an explicit,
     * already-past expiry — {@link ProxyTokenCodec} has no way to do
     * this itself (its own TTL is always in the future), so this test
     * signs directly with nimbus using the same secret.
     */
    private String signWithExplicitExpiry(final Instant expiry, final String redirectUri) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("redirect_uri", redirectUri)
                .expirationTime(Date.from(expiry))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private String signCallbackStateWithExplicitExpiry(final Instant expiry, final String realRedirectUri)
            throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .claim("real_redirect_uri", realRedirectUri)
                .expirationTime(Date.from(expiry))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}