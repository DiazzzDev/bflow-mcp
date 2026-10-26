package bflow.mcp.security;

/**
 * Thrown by {@link ProxyTokenCodec} when a {@code client_id} or outer
 * {@code state} token fails to verify: bad signature, malformed
 * payload, or expired. Callers translate this into a 400 — never into
 * a redirect, since the whole point of verifying these tokens is to
 * avoid sending the browser somewhere an attacker chose.
 */
public class InvalidProxyTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what failed to verify and why.
     */
    public InvalidProxyTokenException(final String message) {
        super(message);
    }

    /**
     * @param message what failed to verify and why.
     * @param cause the underlying parse/signature exception.
     */
    public InvalidProxyTokenException(final String message, final Throwable cause) {
        super(message, cause);
    }
}