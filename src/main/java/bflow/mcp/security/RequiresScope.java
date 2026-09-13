package bflow.mcp.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the OAuth scope a tool method requires, per ADR-0009 §2/§4.
 *
 * <p>This is a necessary, never sufficient, condition: passing this check
 * only means bflow-mcp is allowed to *attempt* the call. Whether the
 * specific wallet/resource is actually accessible to this user is decided
 * downstream, unchanged, by the BFlow API itself.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresScope {

    /**
     * The scope string that must be present in the caller's JWT
     * {@code scope} claim, e.g. {@code "wallets:read"}.
     * @return the required scope.
     */
    String value();
}
