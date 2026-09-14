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
 * Creates a new budget for the authenticated user.
 *
 * <p>Kept deliberately simple for this first version: only the
 * {@code WALLET} scope is exposed (a budget covering all spending in one
 * wallet). {@code WALLET_CATEGORY} and {@code CATEGORY_GLOBAL} scopes
 * need a category id and, for the global scope, no wallet id at all —
 * that's a meaningfully different shape of request for an AI agent to
 * get right, and isn't needed for the common case ("set a $200 budget
 * for my personal wallet this month"). Left for a follow-up once this
 * simpler version is proven.</p>
 *
 * <p>Same known gap as {@code CreateTransactionTool} would have had
 * (ADR-0010 §8): a bad {@code walletId}, a currency mismatch with the
 * wallet, or any other validation failure from the BFlow API currently
 * surfaces as a raw exception, not a structured, agent-safe error.
 * Owned by issue #20.</p>
 */
@Component
public class CreateBudgetTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /** Propose-then-confirm gate (ADR-0010 §18). */
    private final ConfirmationGate confirmationGate;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     * @param confirmationGate the confirmation gate to check before executing.
     */
    public CreateBudgetTool(final BflowApiClient bflowApiClient,
            final ConfirmationGate confirmationGate) {
        this.bflowApiClient = bflowApiClient;
        this.confirmationGate = confirmationGate;
    }

    /**
     * Creates a new whole-wallet budget.
     *
     * @param walletId the wallet this budget applies to.
     * @param amount the budget amount, must be positive.
     * @param currency the currency this amount is denominated in — must
     *      match the wallet's own currency (e.g. {@code USD}).
     * @param period how often the budget resets: {@code DAILY},
     *      {@code WEEKLY}, or {@code MONTHLY}.
     * @param startDate the date the budget period starts, YYYY-MM-DD.
     * @param thresholdWarning optional warning alert threshold, 1-99
     *      (percent of budget spent). Defaults to 70 if omitted.
     * @param thresholdCritical optional critical alert threshold, 1-99
     *      (percent of budget spent). Defaults to 90 if omitted.
     * @param confirmed pass {@code true} only after the user has seen and
     *      approved the preview this tool returns when omitted/false.
     * @return the raw JSON body returned by BFlow's API, or a
     *      {@code CONFIRMATION_REQUIRED} preview if not yet confirmed.
     */
    @Tool(description = "Creates a new budget covering all spending in "
            + "one wallet (e.g. 'set a $200 monthly budget for my "
            + "personal wallet'). Does not support per-category budgets "
            + "yet. Requires confirmed=true to actually execute — call "
            + "once to get a preview, show it to the user, then call "
            + "again with confirmed=true.")
    @RequiresScope("bflow-mcp/budgets.write")
    public String createBudget(
            @ToolParam(description = "Wallet this budget applies to")
                final String walletId,
            @ToolParam(description = "Budget amount, must be positive")
                final double amount,
            @ToolParam(description = "Currency, must match the wallet's "
                    + "own currency, e.g. USD, EUR, MXN")
                final String currency,
            @ToolParam(description = "DAILY, WEEKLY, or MONTHLY")
                final String period,
            @ToolParam(description = "Start date in YYYY-MM-DD format")
                final String startDate,
            @ToolParam(description = "Warning alert threshold, 1-99 "
                    + "percent. Defaults to 70 if omitted", required = false)
                final Integer thresholdWarning,
            @ToolParam(description = "Critical alert threshold, 1-99 "
                    + "percent. Defaults to 90 if omitted", required = false)
                final Integer thresholdCritical,
            @ToolParam(description = "Set to true only after the user "
                    + "has confirmed the preview. Omit or false to get "
                    + "a preview without creating anything", required = false)
                final Boolean confirmed) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("walletId", walletId);
        body.put("amount", amount);
        body.put("currency", currency.toUpperCase());
        body.put("period", period.toUpperCase());
        body.put("startDate", LocalDate.parse(startDate).toString());
        body.put("scope", "WALLET");
        if (thresholdWarning != null) {
            body.put("thresholdWarning", thresholdWarning);
        }
        if (thresholdCritical != null) {
            body.put("thresholdCritical", thresholdCritical);
        }

        String preview = confirmationGate.checkOrNull(
                confirmed, "CREATE_BUDGET", body);
        if (preview != null) {
            return preview;
        }

        return bflowApiClient.post("/api/v1/budgets", body, String.class);
    }
}