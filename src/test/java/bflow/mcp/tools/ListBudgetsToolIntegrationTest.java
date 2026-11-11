package bflow.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import bflow.mcp.support.AbstractToolIntegrationTest;

/** Verifies budget progress is enriched without exposing internal identifiers to the model. */
class ListBudgetsToolIntegrationTest extends AbstractToolIntegrationTest {

    private static final String BUDGET_ID = "00000000-0000-4000-8000-000000000003";

    @Autowired
    private ListBudgetsTool listBudgetsTool;

    @Test
    void summarizesCalculatedProgressAndKeepsTheIdentifierOutOfModelText() {
        authenticateAs("user-123", "bflow-mcp/budgets.read");
        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/budgets"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":{\"content\":[{"
                        + "\"id\":\"" + BUDGET_ID + "\",\"walletName\":\"Personal\","
                        + "\"period\":\"MONTHLY\",\"scope\":\"WALLET\","
                        + "\"budgetLimit\":2999.00}]}}", MediaType.APPLICATION_JSON));
        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/budgets/"
                        + BUDGET_ID + "/detail"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":{\"currency\":\"USD\","
                        + "\"spent\":25.50,\"remaining\":2973.50,"
                        + "\"percentage\":1,\"daysLeft\":12,\"status\":\"OK\"}}",
                        MediaType.APPLICATION_JSON));

        var result = listBudgetsTool.listBudgets();

        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(text).contains("Personal", "Monthly", "25.5", "2973.5")
                .doesNotContain(BUDGET_ID, "null", "percentage", "API");
        assertThat(result.meta().toString()).contains("progressAvailable")
                .doesNotContain(BUDGET_ID);
        mockBflowApi.verify();
    }
}
