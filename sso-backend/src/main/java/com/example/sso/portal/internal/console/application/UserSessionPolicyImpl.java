package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.networkzone.IpRules;
import com.example.sso.session.networkzone.NetworkZoneService;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.List;
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
    private final NetworkZoneService networkZones;
    private final UserService users;
    private final OrgContext orgContext;

    @Override
    public SessionPolicyDetails resolveForUser(UserAccount user) {
        // Resolve scoped to the ACTING org, never the ambient context — an un-drilled platform super-admin must
        // not inherit a tenant's binding (RLS under the platform GUC would expose every tenant's rows). In
        // platform context currentOrg() is empty, so callInOrg(null) collapses RLS to GLOBAL-only. The Default
        // fallback runs INSIDE the scope so it lands on the acting org's own Default, not the global one.
        return orgContext.callInOrg(orgContext.currentOrg().orElse(null),
                () -> bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.USER)
                        .orElseGet(sessionPolicies::resolveDefault));
    }

    @Override
    public SessionPolicyDetails resolveForUsername(String username) {
        return users.findByUsername(username)
                .map(this::resolveForUser)
                .orElseGet(sessionPolicies::defaultPolicy);
    }

    @Override
    public boolean isRemoteAllowed(String username, String remoteAddr) {
        // Floor: the request must pass EVERY governing policy's allowlist (each evaluated on its own first-match
        // list — the lists are NOT flattened, which would let one policy's ALLOW shadow another's BLOCK).
        return governing(username).stream()
                .allMatch(policy -> IpRules.isAllowed(policy.getIpRules(), networkZones::cidrsForZone, remoteAddr));
    }

    @Override
    public int maxConcurrentSessionsFor(String username) {
        // Floor: the smallest non-zero cap across governing policies; 0 = unlimited, so it is excluded from the
        // minimum and only wins when EVERY governing policy is unlimited.
        return governing(username).stream()
                .mapToInt(SessionPolicyDetails::getMaxConcurrentSessions)
                .filter(max -> max > 0)
                .min()
                .orElse(0);
    }

    /** Every enabled session policy governing the user (all matching bindings), else the single acting Default. */
    private List<SessionPolicyDetails> governing(String username) {
        return users.findByUsername(username)
                .map(user -> orgContext.callInOrg(orgContext.currentOrg().orElse(null), () -> {
                    List<SessionPolicyDetails> matching =
                            bindings.resolveSessionPolicies(user, AppType.PORTAL, PortalApps.USER);
                    return matching.isEmpty() ? List.of(sessionPolicies.resolveDefault()) : matching;
                }))
                .orElseGet(() -> List.of(sessionPolicies.defaultPolicy()));
    }
}
