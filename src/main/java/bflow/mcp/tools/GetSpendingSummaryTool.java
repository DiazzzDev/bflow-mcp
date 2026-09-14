package bflow.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.security.RequiresScope;

/**
 * Retrieves the "spending this month" dashboard widget for the
 * authenticated user. Thin proxy, same shape as {@link ListWalletsTool}.
 *
 * <p>Introduces a new scope, {@code bflow-mcp/dashboard.read}, not
 * anticipated in ADR-0009's original catalog (that one only covered
 * wallets/transactions/budgets/recurring). Dashboard data is a distinct
 * concern — aggregates across a user's data rather than a single
 * resource type — so it gets its own scope rather than being folded
 * into {@code transactions.read}.</p>
 */
@Component
public class GetSpendingSummaryTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     */
    public GetSpendingSummaryTool(final BflowApiClient bflowApiClient) {
        this.bflowApiClient = bflowApiClient;
    }

    /**
     * Gets the authenticated user's spending summary for the current
     * month, across all their wallets.
     * @return the raw JSON body returned by BFlow's API.
     */
    @Tool(description = "Gets a summary of the current user's spending "
            + "for the current month, across all their wallets.")
    @RequiresScope("bflow-mcp/dashboard.read")
    public String getSpendingSummary() {
        return bflowApiClient.get("/api/v1/dashboard/spending", String.class);
    }
}