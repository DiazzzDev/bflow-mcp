package bflow.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import bflow.mcp.support.AbstractToolIntegrationTest;

/**
 * Exercises {@link CreateBudgetTool}'s propose-then-confirm behavior
 * (ADR-0010 §18) through the real AOP chain, with only the BFlow API
 * stubbed out.
 */
class CreateBudgetToolIntegrationTest extends AbstractToolIntegrationTest {

    @Autowired
    private CreateBudgetTool createBudgetTool;

    @Test
    void withoutConfirmationReturnsAPreviewAndCallsNothing() {
        authenticateAs("user-123", "bflow-mcp/budgets.write");

        // No expectation registered: if this reaches BflowApiClient at
        // all, mockBflowApi.verify() below fails.
        String result = createBudgetTool.createBudget(
                "wallet-1", 200.0, "USD", "MONTHLY", "2026-09-01",
                null, null, null);

        assertThat(result)
                .contains("\"status\":\"CONFIRMATION_REQUIRED\"")
                .contains("\"action\":\"CREATE_BUDGET\"")
                .contains("\"amount\":200.0");
        mockBflowApi.verify();
    }

    @Test
    void withConfirmationTrueActuallyCreatesTheBudget() {
        authenticateAs("user-123", "bflow-mcp/budgets.write");

        String budgetJson = "{\"data\":{\"id\":\"b-1\"},\"success\":true}";

        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/budgets"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", org.hamcrest.Matchers.notNullValue()))
                .andExpect(jsonPath("$.walletId").value("wallet-1"))
                .andExpect(jsonPath("$.amount").value(200.0))
                .andExpect(jsonPath("$.scope").value("WALLET"))
                .andRespond(withSuccess(budgetJson, MediaType.APPLICATION_JSON));

        String result = createBudgetTool.createBudget(
                "wallet-1", 200.0, "USD", "MONTHLY", "2026-09-01",
                null, null, true);

        assertThat(result).isEqualTo(budgetJson);
        mockBflowApi.verify();
    }
}