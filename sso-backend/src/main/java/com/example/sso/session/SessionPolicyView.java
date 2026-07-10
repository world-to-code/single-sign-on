package com.example.sso.session;

import java.util.List;
import java.util.UUID;

/** Admin view of a named session policy. */
public record SessionPolicyView(String id, String name, int priority, boolean enabled,
                                int absoluteTimeoutMinutes, int idleTimeoutMinutes,
                                int reauthIntervalMinutes, String reauthFactors,
                                int sensitiveReauthWindowMinutes, String stepUpFactors, boolean bindClient,
                                int maxConcurrentSessions, boolean rotateOnReauth,
                                String cookieSameSite, int elevationTokenTtlMinutes, String adminAllowedCidrs,
                                List<String> assignedUserIds, List<String> assignedRoleIds,
                                List<IpRuleSpec> ipRules) {

    public static SessionPolicyView of(SessionPolicyDetails p) {
        return new SessionPolicyView(p.getId().toString(), p.getName(), p.getPriority(), p.isEnabled(),
                p.getAbsoluteTimeoutMinutes(), p.getIdleTimeoutMinutes(), p.getReauthIntervalMinutes(),
                p.getReauthFactors(), p.getSensitiveReauthWindowMinutes(), p.getStepUpFactors(),
                p.isBindClient(), p.getMaxConcurrentSessions(), p.isRotateOnReauth(),
                p.getCookieSameSite(), p.getElevationTokenTtlMinutes(), p.getAdminAllowedCidrs(),
                p.getAssignedUserIds().stream().map(UUID::toString).toList(),
                p.getAssignedRoleIds().stream().map(UUID::toString).toList(),
                p.getIpRules());
    }
}
