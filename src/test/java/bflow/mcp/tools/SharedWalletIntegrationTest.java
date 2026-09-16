package bflow.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import bflow.mcp.support.AbstractToolIntegrationTest;

/**
 * Verifies bflow-mcp adds no wallet-role restriction of its own on top
 * of what BFlow's API already enforces (ADR-0009 §6 "Shared wallets";
 * ADR-0010 §1's structural no-bypass guarantee).
 *
 * <p>What BFlow's real authorization actually does, confirmed against
 * {@code ServiceExpense}/{@code WalletAccessDeniedException}: creating
 * or updating a transaction only checks <b>wallet membership</b>
 * (does a {@code WalletUser} row exist for this user and wallet?), not
 * {@code WalletRole} — a {@code MEMBER} and an {@code OWNER} have
 * identical permissions for expenses, incomes, budgets, and recurring
 * items. {@code WalletRole.OWNER} only matters for wallet-management
 * operations (sharing, deletion) that bflow-mcp exposes no tool for at
 * all. So there is no "MEMBER blocked, OWNER allowed" case to test at
 * this layer — what actually needs verifying is that a MEMBER succeeds
 * exactly like an OWNER would (no accidental extra gate in bflow-mcp),
 * and that a genuine non-member is still denied, unchanged.</p>
 */
class SharedWalletIntegrationTest extends AbstractToolIntegrationTest {

    @Autowired
    private CreateTransactionTool createTransactionTool;

    @Test
    void aWalletMemberCanCreateATransactionJustLikeAnOwnerCan() {
        // "member-user" here only has bflow-mcp's own scope grant — it
        // says nothing about their WalletRole in the target wallet.
        // That distinction lives entirely on the BFlow API side, which
        // this test stubs as if the user were a MEMBER (not OWNER) of
        // "shared-wallet-1" and confirms bflow-mcp doesn't care either
        // way — it has no logic that could.
        authenticateAs("member-user", "bflow-mcp/transactions.write");

        String expenseJson = "{\"data\":{\"id\":\"e-1\",\"walletId\":"
                + "\"shared-wallet-1\"},\"success\":true}";

        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/expenses"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(expenseJson, MediaType.APPLICATION_JSON));

        String result = createTransactionTool.createTransaction(
                "EXPENSE", "Groceries", 45.0, "2026-09-14",
                "shared-wallet-1", "category-1", null, null, true);

        assertThat(result).isEqualTo(expenseJson);
        mockBflowApi.verify();
    }

    @Test
    void aNonMemberIsStillDeniedExactlyAsBflowsApiSays() {
        authenticateAs("outsider-user", "bflow-mcp/transactions.write");

        mockBflowApi.expect(requestTo("http://bflow-api-under-test/api/v1/expenses"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"success\":false,\"message\":"
                                + "\"User does not have access to this wallet\"}"));

        String result = createTransactionTool.createTransaction(
                "EXPENSE", "Groceries", 45.0, "2026-09-14",
                "shared-wallet-1", "category-1", null, null, true);

        assertThat(result)
                .contains("\"code\":\"RESOURCE_ACCESS_DENIED\"")
                .contains("\"retryable\":false");
        mockBflowApi.verify();
    }
}