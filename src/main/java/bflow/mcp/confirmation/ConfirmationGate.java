package bflow.mcp.confirmation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.context.i18n.LocaleContextHolder;

import tools.jackson.databind.ObjectMapper;

import bflow.mcp.i18n.MessageService;

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
 * {@code McpSyncRequestContext}) than this application's simple
 * annotation-based tools, and its behavior against our pinned Spring AI
 * version hasn't been verified end-to-end. This plain-parameter approach
 * works identically with every MCP client, elicitation-capable or not.</p>
 */
@Component
public class ConfirmationGate {

    /** Serializes the preview response. */
    private final ObjectMapper objectMapper;

    /** Resolves visible text for the active request locale. */
    private final MessageService messageService;

    /**
     * Creates the confirmation gate.
     * @param objectMapper JSON serializer supplied by Spring.
     * @param messageService request-localized message facade.
     */
    public ConfirmationGate(final ObjectMapper objectMapper,
            final MessageService messageService) {
        this.objectMapper = objectMapper;
        this.messageService = messageService;
    }

    /**
     * Checks whether the caller has confirmed the operation.
     *
     * @param confirmed the tool's {@code confirmed} parameter — null or
     *      false means "not yet confirmed".
     * @param action a short machine-readable name for the action, e.g.
     *      {@code "CREATE_TRANSACTION"}.
     * @param toolName MCP tool name to invoke after the user confirms.
     * @param preview the parsed parameters, exactly as they would be
     *      sent to BFlow if executed — not a re-description, the actual
     *      values, so what the human confirms is what happens.
     * @return the JSON preview response to return AS the tool's result
     *      if not yet confirmed, or {@code null} if the caller should
     *      proceed with the real operation.
     */
    public String checkOrNull(final Boolean confirmed, final String action,
            final String toolName, final Map<String, Object> preview) {
        if (Boolean.TRUE.equals(confirmed)) {
            return null;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "CONFIRMATION_REQUIRED");
        response.put("action", action);
        response.put("toolName", toolName);
        response.put("preview", preview);
        response.put("locale", LocaleContextHolder.getLocale().toLanguageTag());
        response.put("message", messageService.get("tool.confirmation.required"));
        response.put("labels", Map.of(
                "title", messageService.get("tool.confirmation.title"),
                "confirm", messageService.get("tool.confirmation.confirm"),
                "cancel", messageService.get("tool.confirmation.cancel")));

        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception neverHappens) {
            return "{\"status\":\"CONFIRMATION_REQUIRED\",\"action\":\""
                    + action + "\",\"message\":\""
                    + messageService.get("tool.confirmation.required") + "\"}";
        }
    }
}
