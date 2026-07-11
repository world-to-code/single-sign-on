package com.example.sso.session.internal.policy.application;

import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.internal.policy.domain.SessionPolicy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link SessionPolicy} entity composed with its explicitly-loaded assignment sets and IP rules into the
 * public {@link SessionPolicyDetails} view. The entity carries only its own columns now, so the service pairs
 * it with the child rows it read/wrote — this is what the in-memory cache holds and what resolution and the
 * admin projections consume. Scalars delegate to the entity's embedded {@code SessionRules}.
 */
record CachedSessionPolicy(SessionPolicy policy, Set<UUID> assignedUserIds,
                           Set<UUID> assignedRoleIds, List<IpRuleSpec> ipRules) implements SessionPolicyDetails {

    @Override
    public UUID getId() {
        return policy.getId();
    }

    @Override
    public String getName() {
        return policy.getName();
    }

    @Override
    public int getPriority() {
        return policy.getPriority();
    }

    @Override
    public boolean isEnabled() {
        return policy.isEnabled();
    }

    @Override
    public int getAbsoluteTimeoutMinutes() {
        return policy.getRules().absoluteTimeoutMinutes();
    }

    @Override
    public int getIdleTimeoutMinutes() {
        return policy.getRules().idleTimeoutMinutes();
    }

    @Override
    public int getReauthIntervalMinutes() {
        return policy.getRules().reauthIntervalMinutes();
    }

    @Override
    public String getReauthFactors() {
        return policy.getRules().reauthFactors();
    }

    @Override
    public int getSensitiveReauthWindowMinutes() {
        return policy.getRules().sensitiveReauthWindowMinutes();
    }

    @Override
    public String getStepUpFactors() {
        return policy.getRules().stepUpFactors();
    }

    @Override
    public boolean isBindClient() {
        return policy.getRules().bindClient();
    }

    @Override
    public int getMaxConcurrentSessions() {
        return policy.getRules().maxConcurrentSessions();
    }

    @Override
    public boolean isRotateOnReauth() {
        return policy.getRules().rotateOnReauth();
    }

    @Override
    public String getCookieSameSite() {
        return policy.getRules().cookieSameSite();
    }

    @Override
    public int getElevationTokenTtlMinutes() {
        return policy.getRules().elevationTokenTtlMinutes();
    }

    @Override
    public String getAdminAllowedCidrs() {
        return policy.getRules().adminAllowedCidrs();
    }

    @Override
    public Set<UUID> getAssignedUserIds() {
        return assignedUserIds;
    }

    @Override
    public Set<UUID> getAssignedRoleIds() {
        return assignedRoleIds;
    }

    @Override
    public List<IpRuleSpec> getIpRules() {
        return ipRules;
    }
}
