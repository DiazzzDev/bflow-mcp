package bflow.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.security.RequiresScope;

/**
 * Lists the authenticated user's unified transaction history across all
 * their wallets. Thin proxy — no business logic here, matching
 * {@link ListWalletsTool}'s shape (ADR-0010 §7: response shape design
 * for this tool is still owed to issue #9, not solved here).
 */
@Component
public class ListTransactionsTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     */
    public ListTransactionsTool(final BflowApiClient bflowApiClient) {
        this.bflowApiClient = bflowApiClient;
    }

    /**
     * Lists the authenticated user's transactions across every wallet
     * they belong to, most recent first (API default sort/page size).
     * @return the raw JSON body returned by BFlow's API.
     */
    @Tool(description = "Lists the current user's transactions "
            + "(expenses, incomes, transfers) across all their wallets.")
    @RequiresScope("bflow-mcp/transactions.read")
    public String listTransactions() {
        return bflowApiClient.get("/api/v1/transactions", String.class);
    }
}
