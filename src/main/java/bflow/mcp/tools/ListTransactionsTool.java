package bflow.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
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
    @McpTool(title = "List transactions",
            description = "No requiere parámetros: ejecútala con {} sin pedir datos "
                    + "al usuario. Muestra los gastos, ingresos y transferencias "
                    + "de todas sus billeteras. Resume en lenguaje cotidiano y no "
                    + "muestres IDs, respuestas de API ni nombres de campos internos.",
            annotations = @McpTool.McpAnnotations(title = "List transactions",
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @RequiresScope("bflow-mcp/transactions.read")
    public String listTransactions() {
        return bflowApiClient.get("/api/v1/transactions", String.class);
    }
}
