package bflow.mcp.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
        Map<String, String> labels = Map.ofEntries(
                Map.entry("title", messageService.get("ui.budgets.title")),
                Map.entry("empty", messageService.get("ui.budgets.empty")),
                Map.entry("loading", messageService.get("ui.budgets.loading")),
                Map.entry("retry", messageService.get("ui.budgets.retry")),
                Map.entry("progress", messageService.get("ui.budgets.progress")),
                Map.entry("spent", messageService.get("ui.budgets.spent")),
                Map.entry("remaining", messageService.get("ui.budgets.remaining")),
                Map.entry("limit", messageService.get("ui.budgets.limit")),
                Map.entry("periodDaily", messageService.get("ui.budgets.period.daily")),
                Map.entry("periodWeekly", messageService.get("ui.budgets.period.weekly")),
                Map.entry("periodMonthly", messageService.get("ui.budgets.period.monthly")),
                Map.entry("periodYearly", messageService.get("ui.budgets.period.yearly")),
                Map.entry("periodUnknown", messageService.get("ui.budgets.period.unknown")),
                Map.entry("progressUnavailable", messageService.get(
                        "ui.budgets.progressUnavailable")));
        Object data = readBudgetData(rawBudgets, labels.get("progressUnavailable"));
        return serializePayload(data, labels,
                messageService.get("ui.budgets.progressUnavailable.detail"));
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
                Map.entry("typeAccessible", messageService.get(
                        "ui.transaction.type.accessible")),
                Map.entry("expense", messageService.get("ui.transaction.type.expense")),
                Map.entry("income", messageService.get("ui.transaction.type.income")),
                Map.entry("name", messageService.get("ui.transaction.name")),
                Map.entry("amount", messageService.get("ui.transaction.amount")),
                Map.entry("date", messageService.get("ui.transaction.date")),
                Map.entry("wallet", messageService.get("ui.transaction.wallet")),
                Map.entry("category", messageService.get("ui.transaction.category")),
                Map.entry("description", messageService.get("ui.transaction.description")),
                Map.entry("optional", messageService.get("ui.transaction.optional")),
                Map.entry("namePlaceholder", messageService.get(
                        "ui.transaction.name.placeholder")),
                Map.entry("amountPlaceholder", messageService.get(
                        "ui.transaction.amount.placeholder")),
                Map.entry("dateExample", messageService.get(
                        "ui.transaction.date.example")),
                Map.entry("descriptionPlaceholder", messageService.get(
                        "ui.transaction.description.placeholder")),
                Map.entry("today", messageService.get("ui.transaction.date.today")),
                Map.entry("yesterday", messageService.get(
                        "ui.transaction.date.yesterday")),
                Map.entry("optionLoadError", messageService.get(
                        "ui.transaction.options.error")),
                Map.entry("submit", messageService.get("ui.transaction.submit")),
                Map.entry("empty", messageService.get("ui.transaction.empty")),
                Map.entry("retry", messageService.get("ui.transaction.retry")),
                Map.entry("walletEmpty", messageService.get(
                        "ui.transaction.wallet.empty")),
                Map.entry("categoryEmpty", messageService.get(
                        "ui.transaction.category.empty"))));
    }

    private String serialize(final String rawData, final Map<String, String> labels) {
        return serialize(rawData, labels, null);
    }

    private String serialize(final String rawData, final Map<String, String> labels,
            final String fallbackText) {
        return serializePayload(readOrText(rawData), labels, fallbackText);
    }

    private String serializePayload(final Object data, final Map<String, String> labels,
            final String fallbackText) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("locale", LocaleContextHolder.getLocale().toLanguageTag());
        response.put("labels", labels);
        response.put("data", data);
        if (fallbackText != null) {
            response.put("fallbackText", fallbackText);
        }
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

    private Object readBudgetData(final String rawData, final String unavailableLabel) {
        Object data = readOrText(rawData);
        if (!(data instanceof JsonNode root) || !root.isObject()) {
            return data;
        }
        JsonNode budgets = root.path("data").path("content");
        if (budgets.isArray()) {
            budgets.forEach(item -> {
                if (item instanceof ObjectNode budget
                        && (isMissing(budget.get("spent"))
                        || isMissing(budget.get("remaining"))
                        || isMissing(budget.get("percentage")))) {
                    ObjectNode display = objectMapper.createObjectNode();
                    display.put("spent", displayValue(budget.get("spent")));
                    display.put("remaining", displayValue(budget.get("remaining")));
                    display.put("percentage", displayValue(budget.get("percentage")));
                    display.put("status", unavailableLabel);
                    budget.set("progressDisplay", display);
                }
            });
        }
        return root;
    }

    private boolean isMissing(final JsonNode value) {
        return value == null || value.isNull();
    }

    private String displayValue(final JsonNode value) {
        return isMissing(value) ? "—" : value.toString();
    }
}
