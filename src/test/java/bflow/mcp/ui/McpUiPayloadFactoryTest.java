package bflow.mcp.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

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
        when(messages.get("ui.budgets.progressUnavailable"))
                .thenReturn("Sin datos de progreso");
        when(messages.get("ui.budgets.progressUnavailable.detail"))
                .thenReturn("Muestra spent, remaining y percentage como —.");
        McpUiPayloadFactory factory = new McpUiPayloadFactory(
                new ObjectMapper(), messages);

        String result = factory.budgets("{\"data\":{\"content\":[{"
                + "\"spent\":null,\"remaining\":null,\"percentage\":null}]}}");

        assertThat(result).contains("\"progressDisplay\"")
                .contains("\"spent\":\"—\"")
                .contains("\"remaining\":\"—\"")
                .contains("\"percentage\":\"—\"")
                .contains("Sin datos de progreso")
                .contains("\"spent\":null")
                .doesNotContain("\"spent\":0");
    }
}
