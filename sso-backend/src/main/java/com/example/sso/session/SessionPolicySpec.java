package com.example.sso.session;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object for {@link SessionPolicyService#create(SessionPolicySpec)}: the full set
 * of attributes for a new session policy, including its name, assignment sets and ordered IP rules.
 */
public record SessionPolicySpec(String name, int priority, boolean enabled, int absoluteTimeoutMinutes,
                                int idleTimeoutMinutes, int reauthIntervalMinutes, String reauthFactors,
                                int sensitiveReauthWindowMinutes, String stepUpFactors,
                                boolean bindClient, int maxConcurrentSessions, boolean rotateOnReauth,
                                String cookieSameSite, int elevationTokenTtlMinutes, String adminAllowedCidrs,
                                Set<UUID> userIds, Set<UUID> roleIds, List<IpRuleSpec> ipRules) {
}
