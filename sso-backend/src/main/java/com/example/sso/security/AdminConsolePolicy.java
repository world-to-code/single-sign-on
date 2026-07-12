package com.example.sso.security;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PolicyBindingResolver;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.policy.SessionPolicyService;
import com.example.sso.user.account.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The session policy that governs the ADMIN CONSOLE for the acting admin: the policy bound to the admin
 * portal for them (via the {@code policy_binding} matrix — the tenant's own selection or the global default
 * it inherits, per role/user/group), else the policy resolved for the acting admin exactly as every other
 * request of theirs resolves it.
 *
 * <p>A console policy is a full session policy: it carries the step-up windows AND the console-specific
 * elevation-token lifetime and IP allowlist. So an admin configures the console the same way they configure
 * any session posture, rather than through a parallel settings axis.
 */
@Component
@RequiredArgsConstructor
public class AdminConsolePolicy {

    private final PolicyBindingResolver bindings;
    private final SessionPolicyService sessionPolicies;
    private final UserService users;

    /** The policy governing the console for {@code username}'s tenant. Never null. */
    public SessionPolicyDetails resolveFor(String username) {
        return users.findByUsername(username)
                .flatMap(user -> bindings.resolveSessionPolicy(user, AppType.PORTAL, PortalApps.ADMIN))
                .orElseGet(() -> sessionPolicies.resolveForUsername(username));
    }
}
