package bflow.mcp.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers every tool bean with the MCP server.
 *
 * <p>Add each new tool class to {@code toolObjects(...)} below as issues
 * #9-#17 add them — one line per tool, no other wiring needed.</p>
 */
@Configuration
public class ToolRegistrationConfig {

    /**
     * Exposes all tool beans to the Streamable HTTP MCP server.
     * @param listWalletsTool the wallets read tool.
     * @param listTransactionsTool the transactions read tool.
     * @param listBudgetsTool the budgets read tool.
     * @param listRecurringTool the recurring transactions read tool.
     * @param getSpendingSummaryTool the spending summary read tool.
     * @param listCategoriesTool the categories read tool.
     * @param createBudgetTool the create budget write tool.
     * @param createTransactionTool the create expense/income write tool.
     * @param updateTransactionTool the update expense/income write tool.
     * @param createRecurringTransactionTool the create recurring schedule write tool.
     * @return the callback provider Spring AI's autoconfiguration picks up.
     */
    @Bean
    public ToolCallbackProvider bflowTools(
            final ListWalletsTool listWalletsTool,
            final ListTransactionsTool listTransactionsTool,
            final ListBudgetsTool listBudgetsTool,
            final ListRecurringTool listRecurringTool,
            final GetSpendingSummaryTool getSpendingSummaryTool,
            final ListCategoriesTool listCategoriesTool,
            final CreateBudgetTool createBudgetTool,
            final CreateTransactionTool createTransactionTool,
            final UpdateTransactionTool updateTransactionTool,
            final CreateRecurringTransactionTool createRecurringTransactionTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(
                        listWalletsTool,
                        listTransactionsTool,
                        listBudgetsTool,
                        listRecurringTool,
                        getSpendingSummaryTool,
                        listCategoriesTool,
                        createBudgetTool,
                        createTransactionTool,
                        updateTransactionTool,
                        createRecurringTransactionTool)
                .build();
    }
}