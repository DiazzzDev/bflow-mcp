package bflow.mcp.observability;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Logs every {@code @Tool} invocation — name, the authenticated user
 * (subject only, never the raw token), duration, and outcome — whether
 * it succeeds, gets caught by {@link bflow.mcp.errors.ToolErrorHandlingAspect},
 * or is denied by {@link bflow.mcp.security.RequiresScopeAspect}.
 *
 * <p>This is operational visibility for bflow-mcp itself, not the audit
 * trail — that one is a durable, queryable record
 * of state-changing operations, owned by the BFlow API side (blocked on
 * the core refactor). This is a log line, useful for debugging and
 * spotting patterns (a scope repeatedly denied, a tool repeatedly
 * failing), not for compliance or dispute resolution.</p>
 *
 * <p>Runs as the true outermost advice — order {@code -1}, one below
 * {@link bflow.mcp.errors.ToolErrorHandlingAspect}'s {@code 0} — so its
 * timing covers the scope check and error translation too, and it logs
 * unconditionally regardless of what either of those do.</p>
 */
@Aspect
@Configuration
public class ToolObservabilityAspect {

    /** Structured-ish log line, one per tool call. */
    private static final Logger LOG =
            LoggerFactory.getLogger(ToolObservabilityAspect.class);

    /** Advisor precedence: lower runs first / wraps everyone else. */
    private static final int ADVISOR_ORDER = -1;

    /**
     * Wires the interceptor to every method annotated with Spring AI's
     * {@code @Tool}.
     * @return the advisor Spring registers against matching beans.
     */
    @Bean
    public DefaultPointcutAdvisor toolObservabilityAdvisor() {
        AnnotationMatchingPointcut pointcut =
                new AnnotationMatchingPointcut(null, Tool.class);
        DefaultPointcutAdvisor advisor =
                new DefaultPointcutAdvisor(pointcut, observabilityInterceptor());
        advisor.setOrder(ADVISOR_ORDER);
        return advisor;
    }

    private MethodInterceptor observabilityInterceptor() {
        return (MethodInvocation invocation) -> {
            String toolName = invocation.getMethod().getName();
            String userId = currentUserIdOrUnknown();
            long startedAt = System.currentTimeMillis();

            MDC.put("tool", toolName);
            MDC.put("userId", userId);

            try {
                Object result = invocation.proceed();
                logOutcome(toolName, userId, startedAt, outcomeOf(result));
                return result;
            } catch (Exception ex) {
                // ToolErrorHandlingAspect runs INSIDE this advice (order
                // 0 > -1), so a scope denial or a real exception is
                // already translated to a JSON string by the time it
                // gets here in the normal case — this catch only fires
                // for something that bypasses that translation entirely
                // (a bug in the error handler itself).
                logOutcome(toolName, userId, startedAt,
                        "UNHANDLED_EXCEPTION:" + ex.getClass().getSimpleName());
                throw ex;
            } finally {
                MDC.remove("tool");
                MDC.remove("userId");
            }
        };
    }

    private String outcomeOf(final Object result) {
        if (!(result instanceof String text)) {
            return "SUCCESS";
        }
        if (text.contains("\"status\":\"CONFIRMATION_REQUIRED\"")) {
            return "CONFIRMATION_REQUIRED";
        }
        if (text.contains("\"code\":\"")) {
            // ToolErrorHandlingAspect's ToolError shape — extract the
            // code without a full JSON parse, this is a log line, not
            // a decision point.
            int codeStart = text.indexOf("\"code\":\"") + 8;
            int codeEnd = text.indexOf('"', codeStart);
            return "ERROR:" + (codeEnd > codeStart
                    ? text.substring(codeStart, codeEnd) : "UNKNOWN");
        }
        return "SUCCESS";
    }

    private void logOutcome(final String toolName, final String userId,
            final long startedAt, final String outcome) {
        long durationMs = System.currentTimeMillis() - startedAt;
        LOG.info("tool={} userId={} durationMs={} outcome={}",
                toolName, userId, durationMs, outcome);
    }

    private String currentUserIdOrUnknown() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }

        return authentication instanceof AbstractAuthenticationToken
                ? "unknown-authenticated"
                : "unauthenticated";
    }
}