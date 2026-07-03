package com.example.sso.session;

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
                                  List<IpRuleSpec> ipRules) {
}
