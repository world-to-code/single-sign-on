package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves the per-user session policy from the {@code policy_binding} matrix — the {@code PORTAL/user} binding
 * (most-specific first), else the seeded Default — for the security filters, the session manager, the step-up
 * interceptor and re-auth (which depend only on the {@link UserSessionPolicy} interface in the session module;
 * this implementation is injected at runtime, so no session&rarr;portal cycle). Mirrors {@link ConsoleSessionPolicyImpl},
 * which resolves the console's {@code PORTAL/admin} policy.
 */
@Component
@RequiredArgsConstructor
class UserSessionPolicyImpl implements UserSessionPolicy {

    private final PolicyBindingResolver bindings;
    private final SessionPolicyService sessionPolicies;
    private final UserService users;
    private final OrgContext orgContext;

    @Override
    public SessionPolicyDetails resolveForUser(UserAccount user) {
        // Resolve scoped to the ACTING org, never the ambient context — an un-drilled platform super-admin must
        // not inherit a tenant's binding (RLS under the platform GUC would expose every tenant's rows). In
        // platform context currentOrg() is empty, so callInOrg(null) collapses RLS to GLOBAL-only.
        return orgContext.callInOrg(orgContext.currentOrg().orElse(null),
                        () -> bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.USER))
                .orElseGet(sessionPolicies::defaultPolicy);
    }

    @Override
    public SessionPolicyDetails resolveForUsername(String username) {
        return users.findByUsername(username)
                .map(this::resolveForUser)
                .orElseGet(sessionPolicies::defaultPolicy);
    }
}
