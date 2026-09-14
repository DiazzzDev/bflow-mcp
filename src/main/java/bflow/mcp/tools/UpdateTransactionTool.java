package bflow.mcp.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.confirmation.ConfirmationGate;
import bflow.mcp.security.RequiresScope;

/**
 * Updates an existing expense or income entry for the authenticated user.
 *
 * <p>BFlow's {@code PUT /{id}} endpoints are full replaces, not partial
 * patches: every field in {@code ExpenseRequest}/{@code IncomeRequest}
 * must be sent, not just the ones that changed. This tool mirrors that —
 * every parameter here is required, matching {@link CreateTransactionTool}'s
 * shape exactly (same post-refactor DTO, per main@45be5c2).</p>
 *
 * <p>An AI agent calling this should have first read the existing
 * transaction (via {@link ListTransactionsTool}) to know its current
 * values before deciding what to change — this tool has no partial-update
 * semantics to fall back on.</p>
 */
@Component
public class UpdateTransactionTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /** Propose-then-confirm gate (ADR-0010 §18). */
    private final ConfirmationGate confirmationGate;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     * @param confirmationGate the confirmation gate to check before executing.
     */
    public UpdateTransactionTool(final BflowApiClient bflowApiClient,
            final ConfirmationGate confirmationGate) {
        this.bflowApiClient = bflowApiClient;
        this.confirmationGate = confirmationGate;
    }

    /**
     * Replaces an existing expense or income entry.
     *
     * @param type either {@code EXPENSE} or {@code INCOME} — must match
     *      the transaction's actual type.
     * @param transactionId the id of the transaction to update.
     * @param title short title, 1-50 characters.
     * @param amount the transaction amount, must be positive.
     * @param date the transaction date, must not be in the future.
     * @param walletId the id of the wallet this belongs to.
     * @param categoryId the id of the category this belongs to.
     * @param description optional longer description, up to 100 characters.
     * @param recurring whether this transaction is recurring — pass the
     *      transaction's current value (read it first) to avoid
     *      accidentally turning recurrence off, since this is a full
     *      replace, not a partial update.
     * @param confirmed pass {@code true} only after the user has seen and
     *      approved the preview this tool returns when omitted/false.
     * @return the raw JSON body returned by BFlow's API, or a
     *      {@code CONFIRMATION_REQUIRED} preview if not yet confirmed.
     */
    @Tool(description = "Updates an existing expense or income entry. "
            + "This replaces the whole entry, so pass every field, not "
            + "just the one that changed — read the transaction first "
            + "if unsure of its current values, especially 'recurring' "
            + "(passing recurring=false on a recurring transaction turns "
            + "recurrence off). Requires confirmed=true to actually "
            + "execute — call once to get a preview, show it to the "
            + "user, then call again with confirmed=true.")
    @RequiresScope("bflow-mcp/transactions.write")
    public String updateTransaction(
            @ToolParam(description = "EXPENSE or INCOME — must match the "
                    + "transaction's actual type") final String type,
            @ToolParam(description = "Id of the transaction to update")
                final String transactionId,
            @ToolParam(description = "Short title, 1-50 characters") final String title,
            @ToolParam(description = "Amount, must be positive") final double amount,
            @ToolParam(description = "Date in YYYY-MM-DD format, not in the future")
                final String date,
            @ToolParam(description = "Wallet id this transaction belongs to")
                final String walletId,
            @ToolParam(description = "Category id this transaction belongs to")
                final String categoryId,
            @ToolParam(description = "Optional longer description, up to "
                    + "100 characters", required = false)
                final String description,
            @ToolParam(description = "Whether this transaction is "
                    + "recurring — use its CURRENT value, not false by "
                    + "default, unless intentionally turning recurrence off")
                final boolean recurring,
            @ToolParam(description = "Set to true only after the user "
                    + "has confirmed the preview. Omit or false to get "
                    + "a preview without changing anything", required = false)
                final Boolean confirmed) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("amount", amount);
        body.put("date", LocalDate.parse(date).toString());
        body.put("walletId", walletId);
        body.put("categoryId", categoryId);
        body.put("recurring", recurring);

        String path;
        if ("EXPENSE".equalsIgnoreCase(type)) {
            path = "/api/v1/expenses/" + transactionId;
        } else if ("INCOME".equalsIgnoreCase(type)) {
            path = "/api/v1/incomes/" + transactionId;
        } else {
            throw new IllegalArgumentException(
                    "type must be EXPENSE or INCOME, got: " + type);
        }

        String preview = confirmationGate.checkOrNull(
                confirmed, "UPDATE_TRANSACTION", body);
        if (preview != null) {
            return preview;
        }

        return bflowApiClient.put(path, body, String.class);
    }
}