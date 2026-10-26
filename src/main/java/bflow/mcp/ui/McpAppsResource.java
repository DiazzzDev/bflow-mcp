package bflow.mcp.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Serves cacheable, self-contained MCP Apps resources. */
@Component
public class McpAppsResource {

    /**
     * Returns the budgets MCP App.
     * @return HTML resource content.
     */
    @McpResource(uri = "ui://bflow/budgets", name = "bflow-budgets",
            description = "BFlow budgets view",
            mimeType = "text/html;profile=mcp-app",
            metaProvider = UiResourceMetaProvider.class)
    public String budgets() {
        return resource("mcp-ui/budgets.html");
    }

    /**
     * Returns the reusable write confirmation MCP App.
     * @return HTML resource content.
     */
    @McpResource(uri = "ui://bflow/confirmation", name = "bflow-confirmation",
            description = "BFlow write confirmation view",
            mimeType = "text/html;profile=mcp-app",
            metaProvider = UiResourceMetaProvider.class)
    public String confirmation() {
        return resource("mcp-ui/confirmation.html");
    }

    /**
     * Returns the transaction entry MCP App.
     * @return HTML resource content.
     */
    @McpResource(uri = "ui://bflow/transaction-form",
            name = "bflow-transaction-form",
            description = "BFlow transaction entry form",
            mimeType = "text/html;profile=mcp-app",
            metaProvider = UiResourceMetaProvider.class)
    public String transactionForm() {
        return resource("mcp-ui/transaction-form.html");
    }

    private String resource(final String name) {
        try (var inputStream = new ClassPathResource(name).getInputStream()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Missing MCP App resource: " + name,
                    exception);
        }
    }
}
