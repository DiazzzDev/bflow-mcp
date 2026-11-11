package bflow.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.security.RequiresScope;
import bflow.mcp.ui.BudgetUiMetaProvider;
import bflow.mcp.ui.McpUiPayloadFactory;

/**
 * Lists the authenticated user's budgets. Thin proxy, same shape as
 * {@link ListWalletsTool}.
 */
@Component
public class ListBudgetsTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /** Builds the localized fallback and MCP App payload. */
    private final McpUiPayloadFactory payloadFactory;

    /** Parses and merges the compact list with per-budget progress details. */
    private final ObjectMapper objectMapper;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     */
    public ListBudgetsTool(final BflowApiClient bflowApiClient,
            final McpUiPayloadFactory payloadFactory,
            final ObjectMapper objectMapper) {
        this.bflowApiClient = bflowApiClient;
        this.payloadFactory = payloadFactory;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists the authenticated user's budgets (API default page/sort).
     * @return a user-friendly summary, with detailed app-only data in MCP metadata.
     */
    @McpTool(title = "List budgets",
            description = "No arguments are required. Show the user's budgets across "
                    + "every wallet. Use "
                    + "this to answer questions about budget limits and current progress. "
                    + "Present periods in the user's language. The result uses everyday "
                    + "language and does not expose internal IDs.",
            annotations = @McpTool.McpAnnotations(title = "List budgets",
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            metaProvider = BudgetUiMetaProvider.class)
    @RequiresScope("bflow-mcp/budgets.read")
    public CallToolResult listBudgets() {
        String raw = bflowApiClient.get("/api/v1/budgets", String.class);
        return payloadFactory.budgets(withProgressDetails(raw));
    }

    private String withProgressDetails(final String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode items = root.path("data").path("content");
            if (!items.isArray()) {
                return raw;
            }
            for (JsonNode item : items) {
                String id = item.path("id").asStringOpt().orElse("");
                if (id.isBlank() || !(item instanceof ObjectNode budget)) {
                    continue;
                }
                try {
                    JsonNode detailRoot = objectMapper.readTree(bflowApiClient.get(
                            "/api/v1/budgets/" + id + "/detail", String.class));
                    JsonNode detail = detailRoot.path("data");
                    for (String field : new String[] {"spent", "remaining", "percentage",
                            "status", "endDate", "daysLeft", "currency"}) {
                        JsonNode value = detail.get(field);
                        if (value != null && !value.isNull()) {
                            budget.set(field, value);
                        }
                    }
                } catch (Exception ignored) {
                    // One missing detail should not hide the other budgets.
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to prepare budget summary", exception);
        }
    }
}
