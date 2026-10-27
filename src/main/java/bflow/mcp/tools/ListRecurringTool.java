package bflow.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.security.RequiresScope;

/**
 * Lists the authenticated user's recurring transactions. Thin proxy,
 * same shape as {@link ListWalletsTool}.
 */
@Component
public class ListRecurringTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     */
    public ListRecurringTool(final BflowApiClient bflowApiClient) {
        this.bflowApiClient = bflowApiClient;
    }

    /**
     * Lists the authenticated user's recurring transactions.
     * @return the raw JSON body returned by BFlow's API.
     */
    @McpTool(title = "List recurring transactions",
            description = "Show the user's scheduled recurring expenses and "
                    + "income to answer questions about upcoming activity.",
            annotations = @McpTool.McpAnnotations(title = "List recurring transactions",
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @RequiresScope("bflow-mcp/recurring.read")
    public String listRecurring() {
        return bflowApiClient.get("/api/v1/recurring", String.class);
    }
}
