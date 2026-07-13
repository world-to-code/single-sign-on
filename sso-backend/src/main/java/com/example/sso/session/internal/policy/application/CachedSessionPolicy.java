package com.example.sso.session.internal.policy.application;

import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.session.internal.policy.domain.SessionPolicy;

import java.util.List;
import java.util.UUID;

/**
 * A {@link SessionPolicy} entity composed with its explicitly-loaded IP rules into the public
 * {@link SessionPolicyDetails} view. The entity carries only its own columns, so the service pairs it with the
 * IP-rule rows it read/wrote — this is what the in-memory cache holds. Which users/roles a policy governs now
 * lives in the {@code policy_binding} matrix, not on the view. Scalars delegate to the embedded {@code SessionRules}.
 */
record CachedSessionPolicy(SessionPolicy policy, List<IpRuleSpec> ipRules) implements SessionPolicyDetails {

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
    public List<IpRuleSpec> getIpRules() {
        return ipRules;
    }
}
