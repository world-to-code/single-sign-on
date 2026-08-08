package com.example.sso.security.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Turns a denied authorization into the line an investigator needs, and nothing more.
 *
 * <p>Spring hands the listener the protected object and the check that failed. Both were being discarded, so
 * the audit trail said only that somebody was refused — which is indistinguishable between a misconfigured
 * console and somebody probing every admin route.
 *
 * <p><b>What it deliberately does not do.</b> For method security the protected object is a
 * {@link MethodInvocation}, and in this codebase its arguments are passwords, one-time codes, tokens and
 * assertion XML. So the invocation is described by its METHOD alone — never its arguments, and never
 * {@code toString()}, which reaches them transitively. The same reasoning applies to an unrecognized object:
 * an unknown type is precisely the one whose contents are unknown, so it degrades to its type NAME.
 *
 * <p>A URL denial drops the query string for the same reason (it is where a token ends up) and the whole
 * description is capped: denials fire on every anonymous hit to a protected path, and the path is
 * attacker-controlled.
 */
class DeniedAuthorizationDescriber {

    static final int MAX_TARGET_LENGTH = 200;

    private static final String UNKNOWN_TARGET = "unknown";

    DeniedAuthorization describe(Object protectedObject, AuthorizationResult result) {
        return new DeniedAuthorization(capped(targetOf(protectedObject)), failedCheckOf(result));
    }

    private String targetOf(Object protectedObject) {
        if (protectedObject == null) {
            return UNKNOWN_TARGET;
        }
        if (protectedObject instanceof MethodInvocation invocation) {
            return invocation.getMethod().getDeclaringClass().getSimpleName()
                    + "." + invocation.getMethod().getName();
        }
        if (protectedObject instanceof RequestAuthorizationContext context) {
            return targetOf(context.getRequest());
        }
        if (protectedObject instanceof HttpServletRequest request) {
            // Path only: the query string is where a token rides, and it says nothing about what was refused.
            return request.getMethod() + " " + request.getRequestURI();
        }
        return protectedObject.getClass().getSimpleName();
    }

    /**
     * The expression is the operator's own configuration rather than user input, so recording it is safe and
     * it is the single most useful fact about the refusal — it names the rule to go and read.
     */
    private String failedCheckOf(AuthorizationResult result) {
        return result instanceof ExpressionAuthorizationDecision expression
                ? expression.getExpression().getExpressionString()
                : null;
    }

    private String capped(String target) {
        return target.length() <= MAX_TARGET_LENGTH ? target : target.substring(0, MAX_TARGET_LENGTH);
    }
}
