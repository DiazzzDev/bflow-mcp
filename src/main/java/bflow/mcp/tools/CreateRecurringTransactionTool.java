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
 * Creates a new recurring expense or income for the authenticated user
 * (e.g. a monthly subscription, a biweekly paycheck).
 *
 * <p>Distinct from {@link CreateTransactionTool}: this creates the
 * recurring <em>schedule</em> itself ({@code POST /api/v1/recurring}),
 * not a one-off transaction. BFlow generates the actual expense/income
 * entries from this on its own schedule — this tool has no involvement
 * in that generation.</p>
 */
@Component
public class CreateRecurringTransactionTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /** Propose-then-confirm gate (ADR-0010 §18). */
    private final ConfirmationGate confirmationGate;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     * @param confirmationGate the confirmation gate to check before executing.
     */
    public CreateRecurringTransactionTool(final BflowApiClient bflowApiClient,
            final ConfirmationGate confirmationGate) {
        this.bflowApiClient = bflowApiClient;
        this.confirmationGate = confirmationGate;
    }

    /**
     * Creates a new recurring expense or income schedule.
     *
     * @param type either {@code EXPENSE} or {@code INCOME}.
     * @param title short title.
     * @param amount the amount per occurrence, must be positive.
     * @param frequency {@code DAILY}, {@code WEEKLY}, or {@code MONTHLY}.
     * @param startDate the date the recurrence starts, YYYY-MM-DD.
     * @param walletId the id of the wallet this belongs to.
     * @param categoryId the id of the category this belongs to.
     * @param description optional longer description.
     * @param endDate optional date the recurrence stops, YYYY-MM-DD. If
     *      omitted, it recurs indefinitely.
     * @param intervalValue optional: repeat every N periods instead of
     *      every 1 (e.g. 2 with WEEKLY means every 2 weeks). Defaults
     *      to 1 if omitted.
     * @param confirmed pass {@code true} only after the user has seen and
     *      approved the preview this tool returns when omitted/false.
     * @return the raw JSON body returned by BFlow's API, or a
     *      {@code CONFIRMATION_REQUIRED} preview if not yet confirmed.
     */
    @Tool(description = "Creates a new recurring expense or income "
            + "schedule (e.g. a monthly subscription, a biweekly "
            + "paycheck). BFlow generates the individual transactions "
            + "from this automatically — use createTransaction instead "
            + "for a one-off entry. Requires confirmed=true to actually "
            + "execute — call once to get a preview, show it to the "
            + "user, then call again with confirmed=true.")
    @RequiresScope("bflow-mcp/recurring.write")
    public String createRecurringTransaction(
            @ToolParam(description = "EXPENSE or INCOME") final String type,
            @ToolParam(description = "Short title") final String title,
            @ToolParam(description = "Amount per occurrence, must be positive")
                final double amount,
            @ToolParam(description = "DAILY, WEEKLY, or MONTHLY") final String frequency,
            @ToolParam(description = "Start date in YYYY-MM-DD format")
                final String startDate,
            @ToolParam(description = "Wallet id this belongs to")
                final String walletId,
            @ToolParam(description = "Category id this belongs to")
                final String categoryId,
            @ToolParam(description = "Optional longer description", required = false)
                final String description,
            @ToolParam(description = "Optional end date in YYYY-MM-DD "
                    + "format. Omit for an indefinite recurrence",
                    required = false)
                final String endDate,
            @ToolParam(description = "Optional: repeat every N periods "
                    + "instead of every 1 (e.g. 2 with WEEKLY means "
                    + "every 2 weeks). Defaults to 1", required = false)
                final Integer intervalValue,
            @ToolParam(description = "Set to true only after the user "
                    + "has confirmed the preview. Omit or false to get "
                    + "a preview without creating anything", required = false)
                final Boolean confirmed) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("amount", amount);
        body.put("type", type.toUpperCase());
        body.put("frequency", frequency.toUpperCase());
        body.put("startDate", LocalDate.parse(startDate).toString());
        body.put("walletId", walletId);
        body.put("categoryId", categoryId);
        if (endDate != null) {
            body.put("endDate", LocalDate.parse(endDate).toString());
        }
        if (intervalValue != null) {
            body.put("intervalValue", intervalValue);
        }

        String preview = confirmationGate.checkOrNull(
                confirmed, "CREATE_RECURRING_TRANSACTION", body);
        if (preview != null) {
            return preview;
        }

        return bflowApiClient.post("/api/v1/recurring", body, String.class);
    }
}