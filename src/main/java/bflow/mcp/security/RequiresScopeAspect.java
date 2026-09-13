package bflow.mcp.security;

import java.lang.reflect.Method;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Enforces {@link RequiresScope} on annotated tool methods.
 *
 * <p>This is the entire "scope gate" from ADR-0009 §4 / ADR-0010 §3: it
 * decides whether bflow-mcp is allowed to *attempt* a call. It never
 * decides whether the specific wallet/resource is accessible — that
 * stays downstream, inside the BFlow API, unchanged.</p>
 */
@Aspect
@Configuration
public class RequiresScopeAspect {

    /**
     * Wires the interceptor to every method annotated with
     * {@link RequiresScope}.
     * @return the advisor Spring registers against matching beans.
     */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public DefaultPointcutAdvisor requiresScopeAdvisor() {
        AnnotationMatchingPointcut pointcut =
                new AnnotationMatchingPointcut(null, RequiresScope.class);
        return new DefaultPointcutAdvisor(pointcut, scopeCheckInterceptor());
    }

    private MethodInterceptor scopeCheckInterceptor() {
        return (MethodInvocation invocation) -> {
            Method method = invocation.getMethod();
            RequiresScope required = method.getAnnotation(RequiresScope.class);

            if (required != null) {
                assertHasScope(required.value());
            }

            return invocation.proceed();
        };
    }

    /**
     * Checks for a {@code SCOPE_<value>} authority — the standard
     * GrantedAuthority Spring Security's default JWT converter derives
     * from a space-delimited {@code scope} claim. Relying on this
     * instead of reading the claim by hand means the same converter
     * BFlow's own resource server already trusts is the single source
     * of truth here too.
     *
     * <p>ASSUMPTION TO VERIFY: this expects Cognito to issue a
     * standard OAuth2 {@code scope} claim (space-delimited string). If
     * the granted-scopes-per-agent design (ADR-0009 §5) ends up using a
     * custom claim name or a JSON array instead, this method — and only
     * this method — needs to change; nothing else in this class depends
     * on the claim's shape.</p>
     *
     * @param requiredScope the scope value required by the tool.
     */
    private void assertHasScope(final String requiredScope) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof AbstractAuthenticationToken)
                || authentication.getAuthorities() == null) {
            throw new AccessDeniedException(
                    "No authenticated principal present");
        }

        SimpleGrantedAuthority requiredAuthority =
                new SimpleGrantedAuthority("SCOPE_" + requiredScope);

        boolean hasScope = authentication.getAuthorities()
                .contains(requiredAuthority);

        if (!hasScope) {
            throw new AccessDeniedException(
                    "Missing required scope: " + requiredScope);
        }
    }
}
