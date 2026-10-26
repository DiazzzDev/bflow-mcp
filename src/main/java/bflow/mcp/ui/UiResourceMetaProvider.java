package bflow.mcp.ui;

import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.annotation.context.MetaProvider;

/** Declares the intentionally minimal CSP for self-contained MCP Apps. */
public class UiResourceMetaProvider implements MetaProvider {

    @Override
    public Map<String, Object> getMeta() {
        return Map.of("ui", Map.of(
                "csp", Map.of(
                        "connectDomains", List.of(),
                        "resourceDomains", List.of(),
                        "frameDomains", List.of(),
                        "baseUriDomains", List.of()),
                "prefersBorder", true));
    }
}
