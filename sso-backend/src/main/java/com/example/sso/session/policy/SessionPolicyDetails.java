package com.example.sso.session.policy;

import com.example.sso.session.networkzone.IpRuleSpec;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only view of a session policy — the session module's public projection, consumed by the login
 * flow (auth), the session-integrity filter (security), the portal and the admin console. The backing
 * {@code SessionPolicy} entity stays module-internal.
 */
public interface SessionPolicyDetails {

    UUID getId();

    String getName();

    int getPriority();

    boolean isEnabled();

    int getAbsoluteTimeoutMinutes();

    int getIdleTimeoutMinutes();

    int getReauthIntervalMinutes();

    String getReauthFactors();

    /** Freshness window (minutes) for sensitive (@RequireStepUp) actions — stricter than the reauth interval. */
    int getSensitiveReauthWindowMinutes();

    /** Allowed factors for a sensitive-action step-up (may be stronger than the general reauth factors). */
    String getStepUpFactors();

    boolean isBindClient();

    int getMaxConcurrentSessions();

    boolean isRotateOnReauth();

    String getCookieSameSite();

    Set<UUID> getAssignedUserIds();

    Set<UUID> getAssignedRoleIds();

    /** IP access rules in evaluation order (priority asc). First rule whose CIDR matches the client IP decides. */
    List<IpRuleSpec> getIpRules();
}
