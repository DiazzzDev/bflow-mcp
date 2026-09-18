package bflow.mcp.security;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the two kinds of stateless JWT the DCR shim uses
 * in place of any persisted state — bflow-mcp still touches no
 * database, new or shared (ADR-0009/0010, ADR-0002).
 *
 * <p><b>Client-registration tokens</b> ({@code client_id} itself) carry
 * the single {@code redirect_uri} a caller registered — see
 * {@link ClientRegistrationController}. <b>Callback-state tokens</b>
 * ("outer state") carry a caller's real {@code redirect_uri} and
 * {@code state} across the round trip through Cognito — see
 * {@link OAuthProxyController#authorize} and
 * {@link OAuthProxyController#callback}. Both are HS256-signed with the
 * same secret, {@code bflow.mcp.proxy.signing-secret} — a secret of
 * this service's own, unrelated to anything Cognito holds. This class
 * never validates a *Cognito* JWT (that's {@code SecurityConfig}'s
 * resource-server check); it only round-trips its own tokens through
 * itself.</p>
 */
@Component
public class ProxyTokenCodec {

    private static final String CLAIM_REDIRECT_URI = "redirect_uri";
    private static final String CLAIM_CLIENT_NAME = "client_name";
    private static final String CLAIM_REAL_REDIRECT_URI = "real_redirect_uri";
    private static final String CLAIM_REAL_STATE = "real_state";

    /** Client-registration tokens are long-lived: 1 year. */
    private static final long CLIENT_REGISTRATION_TTL_SECONDS = 365L * 24 * 60 * 60;

    /** Outer-state tokens only need to survive the login/consent redirect. */
    private static final long CALLBACK_STATE_TTL_SECONDS = 10L * 60;

    private final MACSigner signer;
    private final MACVerifier verifier;

    /**
     * Creates the codec.
     * @param signingSecret {@code bflow.mcp.proxy.signing-secret}.
     * @throws IllegalStateException if the secret is too short for
     *      HS256 (nimbus requires at least 256 bits / 32 bytes) —
     *      fail fast at startup rather than at the first request.
     */
    public ProxyTokenCodec(@Value("${bflow.mcp.proxy.signing-secret}") final String signingSecret) {
        byte[] secretBytes = signingSecret.getBytes(StandardCharsets.UTF_8);
        try {
            this.signer = new MACSigner(secretBytes);
            this.verifier = new MACVerifier(secretBytes);
        } catch (JOSEException e) {
            throw new IllegalStateException(
                    "bflow.mcp.proxy.signing-secret must be at least 32 bytes for HS256", e);
        }
    }

    /**
     * Encodes a client registration — this IS the {@code client_id}
     * returned from {@code POST /oauth/register}.
     * @param redirectUri the single redirect URI this registration is
     *      scoped to. The caller (see {@link ClientRegistrationController})
     *      is responsible for having already checked it against
     *      {@link RedirectUriAllowlist} — this method just encodes it.
     * @param clientName the caller-supplied display name, or {@code null}.
     * @return the signed JWT.
     */
    public String encodeClientRegistration(final String redirectUri, final String clientName) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .claim(CLAIM_REDIRECT_URI, redirectUri)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(CLIENT_REGISTRATION_TTL_SECONDS)));
        if (clientName != null) {
            claims.claim(CLAIM_CLIENT_NAME, clientName);
        }
        return sign(claims.build());
    }

    /**
     * Decodes and verifies a {@code client_id} token.
     * @param clientId the token as received from the caller.
     * @return the decoded registration.
     * @throws InvalidProxyTokenException if the signature doesn't
     *      verify, the token is malformed, is missing its
     *      {@code redirect_uri} claim, or has expired.
     */
    public ClientRegistration decodeClientRegistration(final String clientId) {
        JWTClaimsSet claims = verifyAndParse(clientId);
        String redirectUri = stringClaim(claims, CLAIM_REDIRECT_URI);
        if (redirectUri == null) {
            throw new InvalidProxyTokenException("client_id token missing redirect_uri claim");
        }
        return new ClientRegistration(redirectUri, stringClaim(claims, CLAIM_CLIENT_NAME));
    }

    /**
     * Encodes the "outer state" that carries a caller's real
     * {@code redirect_uri} and {@code state} through Cognito's
     * authorize/callback round trip.
     * @param realRedirectUri the caller's real, already-allowlisted
     *      redirect URI.
     * @param realState the caller's original {@code state} parameter —
     *      may be {@code null}, not every client sends one.
     * @return the signed JWT.
     */
    public String encodeCallbackState(final String realRedirectUri, final String realState) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .claim(CLAIM_REAL_REDIRECT_URI, realRedirectUri)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(CALLBACK_STATE_TTL_SECONDS)));
        if (realState != null) {
            claims.claim(CLAIM_REAL_STATE, realState);
        }
        return sign(claims.build());
    }

    /**
     * Decodes and verifies an outer-state token.
     * @param state the {@code state} value Cognito echoed back on the
     *      callback.
     * @return the decoded callback state.
     * @throws InvalidProxyTokenException if the signature doesn't
     *      verify, the token is malformed, is missing its
     *      {@code real_redirect_uri} claim, or has expired (the
     *      10-minute TTL covers login + consent; anything older means
     *      the flow timed out or this is a replay).
     */
    public CallbackState decodeCallbackState(final String state) {
        JWTClaimsSet claims = verifyAndParse(state);
        String realRedirectUri = stringClaim(claims, CLAIM_REAL_REDIRECT_URI);
        if (realRedirectUri == null) {
            throw new InvalidProxyTokenException("state token missing real_redirect_uri claim");
        }
        return new CallbackState(realRedirectUri, stringClaim(claims, CLAIM_REAL_STATE));
    }

    private String sign(final JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to sign proxy token", e);
        }
        return jwt.serialize();
    }

    private JWTClaimsSet verifyAndParse(final String token) {
        if (token == null) {
            throw new InvalidProxyTokenException("Proxy token is missing");
        }
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new InvalidProxyTokenException("Malformed proxy token", e);
        }
        boolean verified;
        try {
            verified = jwt.verify(verifier);
        } catch (JOSEException e) {
            throw new InvalidProxyTokenException("Proxy token signature verification failed", e);
        }
        if (!verified) {
            throw new InvalidProxyTokenException("Proxy token signature is invalid");
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidProxyTokenException("Malformed proxy token claims", e);
        }
        Date expiration = claims.getExpirationTime();
        if (expiration == null || expiration.before(new Date())) {
            throw new InvalidProxyTokenException("Proxy token has expired");
        }
        return claims;
    }

    private String stringClaim(final JWTClaimsSet claims, final String name) {
        Object value = claims.getClaim(name);
        return value instanceof String s ? s : null;
    }

    /**
     * A decoded client registration.
     * @param redirectUri the single redirect URI this client was
     *      registered for.
     * @param clientName the caller-supplied display name, or
     *      {@code null} if none was given.
     */
    public record ClientRegistration(String redirectUri, String clientName) {
    }

    /**
     * A decoded callback state.
     * @param realRedirectUri the caller's real redirect URI.
     * @param realState the caller's original {@code state}, or
     *      {@code null} if it never sent one.
     */
    public record CallbackState(String realRedirectUri, String realState) {
    }
}