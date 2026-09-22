package bflow.mcp.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.confirmation.ConfirmationGate;
import bflow.mcp.security.RequiresScope;

/**
 * Creates a new expense or income entry for the authenticated user.
 *
 * <p>Unified into one tool rather than two ({@code createExpense} /
 * {@code createIncome}) because that's how a person actually talks about
 * it ("me compré un teléfono", "recibí mi salario") — the {@code type}
 * parameter routes internally to BFlow's real {@code /api/v1/expenses}
 * or {@code /api/v1/incomes} endpoint. As of BFlow-Financial-Engine's
 * "drop decorative flags" refactor (main@45be5c2), both endpoints share
 * the exact same request shape — no more {@code taxDeductible},
 * {@code reimbursable}, or {@code taxable} to route between.</p>
 *
 * <p>Deliberately out of scope here: recurring transactions (roadmap
 * issue #15 owns that) and receipt attachments. Both left at their
 * BFlow API defaults ({@code recurring=false}, no receipt).</p>
 *
 * <p>Error translation for a bad {@code type}, an unowned wallet/category,
 * or any other validation failure is handled uniformly by
 * {@link bflow.mcp.errors.ToolErrorHandlingAspect} (ADR-0010 §8) — this
 * class only throws {@link IllegalArgumentException} for a bad
 * {@code type}, and lets everything else propagate.</p>
 */
@Component
public class CreateTransactionTool {

    /**
     * Value sent for BFlow's {@code source} field, which is {@code NOT
     * NULL} at the database level ({@code incomes}/{@code expenses})
     * but was — until this fix — never populated here, since
     * {@code BaseTransactionRequest} on the BFlow side doesn't mark it
     * required and so let a missing value pass validation, only to
     * fail as an opaque {@code 409 CONFLICT} ("violates a database
     * constraint") once Hibernate tried the insert. BFlow's own
     * {@code source} javadoc documents the convention this value
     * follows: {@code manual, receipt, voice, import, prediction} —
     * {@code agent} extends that same convention for entries an AI
     * assistant creates on the user's behalf through this tool.
     */
    private static final String TRANSACTION_SOURCE = "agent";

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /** Propose-then-confirm gate (ADR-0010 §18). */
    private final ConfirmationGate confirmationGate;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     * @param confirmationGate the confirmation gate to check before executing.
     */
    public CreateTransactionTool(final BflowApiClient bflowApiClient,
            final ConfirmationGate confirmationGate) {
        this.bflowApiClient = bflowApiClient;
        this.confirmationGate = confirmationGate;
    }

    /**
     * Creates a new expense or income entry.
     *
     * @param type either {@code EXPENSE} or {@code INCOME}.
     * @param title short title, 1-50 characters.
     * @param amount the transaction amount, must be positive.
     * @param date the transaction date, must not be in the future.
     * @param walletId the id of the wallet this belongs to.
     * @param categoryId the id of the category this belongs to.
     * @param description optional longer description, up to 100 characters.
     * @param idempotencyKey optional — pass the SAME value again if
     *      retrying this exact call after an unclear result (timeout,
     *      no response), so BFlow returns the original result instead
     *      of creating a duplicate. Omit for a genuinely new transaction
     *      (a fresh key is generated automatically).
     * @param confirmed pass {@code true} only after the user has seen and
     *      approved the preview this tool returns when omitted/false.
     * @return the raw JSON body returned by BFlow's API, or a
     *      {@code CONFIRMATION_REQUIRED} preview if not yet confirmed.
     */
    @Tool(description = "Creates a new expense or income entry for the "
            + "current user (e.g. 'I bought a phone for $500' is an "
            + "EXPENSE, 'I got paid my salary' is an INCOME). Does not "
            + "handle recurring transactions. If retrying after an "
            + "unclear result, pass the same idempotencyKey used before "
            + "instead of calling again blind. Requires confirmed=true "
            + "to actually execute — call once to get a preview, show it "
            + "to the user, then call again with confirmed=true.")
    @RequiresScope("bflow-mcp/transactions.write")
    public String createTransaction(
            @ToolParam(description = "EXPENSE or INCOME") final String type,
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
            @ToolParam(description = "Optional: reuse the same value "
                    + "when retrying this exact call after an unclear "
                    + "result, to avoid creating a duplicate. Omit for "
                    + "a new transaction", required = false)
                final String idempotencyKey,
            @ToolParam(description = "Set to true only after the user "
                    + "has confirmed the preview. Omit or false to get "
                    + "a preview without creating anything", required = false)
                final Boolean confirmed) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("amount", amount);
        body.put("date", LocalDate.parse(date).toString());
        body.put("walletId", walletId);
        body.put("categoryId", categoryId);
        body.put("recurring", false);
        body.put("source", TRANSACTION_SOURCE);

        String path;
        if ("EXPENSE".equalsIgnoreCase(type)) {
            path = "/api/v1/expenses";
        } else if ("INCOME".equalsIgnoreCase(type)) {
            path = "/api/v1/incomes";
        } else {
            throw new IllegalArgumentException(
                    "type must be EXPENSE or INCOME, got: " + type);
        }

        String preview = confirmationGate.checkOrNull(
                confirmed, "CREATE_TRANSACTION", body);
        if (preview != null) {
            return preview;
        }

        String key = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : UUID.randomUUID().toString();

        return bflowApiClient.post(path, body, String.class, key);
    }
}