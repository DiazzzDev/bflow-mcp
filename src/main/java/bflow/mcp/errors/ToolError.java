package bflow.mcp.errors;

/**
 * Structured, agent-safe error shape (ADR-0010 §8).
 *
 * @param code a short, stable machine-readable error code.
 * @param message a human-readable explanation, safe to show the user —
 *      never a raw exception message or stack trace.
 * @param retryable whether retrying the same call might succeed without
 *      the caller changing anything (e.g. a transient upstream outage).
 */
public record ToolError(String code, String message, boolean retryable) {
}
