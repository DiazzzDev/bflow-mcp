package bflow.mcp.errors;

import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.ai.mcp.annotation.McpTool;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bflow.mcp.i18n.MessageService;

/**
 * Wraps every {@code @McpTool}-annotated method and translates any
 * exception it throws — including one thrown by
 * {@link bflow.mcp.security.RequiresScopeAspect}'s scope check, which
 * runs "inside" this advice, see its lower {@code @Order} — into a short,
 * localized message instead of letting raw exceptions or upstream
 * validation details reach the AI client.
 *
 * <p>This is the outermost advice on every tool method: it must run
 * first so it can catch exceptions from every other aspect too, hence
 * the low {@code order} value passed to its advisor.</p>
 */
@Aspect
@Configuration
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class ToolErrorHandlingAspect {

    /** Logger — the one place the real exception detail actually goes. */
    private static final Logger LOG =
            LoggerFactory.getLogger(ToolErrorHandlingAspect.class);

    /** Advisor precedence: lower runs first / wraps everyone else. */
    private static final int ADVISOR_ORDER = 0;

    /** Resolves visible errors for the active request locale. */
    private final MessageService messageService;

    /**
     * Creates the error translation advice.
     * @param messageService request-localized message facade.
     */
    public ToolErrorHandlingAspect(final MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Wires the interceptor to every method annotated with
     * Spring AI's {@code @McpTool}.
     * @return the advisor Spring registers against matching beans.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public DefaultPointcutAdvisor toolErrorHandlingAdvisor() {
        AnnotationMatchingPointcut pointcut =
                new AnnotationMatchingPointcut(null, McpTool.class);
        DefaultPointcutAdvisor advisor =
                new DefaultPointcutAdvisor(pointcut, errorHandlingInterceptor());
        advisor.setOrder(ADVISOR_ORDER);
        return advisor;
    }

    private MethodInterceptor errorHandlingInterceptor() {
        return (MethodInvocation invocation) -> {
            try {
                return invocation.proceed();
            } catch (Exception ex) {
                ToolError error = translate(invocation.getMethod(), ex);
                if (CallToolResult.class.isAssignableFrom(
                        invocation.getMethod().getReturnType())) {
                    return CallToolResult.builder().addTextContent(error.message())
                            .isError(true).build();
                }
                return error.message();
            }
        };
    }

    private ToolError translate(final Method method, final Exception ex) {
        LOG.warn("Tool '{}' failed: {}", method.getName(), ex.toString());

        if (ex instanceof AccessDeniedException) {
            return new ToolError(
                    "SCOPE_DENIED",
                    messageService.get("tool.error.scopeDenied"),
                    false);
        }

        if (ex instanceof IllegalArgumentException) {
            return new ToolError(
                    "VALIDATION_ERROR",
                    messageService.get("tool.error.validation"),
                    false);
        }

        if (ex instanceof HttpStatusCodeException httpEx) {
            return translateHttpStatus(httpEx);
        }

        if (ex instanceof ResourceAccessException) {
            return new ToolError(
                    "UPSTREAM_UNAVAILABLE",
                    messageService.get("tool.error.upstreamUnavailable"),
                    true);
        }

        return new ToolError(
                "INTERNAL_ERROR",
                messageService.get("tool.error.internal"),
                false);
    }

    private ToolError translateHttpStatus(final HttpStatusCodeException httpEx) {
        return switch (httpEx.getStatusCode().value()) {
            case 400 -> new ToolError(
                    "VALIDATION_ERROR",
                    messageService.get("tool.error.validation"),
                    false);
            case 401 -> new ToolError(
                    "UNAUTHENTICATED",
                    messageService.get("tool.error.unauthenticated"),
                    false);
            case 403 -> new ToolError(
                    "RESOURCE_ACCESS_DENIED",
                    messageService.get("tool.error.resourceAccessDenied"),
                    false);
            case 404 -> new ToolError(
                    "NOT_FOUND",
                    messageService.get("tool.error.notFound"),
                    false);
            case 409 -> new ToolError(
                    "CONFLICT",
                    messageService.get("tool.error.conflict"),
                    false);
            default -> httpEx.getStatusCode().is5xxServerError()
                    ? new ToolError(
                            "UPSTREAM_UNAVAILABLE",
                            messageService.get("tool.error.upstreamUnavailable"),
                            true)
                    : new ToolError(
                            "INTERNAL_ERROR",
                            messageService.get("tool.error.internal"),
                            false);
        };
    }

}
