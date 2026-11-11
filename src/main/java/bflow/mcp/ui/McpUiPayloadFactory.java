package bflow.mcp.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import bflow.mcp.i18n.MessageService;

/** Builds localized tool responses and payloads for cacheable MCP App HTML. */
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
    public CallToolResult budgets(final String rawBudgets) {
        Map<String, String> labels = Map.ofEntries(
                Map.entry("title", messageService.get("ui.budgets.title")),
                Map.entry("empty", messageService.get("ui.budgets.empty")),
                Map.entry("loading", messageService.get("ui.budgets.loading")),
                Map.entry("retry", messageService.get("ui.budgets.retry")),
                Map.entry("refresh", messageService.get("ui.budgets.refresh")),
                Map.entry("progress", messageService.get("ui.budgets.progress")),
                Map.entry("spent", messageService.get("ui.budgets.spent")),
                Map.entry("remaining", messageService.get("ui.budgets.remaining")),
                Map.entry("limit", messageService.get("ui.budgets.limit")),
                Map.entry("periodDaily", messageService.get("ui.budgets.period.daily")),
                Map.entry("periodWeekly", messageService.get("ui.budgets.period.weekly")),
                Map.entry("periodMonthly", messageService.get("ui.budgets.period.monthly")),
                Map.entry("periodYearly", messageService.get("ui.budgets.period.yearly")),
                Map.entry("periodUnknown", messageService.get("ui.budgets.period.unknown")),
                Map.entry("progressUnavailable", messageService.get("ui.budgets.progressUnavailable")),
                Map.entry("percentUsed", messageService.get("ui.budgets.percentUsed")),
                Map.entry("search", messageService.get("ui.budgets.search")),
                Map.entry("all", messageService.get("ui.budgets.all")),
                Map.entry("monthly", messageService.get("ui.budgets.monthly")),
                Map.entry("weekly", messageService.get("ui.budgets.weekly")),
                Map.entry("yearly", messageService.get("ui.budgets.yearly")),
                Map.entry("daily", messageService.get("ui.budgets.daily")),
                Map.entry("wallet", messageService.get("ui.budgets.wallet")),
                Map.entry("category", messageService.get("ui.budgets.category")),
                Map.entry("healthy", messageService.get("ui.budgets.healthy")),
                Map.entry("statusUnavailable", messageService.get("ui.budgets.statusUnavailable")),
                Map.entry("warning", messageService.get("ui.budgets.warning")),
                Map.entry("critical", messageService.get("ui.budgets.critical")),
                Map.entry("daysLeft", messageService.get("ui.budgets.daysLeft")),
                Map.entry("budgetCountOne", messageService.get(
                        "ui.budgets.count.one")),
                Map.entry("budgetCountMany", messageService.get(
                        "ui.budgets.count.many")));
        Object data = readBudgetData(rawBudgets, labels.get("progressUnavailable"));
        Map<String, Object> appPayload = new LinkedHashMap<>();
        appPayload.put("locale", LocaleContextHolder.getLocale().toLanguageTag());
        appPayload.put("labels", labels);
        appPayload.put("data", data);
        appPayload.put("fallbackText", messageService.get("ui.budgets.progressUnavailable"));
        try {
            return CallToolResult.builder()
                    .addTextContent(budgetSummary(data))
                    .meta(Map.of("io.bflow/budgets", appPayload))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize MCP budget result", exception);
        }
    }

    /**
     * Returns the localized initial state for the transaction form.
     * @return a useful JSON fallback and MCP App payload.
     */
    public String transactionForm() {
        return serialize("{}", Map.ofEntries(
                Map.entry("title", messageService.get("ui.transaction.title")),
                Map.entry("subtitle", messageService.get("ui.transaction.subtitle")),
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
                Map.entry("categoryOptional", messageService.get("ui.transaction.category.optional")),
                Map.entry("categoryNone", messageService.get("ui.transaction.category.none")),
                Map.entry("categoryHelp", messageService.get("ui.transaction.category.help")),
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
                Map.entry("saveExpense", messageService.get(
                        "ui.transaction.submit.expense")),
                Map.entry("saveIncome", messageService.get(
                        "ui.transaction.submit.income")),
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
        JsonNode budgets = root.path("data");
        if (budgets.isArray()) {
            return root;
        }
        budgets = root.path("data").path("content");
        if (budgets.isArray()) {
            ObjectNode sanitized = objectMapper.createObjectNode();
            sanitized.put("count", budgets.size());
            var items = objectMapper.createArrayNode();
            budgets.forEach(item -> {
                if (item instanceof ObjectNode budget) {
                    ObjectNode view = objectMapper.createObjectNode();
                    copy(budget, view, "walletName", "categoryName", "name", "period",
                            "scope", "budgetLimit", "currency", "spent", "remaining",
                            "percentage", "status", "startDate", "endDate", "daysLeft");
                    if (isMissing(view.get("spent")) || isMissing(view.get("remaining"))
                            || isMissing(view.get("percentage"))) {
                        view.remove("spent");
                        view.remove("remaining");
                        view.remove("percentage");
                        view.put("progressAvailable", false);
                    } else {
                        view.put("progressAvailable", true);
                    }
                    view.put("progressUnavailableLabel", unavailableLabel);
                    items.add(view);
                }
            });
            sanitized.set("budgets", items);
            return sanitized;
        }
        return data;
    }

    private boolean isMissing(final JsonNode value) {
        return value == null || value.isNull();
    }

    private void copy(final ObjectNode source, final ObjectNode target,
            final String... fields) {
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (!isMissing(value)) {
                target.set(field, value);
            }
        }
    }

    private String budgetSummary(final Object data) {
        if (!(data instanceof JsonNode node)) {
            return messageService.get("ui.budgets.empty");
        }
        JsonNode budgets = node.path("budgets");
        if (!budgets.isArray() || budgets.size() == 0) {
            return messageService.get("ui.budgets.empty");
        }
        String summaryMessage = budgets.size() == 1
                ? messageService.get("ui.budgets.summary.one")
                : messageService.get("ui.budgets.summary.many", budgets.size());
        StringBuilder summary = new StringBuilder(summaryMessage);
        for (JsonNode budget : budgets) {
            String name = stringValue(budget.path("walletName"), stringValue(
                    budget.path("categoryName"), messageService.get("ui.budgets.nameFallback")));
            String periodCode = stringValue(budget.path("period"), "");
            String period = periodCode.isBlank() ? "" : messageService.get(
                    "ui.budgets.period." + periodCode.toLowerCase(LocaleContextHolder.getLocale()));
            summary.append("\n• ").append(name).append(" — ")
                    .append(period).append(": ")
                    .append(stringValue(budget.path("currency"), ""))
                    .append(' ').append(stringValue(budget.path("budgetLimit"), ""));
            if (budget.path("progressAvailable").asBoolean(false)) {
                summary.append("; ").append(messageService.get("ui.budgets.spent"))
                        .append(' ').append(stringValue(budget.path("currency"), ""))
                        .append(' ').append(stringValue(budget.path("spent"), ""))
                        .append(", ").append(messageService.get("ui.budgets.remaining"))
                        .append(' ').append(stringValue(budget.path("currency"), ""))
                        .append(' ').append(stringValue(budget.path("remaining"), ""))
                        .append(" ( ").append(messageService.get("ui.budgets.percentUsed"))
                        .append(' ').append(stringValue(budget.path("percentage"), ""))
                        .append("% )");
            } else {
                summary.append("; ").append(messageService.get(
                        "ui.budgets.progressUnavailable"));
            }
        }
        return summary.toString();
    }

    private String stringValue(final JsonNode node, final String fallback) {
        return node == null || node.isMissingNode() || node.isNull()
                ? fallback : node.asStringOpt().orElse(node.toString());
    }
}
