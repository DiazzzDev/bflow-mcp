package bflow.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import bflow.mcp.security.RequiresScope;
import bflow.mcp.ui.McpUiPayloadFactory;
import bflow.mcp.ui.TransactionFormUiMetaProvider;

/** Opens a localized transaction form for MCP Apps-capable hosts. */
@Component
public class OpenTransactionFormTool {

    /** Produces localized fallbacks and form labels. */
    private final McpUiPayloadFactory payloadFactory;

    /**
     * Creates the tool.
     * @param payloadFactory localized MCP App payload factory.
     */
    public OpenTransactionFormTool(final McpUiPayloadFactory payloadFactory) {
        this.payloadFactory = payloadFactory;
    }

    /**
     * Returns a form payload; the app invokes existing scoped server tools
     * for wallets, categories and the final write.
     * @return localized JSON fallback and MCP App payload.
     */
    @McpTool(description = "Opens a transaction form with wallet and category "
            + "selectors. Hosts without MCP Apps receive localized JSON.",
            metaProvider = TransactionFormUiMetaProvider.class)
    @RequiresScope("bflow-mcp/transactions.write")
    public String openTransactionForm() {
        return payloadFactory.transactionForm();
    }
}
