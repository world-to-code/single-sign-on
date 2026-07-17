package com.example.sso.session.policy;

import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.session.networkzone.IpRuleSpec;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object for {@link SessionPolicyService#update(UUID, SessionPolicyUpdate)}: the
 * editable attributes of an existing session policy (the name is fixed and stays a keyed lookup).
 */
public record SessionPolicyUpdate(int priority, boolean enabled, int absoluteTimeoutMinutes,
                                  int idleTimeoutMinutes, int reauthIntervalMinutes, String reauthFactors,
                                  int sensitiveReauthWindowMinutes, String stepUpFactors,
                                  boolean bindClient, int maxConcurrentSessions, boolean rotateOnReauth,
                                  String cookieSameSite, Set<UUID> userIds, Set<UUID> roleIds,
                                  List<IpRuleSpec> ipRules, Set<AttributePredicateGroup> attributePredicates) {

    /** Update with user/role assignments only (no metadata predicate targets). */
    public SessionPolicyUpdate(int priority, boolean enabled, int absoluteTimeoutMinutes, int idleTimeoutMinutes,
                               int reauthIntervalMinutes, String reauthFactors, int sensitiveReauthWindowMinutes,
                               String stepUpFactors, boolean bindClient, int maxConcurrentSessions,
                               boolean rotateOnReauth, String cookieSameSite, Set<UUID> userIds, Set<UUID> roleIds,
                               List<IpRuleSpec> ipRules) {
        this(priority, enabled, absoluteTimeoutMinutes, idleTimeoutMinutes, reauthIntervalMinutes, reauthFactors,
                sensitiveReauthWindowMinutes, stepUpFactors, bindClient, maxConcurrentSessions, rotateOnReauth,
                cookieSameSite, userIds, roleIds, ipRules, Set.of());
    }
}
