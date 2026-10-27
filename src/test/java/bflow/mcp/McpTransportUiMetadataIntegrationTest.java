package bflow.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import bflow.mcp.support.AbstractToolIntegrationTest;

/** Verifies MCP Apps metadata through the actual Streamable HTTP transport. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTransportUiMetadataIntegrationTest extends AbstractToolIntegrationTest {

    /** UI resources advertised by tools in this server. */
    private static final Set<String> UI_RESOURCE_URIS = Set.of(
            "ui://bflow/budgets",
            "ui://bflow/confirmation",
            "ui://bflow/transaction-form");

    /** Tools whose implementations attach one of the MCP App resources. */
    private static final Set<String> UI_TOOL_NAMES = Set.of(
            "openTransactionForm", "listBudgets", "createBudget",
            "createTransaction", "updateTransaction", "createRecurringTransaction");

    /** Read tools intentionally exposed without MCP App metadata. */
    private static final Set<String> TEXT_ONLY_TOOL_NAMES = Set.of(
            "listWallets", "listCategories", "listTransactions", "listRecurring");

    /** HTTP client for the actual /mcp endpoint. */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Parses MCP JSON-RPC messages. */
    @Autowired
    private ObjectMapper objectMapper;

    /** Random HTTP port assigned to the embedded server. */
    @LocalServerPort
    private int port;

    /** Session created by initialize and required on later requests. */
    private String sessionId;

    @Test
    void toolsAdvertiseReadableMcpAppResourcesAndKeepOtherToolsUiFree() throws Exception {
        stubTransportJwt();

        JsonNode initialize = post("{\"jsonrpc\":\"2.0\",\"id\":1,"
                + "\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{"
                + "\"name\":\"bflow-mcp-integration-test\","
                + "\"version\":\"1.0\"}}}");
        assertThat(initialize.path("result").path("protocolVersion").asStringOpt())
                .as("MCP initialize protocolVersion: %s", initialize)
                .contains("2025-06-18");
        assertThat(initialize.path("result").path("instructions").asStringOpt())
                .hasValueSatisfying(instructions -> assertThat(instructions)
                        .contains("BFlow is the user's personal finance app")
                        .contains("do not ask the host app for confirmation"));

        post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        JsonNode toolsResponse = post("{\"jsonrpc\":\"2.0\",\"id\":2,"
                + "\"method\":\"tools/list\",\"params\":{}}");
        JsonNode tools = toolsResponse.path("result").path("tools");
        assertThat(tools.isArray()).isTrue();

        Set<String> namesWithUi = new java.util.HashSet<>();
        Set<String> toolNames = new java.util.HashSet<>();
        Set<String> annotatedTools = new java.util.HashSet<>();
        for (JsonNode tool : tools) {
            String toolName = tool.path("name").asStringOpt().orElse(null);
            assertThat(toolName)
                    .as("MCP tool name in tools/list JSON: %s", tool)
                    .isNotNull()
                    .isNotBlank();
            toolNames.add(toolName);
            JsonNode annotations = tool.path("annotations");
            assertThat(annotations.path("title").asStringOpt())
                    .as("tool title annotation: %s", tool)
                    .isPresent();
            assertThat(annotations.path("readOnlyHint").isBoolean())
                    .as("readOnlyHint annotation: %s", tool).isTrue();
            assertThat(annotations.path("readOnlyHint").booleanValue())
                    .as("readOnlyHint annotation: %s", tool)
                    .isEqualTo(Set.of("listBudgets", "listWallets", "listCategories",
                            "listTransactions", "listRecurring", "openTransactionForm")
                            .contains(toolName));
            assertThat(annotations.path("destructiveHint").isBoolean())
                    .as("destructiveHint annotation: %s", tool).isTrue();
            assertThat(annotations.path("destructiveHint").booleanValue())
                    .as("destructiveHint annotation: %s", tool)
                    .isEqualTo("updateTransaction".equals(toolName));
            assertThat(annotations.path("idempotentHint").isBoolean())
                    .as("idempotentHint annotation: %s", tool).isTrue();
            assertThat(annotations.path("idempotentHint").booleanValue())
                    .as("idempotentHint annotation: %s", tool)
                    .isEqualTo("updateTransaction".equals(toolName)
                            || Set.of("listBudgets", "listWallets", "listCategories",
                                    "listTransactions", "listRecurring", "openTransactionForm")
                                    .contains(toolName));
            assertThat(annotations.path("openWorldHint").isBoolean())
                    .as("openWorldHint annotation: %s", tool).isTrue();
            assertThat(annotations.path("openWorldHint").booleanValue())
                    .as("openWorldHint annotation: %s", tool).isFalse();
            annotatedTools.add(toolName);
            JsonNode uiMeta = tool.path("_meta").path("ui");
            if (!uiMeta.isMissingNode() && !uiMeta.isNull()) {
                String resourceUri = uiMeta.path("resourceUri").asStringOpt().orElse(null);
                assertThat(resourceUri)
                        .as("MCP tools/list JSON: %s", toolsResponse)
                        .isNotNull()
                        .isNotBlank()
                        .startsWith("ui://");
                assertThat(UI_RESOURCE_URIS).contains(resourceUri);
                namesWithUi.add(toolName);

                JsonNode readResponse = post("{\"jsonrpc\":\"2.0\",\"id\":3,"
                        + "\"method\":\"resources/read\",\"params\":{"
                        + "\"uri\":\"" + resourceUri + "\"}}");
                JsonNode contents = readResponse.path("result").path("contents");
                assertThat(contents.isArray())
                        .as("MCP resources/read JSON for %s: %s", resourceUri,
                                readResponse)
                        .isTrue();
                JsonNode matchingContent = findContent(contents, resourceUri);
                assertThat(matchingContent.path("mimeType").asStringOpt())
                        .as("MCP resources/read JSON for %s: %s", resourceUri,
                                readResponse)
                        .contains("text/html;profile=mcp-app");
                assertThat(matchingContent.path("text").asStringOpt())
                        .as("MCP resources/read JSON for %s: %s", resourceUri,
                                readResponse)
                        .hasValueSatisfying(value -> assertThat(value).isNotBlank());
            }
        }

        assertThat(namesWithUi)
                .as("UI-backed tool names in MCP tools/list JSON: %s", toolsResponse)
                .isEqualTo(UI_TOOL_NAMES);
        assertThat(toolNames)
                .as("Tool names in MCP tools/list JSON: %s", toolsResponse)
                .containsAll(UI_TOOL_NAMES)
                .containsAll(TEXT_ONLY_TOOL_NAMES);
        assertThat(annotatedTools).hasSize(10);
        for (JsonNode tool : tools) {
            String toolName = tool.path("name").asStringOpt().orElse(null);
            if (toolName != null && !UI_TOOL_NAMES.contains(toolName)) {
                assertThat(tool.path("_meta").path("ui").isMissingNode()
                        || tool.path("_meta").path("ui").isNull())
                        .as("tool without a UI must not expose _meta.ui: %s; tools/list: %s",
                                tool, toolsResponse)
                        .isTrue();
            }
        }
    }

    private JsonNode post(final String jsonRpc) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/mcp"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT,
                        "application/json, text/event-stream");
        if (sessionId != null) {
            requestBuilder.header("Mcp-Session-Id", sessionId);
        }
        HttpRequest request = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(jsonRpc)).build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());
        response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
        assertThat(response.statusCode())
                .as("HTTP response for MCP payload %s: %s", jsonRpc,
                        response.body())
                .isBetween(200, 299);
        return response.body().isBlank()
                ? objectMapper.createObjectNode() : parseJsonRpc(response.body());
    }

    private JsonNode parseJsonRpc(final String responseBody) throws Exception {
        String json = responseBody;
        List<String> dataLines = responseBody.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .toList();
        if (!dataLines.isEmpty()) {
            json = dataLines.get(dataLines.size() - 1);
        }
        return objectMapper.readTree(json);
    }

    private JsonNode findContent(final JsonNode contents, final String resourceUri) {
        for (JsonNode content : contents) {
            if (resourceUri.equals(content.path("uri").asStringOpt().orElse(null))) {
                return content;
            }
        }
        return objectMapper.createObjectNode();
    }

    private void stubTransportJwt() {
        List<String> scopes = List.of(
                "bflow-mcp/transactions.write",
                "bflow-mcp/budgets.read",
                "bflow-mcp/budgets.write",
                "bflow-mcp/transactions.read",
                "bflow-mcp/transactions.write",
                "bflow-mcp/recurring.read",
                "bflow-mcp/recurring.write",
                "bflow-mcp/wallets.read",
                "bflow-mcp/categories.read");
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject("transport-test-user")
                .claim("scope", scopes.stream().collect(Collectors.joining(" ")))
                .build();
        given(jwtDecoder.decode("test-token")).willReturn(jwt);
    }
}
