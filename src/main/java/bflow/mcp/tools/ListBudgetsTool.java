package bflow.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

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

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     */
    public ListBudgetsTool(final BflowApiClient bflowApiClient,
            final McpUiPayloadFactory payloadFactory) {
        this.bflowApiClient = bflowApiClient;
        this.payloadFactory = payloadFactory;
    }

    /**
     * Lists the authenticated user's budgets (API default page/sort).
     * @return the raw JSON body returned by BFlow's API.
     */
    @McpTool(description = "Lists the current user's budgets, across all "
            + "their wallets. Hosts without MCP Apps receive localized JSON.",
            metaProvider = BudgetUiMetaProvider.class)
    @RequiresScope("bflow-mcp/budgets.read")
    public String listBudgets() {
        return payloadFactory.budgets(
                bflowApiClient.get("/api/v1/budgets", String.class));
    }
}
