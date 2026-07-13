package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.policy.ConsoleSessionPolicy;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the admin console's governing session policy — the {@code PORTAL/admin} binding, else the user's own
 * resolved policy — for the step-up interceptor (which depends only on the {@link ConsoleSessionPolicy} interface
 * in the session module; this implementation is injected at runtime, so no session→portal cycle).
 */
@Component
@RequiredArgsConstructor
class ConsoleSessionPolicyImpl implements ConsoleSessionPolicy {

    private final PolicyBindingResolver bindings;
    private final UserSessionPolicy userSessionPolicy;
    private final UserService users;
    private final OrgContext orgContext;

    @Override
    public SessionPolicyDetails resolveForConsole(String username) {
        // Resolve the binding scoped to the ACTING org, never the ambient context — an un-drilled platform
        // super-admin must not inherit a tenant's PORTAL/admin binding (RLS under the platform GUC would expose
        // every tenant's rows, and org-ownership would rank a tenant row above the global pin). In platform
        // context currentOrg() is empty, so callInOrg(null) collapses RLS to GLOBAL-only — the global pin.
        return users.findByUsername(username)
                .flatMap(user -> orgContext.callInOrg(orgContext.currentOrg().orElse(null),
                        () -> bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.ADMIN)))
                .orElseGet(() -> userSessionPolicy.resolveForUsername(username));
    }
}
