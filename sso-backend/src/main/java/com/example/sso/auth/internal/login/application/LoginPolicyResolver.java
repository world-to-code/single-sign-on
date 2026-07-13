package com.example.sso.auth.internal.login.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The login (sign-on) authentication policy for a user: the auth policy BOUND to the user portal (the
 * {@code policy_binding} matrix, per user/role/all-subjects), else the seeded Default. Resolved inside the
 * LOGIN org so the tenant's own bindings/policies (RLS-scoped) participate; with no org bound (pre-org steps
 * / step-up) only global/default bindings resolve. Shared by the login-state and factor-step services so
 * every login step agrees on the same winning policy.
 */
@Service
@RequiredArgsConstructor
public class LoginPolicyResolver {

    private final PolicyBindingResolver bindings;
    private final AuthPolicyResolver authPolicies;
    private final OrgContext orgContext;

    public AuthPolicyView resolve(UserAccount user, UUID loginOrgId) {
        // Scope every binding read explicitly — NEVER inherit an ambient platform context (a super-admin's
        // post-login step-up), which RLS would widen to every tenant's bindings. A known login org scopes to
        // that tenant plus globals; a null org (pre-org login / step-up) scopes to GLOBAL-only (org_id IS NULL).
        return orgContext.callInOrg(loginOrgId, () -> resolveInScope(user));
    }

    private AuthPolicyView resolveInScope(UserAccount user) {
        return bindings.resolveAuthPolicy(user, AppType.PORTAL, PortalApps.USER)
                .orElseGet(authPolicies::defaultPolicy);
    }
}
