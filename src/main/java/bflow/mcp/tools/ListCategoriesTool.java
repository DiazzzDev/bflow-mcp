package bflow.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.security.RequiresScope;

/**
 * Lists the transaction categories available in BFlow.
 *
 * <p>Closes a real gap: {@code CreateTransactionTool} and
 * {@code CreateBudgetTool} both require a {@code categoryId} parameter,
 * but until now there was no tool letting an agent discover which
 * categories exist and what their ids are. Modeled directly on
 * {@link ListWalletsTool} — no business logic here, just a proxy over
 * {@code GET /api/v1/categories} ({@code ControllerCategory.getAll()}),
 * which returns {@code ApiResponse<List<CategoryResponse>>}. Left as
 * {@code String} for the same reason {@code ListWalletsTool} is: to
 * avoid coupling this module to BFlow's DTOs before the response shape
 * for MCP tools is settled.</p>
 */
@Component
public class ListCategoriesTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     */
    public ListCategoriesTool(final BflowApiClient bflowApiClient) {
        this.bflowApiClient = bflowApiClient;
    }

    /**
     * Lists all transaction categories.
     * @return the raw JSON body returned by BFlow's API.
     */
    @Tool(description = "Lists the transaction categories available in "
            + "BFlow, including each category's id, name, type "
            + "(INCOME, EXPENSE or TRANSFER), icon and color. Use this "
            + "to look up the categoryId needed by tools that create "
            + "transactions or budgets.")
    @RequiresScope("bflow-mcp/categories.read")
    public String listCategories() {
        return bflowApiClient.get("/api/v1/categories", String.class);
    }
}