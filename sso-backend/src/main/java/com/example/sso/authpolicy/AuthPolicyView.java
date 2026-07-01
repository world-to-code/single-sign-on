package com.example.sso.authpolicy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only view of an authentication policy — the authpolicy module's public projection, consumed by
 * the login flow (auth), per-app step-up (portal) and the admin console. The backing {@code AuthPolicy}
 * entity stays module-internal.
 */
public interface AuthPolicyView {

    UUID getId();

    String getName();

    int getPriority();

    boolean isEnabled();

    boolean isAppliesToLogin();

    boolean isAllowEnrollmentAtLogin();

    int getStepUpFreshnessMinutes();

    List<? extends AuthPolicyStepView> getSteps();

    Set<UUID> getAssignedUserIds();

    Set<UUID> getAssignedRoleIds();
}
