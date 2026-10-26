package bflow.mcp.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import bflow.mcp.i18n.MessageService;

/** Builds localized JSON fallbacks and payloads for cacheable MCP App HTML. */
@Component
public class McpUiPayloadFactory {

    /** JSON mapper supplied by Spring. */
    private final ObjectMapper objectMapper;

    /** Resolves visible UI text for the current request. */
    private final MessageService messageService;

    /**
     * Creates the payload factory.
     * @param objectMapper JSON mapper supplied by Spring.
     * @param messageService request-localized message facade.
     */
    public McpUiPayloadFactory(final ObjectMapper objectMapper,
            final MessageService messageService) {
        this.objectMapper = objectMapper;
        this.messageService = messageService;
    }

    /**
     * Adds localized labels and locale information to raw budget API data.
     * @param rawBudgets raw JSON returned by BFlow.
     * @return a useful JSON fallback and MCP App payload.
     */
    public String budgets(final String rawBudgets) {
        return serialize(rawBudgets, Map.of(
                "title", messageService.get("ui.budgets.title"),
                "empty", messageService.get("ui.budgets.empty"),
                "loading", messageService.get("ui.budgets.loading"),
                "retry", messageService.get("ui.budgets.retry"),
                "progress", messageService.get("ui.budgets.progress"),
                "spent", messageService.get("ui.budgets.spent"),
                "remaining", messageService.get("ui.budgets.remaining"),
                "limit", messageService.get("ui.budgets.limit")));
    }

    /**
     * Returns the localized initial state for the transaction form.
     * @return a useful JSON fallback and MCP App payload.
     */
    public String transactionForm() {
        return serialize("{}", Map.ofEntries(
                Map.entry("title", messageService.get("ui.transaction.title")),
                Map.entry("loading", messageService.get("ui.transaction.loading")),
                Map.entry("type", messageService.get("ui.transaction.type")),
                Map.entry("expense", messageService.get("ui.transaction.type.expense")),
                Map.entry("income", messageService.get("ui.transaction.type.income")),
                Map.entry("name", messageService.get("ui.transaction.name")),
                Map.entry("amount", messageService.get("ui.transaction.amount")),
                Map.entry("date", messageService.get("ui.transaction.date")),
                Map.entry("wallet", messageService.get("ui.transaction.wallet")),
                Map.entry("category", messageService.get("ui.transaction.category")),
                Map.entry("description", messageService.get("ui.transaction.description")),
                Map.entry("submit", messageService.get("ui.transaction.submit")),
                Map.entry("empty", messageService.get("ui.transaction.empty")),
                Map.entry("retry", messageService.get("ui.transaction.retry"))));
    }

    private String serialize(final String rawData, final Map<String, String> labels) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("locale", LocaleContextHolder.getLocale().toLanguageTag());
        response.put("labels", labels);
        response.put("data", readOrText(rawData));
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize MCP App payload", exception);
        }
    }

    private Object readOrText(final String rawData) {
        try {
            JsonNode parsed = objectMapper.readTree(rawData);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return rawData;
        }
    }
}
