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
    @McpTool(title = "Open transaction form",
            description = "Open a form to enter an expense or income and "
                    + "choose its wallet and category.",
            annotations = @McpTool.McpAnnotations(title = "Open transaction form",
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false),
            metaProvider = TransactionFormUiMetaProvider.class)
    @RequiresScope("bflow-mcp/transactions.write")
    public String openTransactionForm() {
        return payloadFactory.transactionForm();
    }
}
