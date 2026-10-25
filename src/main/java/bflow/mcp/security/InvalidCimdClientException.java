package bflow.mcp.security;

/**
 * Thrown by {@link CimdMetadataClient} when a CIMD {@code client_id} is
 * not allowlisted, or its metadata document cannot be fetched or fails
 * validation. Callers translate it into a 400, never a redirect.
 */
public class InvalidCimdClientException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what was rejected and why. Must not contain
     *      attacker-controlled text, since callers log it.
     */
    public InvalidCimdClientException(final String message) {
        super(message);
    }

    /**
     * @param message what was rejected and why.
     * @param cause the underlying fetch or parse failure.
     */
    public InvalidCimdClientException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
