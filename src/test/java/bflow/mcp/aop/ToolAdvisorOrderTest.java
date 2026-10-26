package bflow.mcp.aop;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.context.support.StaticMessageSource;

import bflow.mcp.errors.ToolErrorHandlingAspect;
import bflow.mcp.i18n.MessageService;
import bflow.mcp.observability.ToolObservabilityAspect;
import bflow.mcp.security.RequiresScopeAspect;
import bflow.mcp.tools.ListWalletsTool;
import tools.jackson.databind.ObjectMapper;

/** Guards the required observability, errors, scope aspect nesting. */
class ToolAdvisorOrderTest {

    @Test
    void appliesAllAdvisorsToMcpToolsInTheRequiredOrder() throws Exception {
        DefaultPointcutAdvisor observability = new ToolObservabilityAspect()
                .toolObservabilityAdvisor();
        DefaultPointcutAdvisor errors = new ToolErrorHandlingAspect(
                new ObjectMapper(), messageService()).toolErrorHandlingAdvisor();
        DefaultPointcutAdvisor scope = new RequiresScopeAspect()
                .requiresScopeAdvisor();
        Method tool = ListWalletsTool.class.getMethod("listWallets");

        assertThat(observability.getOrder()).isEqualTo(-1);
        assertThat(errors.getOrder()).isEqualTo(0);
        assertThat(scope.getOrder()).isEqualTo(1);
        assertThat(observability.getPointcut().getMethodMatcher()
                .matches(tool, ListWalletsTool.class)).isTrue();
        assertThat(errors.getPointcut().getMethodMatcher()
                .matches(tool, ListWalletsTool.class)).isTrue();
    }

    private MessageService messageService() {
        StaticMessageSource source = new StaticMessageSource();
        source.addMessage("tool.error.internal", Locale.ENGLISH, "error");
        return new MessageService(source);
    }
}
