package bflow.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import bflow.mcp.client.BflowApiClient;
import bflow.mcp.i18n.MessageService;
import bflow.mcp.security.RequiresScope;

/** Returns a friendly identity summary for the account behind this connection. */
@Component
public class GetCurrentAccountTool {

    private final BflowApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final MessageService messages;

    public GetCurrentAccountTool(final BflowApiClient apiClient,
            final ObjectMapper objectMapper, final MessageService messages) {
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    @McpTool(title = "Get connected BFlow account",
            description = "Identify the BFlow account currently connected to this session. "
                    + "Use when the user asks which account or email is connected.",
            annotations = @McpTool.McpAnnotations(title = "Get connected BFlow account",
                    readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @RequiresScope("openid")
    public String getCurrentAccount() {
        String response = apiClient.get("/api/v1/users/me", String.class);
        try {
            JsonNode profile = objectMapper.readTree(response).path("data");
            String email = profile.path("email").asStringOpt().orElse("");
            if (email.isBlank()) {
                return messages.get("tool.account.emailUnavailable");
            }
            String name = profile.path("name").asStringOpt().orElse("");
            return name.isBlank()
                    ? messages.get("tool.account.email", email)
                    : messages.get("tool.account.nameAndEmail", name, email);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read the connected account profile", exception);
        }
    }
}
