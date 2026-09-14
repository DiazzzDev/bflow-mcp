package bflow.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.security.RequiresScope;

/**
 * Lists the authenticated user's budgets. Thin proxy, same shape as
 * {@link ListWalletsTool}.
 */
@Component
public class ListBudgetsTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     */
    public ListBudgetsTool(final BflowApiClient bflowApiClient) {
        this.bflowApiClient = bflowApiClient;
    }

    /**
     * Lists the authenticated user's budgets (API default page/sort).
     * @return the raw JSON body returned by BFlow's API.
     */
    @Tool(description = "Lists the current user's budgets, across all "
            + "their wallets.")
    @RequiresScope("bflow-mcp/budgets.read")
    public String listBudgets() {
        return bflowApiClient.get("/api/v1/budgets", String.class);
    }
}