package bflow.mcp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import tools.jackson.databind.ObjectMapper;

import bflow.mcp.i18n.MessageService;

/** Tests localized text fallback metadata for MCP App payloads. */
class McpUiPayloadFactoryTest {

    @Test
    void budgetFallbackLabelsMissingProgressWithoutConvertingItToZero() {
        MessageService messages = mock(MessageService.class);
        when(messages.get("ui.budgets.title")).thenReturn("Presupuestos");
        when(messages.get("ui.budgets.empty")).thenReturn("Vacío");
        when(messages.get("ui.budgets.loading")).thenReturn("Cargando");
        when(messages.get("ui.budgets.retry")).thenReturn("Reintentar");
        when(messages.get("ui.budgets.progress")).thenReturn("Progreso");
        when(messages.get("ui.budgets.spent")).thenReturn("Gastado");
        when(messages.get("ui.budgets.remaining")).thenReturn("Restante");
        when(messages.get("ui.budgets.limit")).thenReturn("Límite");
        when(messages.get("ui.budgets.period.daily")).thenReturn("Diario");
        when(messages.get("ui.budgets.period.weekly")).thenReturn("Semanal");
        when(messages.get("ui.budgets.period.monthly")).thenReturn("Mensual");
        when(messages.get("ui.budgets.period.yearly")).thenReturn("Anual");
        when(messages.get("ui.budgets.period.unknown")).thenReturn("Otro periodo");
        for (String key : new String[] {"ui.budgets.title", "ui.budgets.empty",
                "ui.budgets.loading", "ui.budgets.retry", "ui.budgets.refresh", "ui.budgets.progress",
                "ui.budgets.spent", "ui.budgets.remaining", "ui.budgets.limit",
                "ui.budgets.progressUnavailable", "ui.budgets.search", "ui.budgets.all",
                "ui.budgets.monthly", "ui.budgets.weekly", "ui.budgets.yearly",
                "ui.budgets.daily", "ui.budgets.wallet", "ui.budgets.category",
                "ui.budgets.healthy", "ui.budgets.warning", "ui.budgets.critical",
                "ui.budgets.statusUnavailable", "ui.budgets.daysLeft",
                "ui.budgets.percentUsed", "ui.budgets.count.one",
                "ui.budgets.count.many"}) {
            when(messages.get(key)).thenReturn(key);
        }
        when(messages.get("ui.budgets.progressUnavailable"))
                .thenReturn("El progreso no está disponible todavía.");
        when(messages.get("ui.budgets.summary.one")).thenReturn("Tienes 1 presupuesto:");
        when(messages.get("ui.budgets.nameFallback")).thenReturn("Presupuesto general");
        McpUiPayloadFactory factory = new McpUiPayloadFactory(
                new ObjectMapper(), messages);

        CallToolResult result = factory.budgets("{\"data\":{\"content\":[{"
                + "\"id\":\"private-id\",\"walletName\":\"Personal\","
                + "\"period\":\"YEARLY\",\"budgetLimit\":10,"
                + "\"spent\":null,\"remaining\":null,\"percentage\":null}]}}");

        String modelText = ((TextContent) result.content().getFirst()).text();
        assertThat(modelText).contains("Personal")
                .contains("El progreso no está disponible todavía.")
                .doesNotContain("private-id", "spent", "percentage", "null", "API");
        assertThat(result.meta()).containsKey("io.bflow/budgets");
        assertThat(result.meta().toString()).contains("progressAvailable")
                .doesNotContain("private-id", "\"spent\":null");
    }
}
