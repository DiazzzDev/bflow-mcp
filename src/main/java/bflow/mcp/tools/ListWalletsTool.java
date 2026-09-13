package bflow.mcp.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.security.RequiresScope;

/**
 * First real MCP tool, used to validate the end-to-end wiring
 * (AI client → scope gate → BFlow API → resource gate) before issue #8
 * (Wallets) builds out the full read surface.
 *
 * <p>Intentionally minimal: no business logic here, just a proxy. The
 * response shape is whatever {@code GET /api/v1/wallets} already
 * returns via {@code ApiResponse<List<WalletResponse>>} — left as
 * {@code String} for now to avoid coupling this module to BFlow's DTOs
 * before issue #8 decides how MCP tool responses should actually be
 * shaped for a language model.</p>
 */
@Component
public class ListWalletsTool {

    /** Client used to call the real BFlow API. */
    private final BflowApiClient bflowApiClient;

    /**
     * Creates the tool.
     * @param bflowApiClient the BFlow API client to proxy through.
     */
    public ListWalletsTool(final BflowApiClient bflowApiClient) {
        this.bflowApiClient = bflowApiClient;
    }

    /**
     * Lists the authenticated user's own wallets.
     * @return the raw JSON body returned by BFlow's API.
     */
    @Tool(description = "Lists the wallets the current user owns or "
            + "belongs to.")
    @RequiresScope("bflow-mcp/wallets.read")
    public String listWallets() {
        return bflowApiClient.get("/api/v1/wallets", String.class);
    }
}