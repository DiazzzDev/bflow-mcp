package bflow.mcp.tools;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.confirmation.ConfirmationGate;
import bflow.mcp.security.RequiresScope;
import bflow.mcp.ui.ConfirmationUiMetaProvider;

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
     * @param frequency {@code DAILY}, {@code WEEKLY}, {@code MONTHLY}, or {@code YEARLY}.
     * @param startDate optional start date; defaults to today.
     * @param walletId optional wallet; BFlow chooses the default wallet when omitted.
     * @param categoryId optional category.
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
    @McpTool(title = "Create recurring transaction",
            description = "Schedule a recurring expense or income. Start date defaults to today; "
                    + "wallet and category can be omitted, and end date is optional. Get a "
                    + "preview first, show it to the user and execute only "
                    + "after approval with confirmed=true. Keep IDs and field names "
                    + "internal. Use createTransaction "
                    + "for a one-time entry.",
            annotations = @McpTool.McpAnnotations(title = "Create recurring transaction",
                    readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false),
            metaProvider = ConfirmationUiMetaProvider.class)
    @RequiresScope("bflow-mcp/recurring.write")
    public String createRecurringTransaction(
            @McpToolParam(description = "EXPENSE or INCOME") final String type,
            @McpToolParam(description = "Short title") final String title,
            @McpToolParam(description = "Amount per occurrence, must be positive")
                final double amount,
            @McpToolParam(description = "DAILY, WEEKLY, MONTHLY, or YEARLY") final String frequency,
            @McpToolParam(description = "Optional start date in YYYY-MM-DD format; defaults to today", required = false)
                final String startDate,
            @McpToolParam(description = "Optional wallet id; defaults to your primary wallet", required = false)
                final String walletId,
            @McpToolParam(description = "Optional category id", required = false)
                final String categoryId,
            @McpToolParam(description = "Optional longer description", required = false)
                final String description,
            @McpToolParam(description = "Optional end date in YYYY-MM-DD "
                    + "format. Omit for an indefinite recurrence",
                    required = false)
                final String endDate,
            @McpToolParam(description = "Optional: repeat every N periods "
                    + "instead of every 1 (e.g. 2 with WEEKLY means "
                    + "every 2 weeks). Defaults to 1", required = false)
                final Integer intervalValue,
            @McpToolParam(description = "Set to true only after the user "
                    + "has confirmed the preview. Omit or false to get "
                    + "a preview without creating anything", required = false)
                final Boolean confirmed) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", ToolInputValidator.requiredText(title, "title", 50));
        body.put("description", ToolInputValidator.optionalText(description, "description", 100));
        ToolInputValidator.positiveAmount(amount);
        body.put("amount", amount);
        body.put("type", ToolInputValidator.enumValue(type, "type", "EXPENSE", "INCOME"));
        body.put("frequency", ToolInputValidator.enumValue(frequency, "frequency", "DAILY", "WEEKLY", "MONTHLY", "YEARLY"));
        body.put("startDate", ToolInputValidator.dateOrToday(startDate, "startDate").toString());
        body.put("walletId", ToolInputValidator.optionalUuid(walletId, "walletId"));
        body.put("categoryId", ToolInputValidator.optionalUuid(categoryId, "categoryId"));
        if (endDate != null && !endDate.isBlank()) {
            LocalDate parsedEndDate = ToolInputValidator.dateOrToday(endDate, "endDate");
            LocalDate parsedStartDate = LocalDate.parse((String) body.get("startDate"));
            if (parsedEndDate.isBefore(parsedStartDate)) {
                throw new IllegalArgumentException("endDate must be on or after startDate");
            }
            body.put("endDate", parsedEndDate.toString());
        }
        if (intervalValue != null) {
            if (intervalValue < 1 || intervalValue > 365) {
                throw new IllegalArgumentException("intervalValue must be between 1 and 365");
            }
            body.put("intervalValue", intervalValue);
        }

        String preview = confirmationGate.checkOrNull(
                confirmed, "CREATE_RECURRING_TRANSACTION",
                "createRecurringTransaction", body);
        if (preview != null) {
            return preview;
        }

        return bflowApiClient.post("/api/v1/recurring", body, String.class);
    }
}
