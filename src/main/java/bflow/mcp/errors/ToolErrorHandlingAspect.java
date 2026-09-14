package bflow.mcp.errors;

import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wraps every {@code @Tool}-annotated method and translates any
 * exception it throws — including one thrown by
 * {@link bflow.mcp.security.RequiresScopeAspect}'s scope check, which
 * runs "inside" this advice, see its lower {@code @Order} — into the
 * structured {@link ToolError} shape (ADR-0010 §8), instead of letting a
 * raw exception (stack trace, SQL state, connection details) reach the
 * AI client.
 *
 * <p>This is the outermost advice on every tool method: it must run
 * first so it can catch exceptions from every other aspect too, hence
 * the low {@code order} value passed to its advisor.</p>
 */
@Aspect
@Configuration
public class ToolErrorHandlingAspect {

    /** Logger — the one place the real exception detail actually goes. */
    private static final Logger LOG =
            LoggerFactory.getLogger(ToolErrorHandlingAspect.class);

    /** Advisor precedence: lower runs first / wraps everyone else. */
    private static final int ADVISOR_ORDER = 0;

    /** Serializes {@link ToolError} to the JSON string tools return. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Wires the interceptor to every method annotated with
     * Spring AI's {@code @Tool}.
     * @return the advisor Spring registers against matching beans.
     */
    @Bean
    public DefaultPointcutAdvisor toolErrorHandlingAdvisor() {
        AnnotationMatchingPointcut pointcut =
                new AnnotationMatchingPointcut(null, Tool.class);
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
                return toJson(translate(invocation.getMethod(), ex));
            }
        };
    }

    private ToolError translate(final Method method, final Exception ex) {
        LOG.warn("Tool '{}' failed: {}", method.getName(), ex.toString());

        if (ex instanceof AccessDeniedException) {
            return new ToolError(
                    "SCOPE_DENIED",
                    "This action requires a permission that wasn't "
                            + "granted for this connection.",
                    false);
        }

        if (ex instanceof IllegalArgumentException) {
            return new ToolError(
                    "VALIDATION_ERROR",
                    ex.getMessage() != null ? ex.getMessage()
                            : "The request was invalid.",
                    false);
        }

        if (ex instanceof HttpStatusCodeException httpEx) {
            return translateHttpStatus(httpEx);
        }

        if (ex instanceof ResourceAccessException) {
            return new ToolError(
                    "UPSTREAM_UNAVAILABLE",
                    "Could not reach BFlow right now. Please try again "
                            + "shortly.",
                    true);
        }

        return new ToolError(
                "INTERNAL_ERROR",
                "Something went wrong processing this request.",
                false);
    }

    private ToolError translateHttpStatus(final HttpStatusCodeException httpEx) {
        String upstreamMessage = extractMessage(httpEx.getResponseBodyAsString());

        return switch (httpEx.getStatusCode().value()) {
            case 400 -> new ToolError(
                    "VALIDATION_ERROR",
                    upstreamMessage != null ? upstreamMessage
                            : "The request was rejected as invalid.",
                    false);
            case 401 -> new ToolError(
                    "UNAUTHENTICATED",
                    "The session is no longer valid. Please "
                            + "reconnect and try again.",
                    false);
            case 403 -> new ToolError(
                    "RESOURCE_ACCESS_DENIED",
                    "The authenticated user does not have permission "
                            + "for this resource.",
                    false);
            case 404 -> new ToolError(
                    "NOT_FOUND",
                    "The requested resource does not exist.",
                    false);
            case 409 -> new ToolError(
                    "CONFLICT",
                    upstreamMessage != null ? upstreamMessage
                            : "The request conflicts with the current "
                                    + "state of the resource.",
                    false);
            default -> httpEx.getStatusCode().is5xxServerError()
                    ? new ToolError(
                            "UPSTREAM_UNAVAILABLE",
                            "BFlow is temporarily unavailable. Please "
                                    + "try again shortly.",
                            true)
                    : new ToolError(
                            "INTERNAL_ERROR",
                            "Something went wrong processing this "
                                    + "request.",
                            false);
        };
    }

    /**
     * Best-effort extraction of BFlow's own {@code ApiResponse.message}
     * field from an error response body, so the AI client sees the same
     * human-readable message a web user would — never the raw body.
     * @param responseBody the raw response body, possibly not JSON.
     * @return the extracted message, or {@code null} if unavailable.
     */
    private String extractMessage(final String responseBody) {
        try {
            var node = objectMapper.readTree(responseBody);
            var message = node.get("message");
            return message != null ? message.asText() : null;
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private String toJson(final ToolError error) {
        try {
            return objectMapper.writeValueAsString(error);
        } catch (Exception neverHappens) {
            return "{\"code\":\"INTERNAL_ERROR\","
                    + "\"message\":\"Something went wrong.\","
                    + "\"retryable\":false}";
        }
    }
}
