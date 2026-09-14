package bflow.mcp.confirmation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared "propose, then confirm" gate for write tools (ADR-0010 §6/§18).
 *
 * <p>Money transfers and payments never get a tool at all — that's a
 * hard exclusion, not something confirmation makes acceptable. This
 * gate exists for the write tools that <em>do</em> exist
 * (createTransaction, updateTransaction, createBudget,
 * createRecurringTransaction): the real risk with those isn't a
 * malicious agent, it's an LLM misreading "50" as "500" or picking the
 * wrong category. A human should see exactly what's about to happen
 * before it happens.</p>
 *
 * <p>Deliberately not MCP's native elicitation feature: that requires
 * a different tool-definition style ({@code @McpTool} +
 * {@code McpSyncRequestContext}) than the {@code @Tool} +
 * {@code MethodToolCallbackProvider} approach every existing tool
 * already uses, and its behavior against our pinned Spring AI version
 * hasn't been verified end-to-end. This plain-parameter approach works
 * identically with every MCP client, elicitation-capable or not.</p>
 */
@Component
public class ConfirmationGate {

    /** Serializes the preview response. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Checks whether the caller has confirmed the operation.
     *
     * @param confirmed the tool's {@code confirmed} parameter — null or
     *      false means "not yet confirmed".
     * @param action a short machine-readable name for the action, e.g.
     *      {@code "CREATE_TRANSACTION"}.
     * @param preview the parsed parameters, exactly as they would be
     *      sent to BFlow if executed — not a re-description, the actual
     *      values, so what the human confirms is what happens.
     * @return the JSON preview response to return AS the tool's result
     *      if not yet confirmed, or {@code null} if the caller should
     *      proceed with the real operation.
     */
    public String checkOrNull(final Boolean confirmed, final String action,
            final Map<String, Object> preview) {
        if (Boolean.TRUE.equals(confirmed)) {
            return null;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "CONFIRMATION_REQUIRED");
        response.put("action", action);
        response.put("preview", preview);
        response.put("message", "Nothing has happened yet. Show this to "
                + "the user, and if they approve, call this same tool "
                + "again with confirmed=true and the exact same "
                + "parameters to execute it.");

        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception neverHappens) {
            return "{\"status\":\"CONFIRMATION_REQUIRED\",\"action\":\""
                    + action + "\"}";
        }
    }
}