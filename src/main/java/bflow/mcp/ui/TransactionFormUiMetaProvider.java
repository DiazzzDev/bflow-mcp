package bflow.mcp.ui;

import java.util.Map;

import org.springframework.ai.mcp.annotation.context.MetaProvider;

/** Adds the standard MCP Apps resource link to the transaction form. */
public class TransactionFormUiMetaProvider implements MetaProvider {

    @Override
    public Map<String, Object> getMeta() {
        return Map.of("ui", Map.of("resourceUri", "ui://bflow/transaction-form"));
    }
}
