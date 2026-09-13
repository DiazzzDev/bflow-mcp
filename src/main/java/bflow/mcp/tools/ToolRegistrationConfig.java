package bflow.mcp.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers every tool bean with the MCP server.
 *
 * <p>Add each new tool class to {@code toolObjects(...)} below as issues
 * #9-#17 add them — one line per tool, no other wiring needed.</p>
 */
@Configuration
public class ToolRegistrationConfig {

    /**
     * Exposes all tool beans to the Streamable HTTP MCP server.
     * @param listWalletsTool the wallets read tool.
     * @return the callback provider Spring AI's autoconfiguration picks up.
     */
    @Bean
    public ToolCallbackProvider bflowTools(final ListWalletsTool listWalletsTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(listWalletsTool)
                .build();
    }
}
