package com.example.sso.security;

import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The session policy that governs the ADMIN CONSOLE for the acting tenant: the policy the tenant explicitly
 * selected for its console, or — when it selected none — the policy resolved for the acting admin, exactly
 * as every other request of theirs resolves it.
 *
 * <p>A console policy is a full session policy: it carries the step-up windows AND the console-specific
 * elevation-token lifetime and IP allowlist. So an admin configures the console the same way they configure
 * any session posture, rather than through a parallel settings axis.
 */
@Component
@RequiredArgsConstructor
public class AdminConsolePolicy {

    private final AdminPortalSettingsService portalSettings;
    private final SessionPolicyService sessionPolicies;

    /** The policy governing the console for {@code username}'s tenant. Never null. */
    public SessionPolicyDetails resolveFor(String username) {
        return Optional.ofNullable(selectedPolicyId())
                .flatMap(sessionPolicies::findById)
                // A selected policy that was deleted (or is invisible in this tier) must not lock admins out:
                // fall back to the acting admin's own resolved policy.
                .orElseGet(() -> sessionPolicies.resolveForUsername(username));
    }

    private UUID selectedPolicyId() {
        return portalSettings.get().sessionPolicyId();
    }
}
