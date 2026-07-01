package com.example.sso.session;

import java.util.Set;
import java.util.UUID;

/**
 * Immutable parameter object for {@link SessionPolicyService#create(SessionPolicySpec)}: the full set
 * of attributes for a new session policy, including its name and assignment sets.
 */
public record SessionPolicySpec(String name, int priority, boolean enabled, int absoluteTimeoutMinutes,
                                int idleTimeoutMinutes, int reauthIntervalMinutes, String reauthFactors,
                                boolean bindClient, int maxConcurrentSessions, boolean rotateOnReauth,
                                String cookieSameSite, Set<UUID> userIds, Set<UUID> roleIds) {
}
