package com.example.sso.session.policy;

import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.session.networkzone.IpRuleSpec;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Admin view of a named session policy. Each assigned attribute target is an AND of conditions (a group). */
public record SessionPolicyView(String id, String name, int priority, boolean enabled,
                                int absoluteTimeoutMinutes, int idleTimeoutMinutes,
                                int reauthIntervalMinutes, String reauthFactors,
                                int sensitiveReauthWindowMinutes, String stepUpFactors, boolean bindClient,
                                int maxConcurrentSessions, boolean rotateOnReauth,
                                String cookieSameSite,
                                List<String> assignedUserIds, List<String> assignedRoleIds,
                                List<AttributePredicateGroup> assignedAttributes,
                                List<IpRuleSpec> ipRules) {

    private static final Comparator<AttributePredicateGroup> GROUP_ORDER =
            Comparator.comparing((AttributePredicateGroup g) -> g.conditions().get(0).key())
                    .thenComparing(g -> g.conditions().get(0).value(), Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparingInt(g -> g.conditions().size());

    /** Projects a policy plus its assignment scope (from the policy_binding matrix) to the admin view. */
    public static SessionPolicyView of(SessionPolicyDetails p, SessionAssignment assignment) {
        return new SessionPolicyView(p.getId().toString(), p.getName(), p.getPriority(), p.isEnabled(),
                p.getAbsoluteTimeoutMinutes(), p.getIdleTimeoutMinutes(), p.getReauthIntervalMinutes(),
                p.getReauthFactors(), p.getSensitiveReauthWindowMinutes(), p.getStepUpFactors(),
                p.isBindClient(), p.getMaxConcurrentSessions(), p.isRotateOnReauth(),
                p.getCookieSameSite(),
                assignment.userIds().stream().map(UUID::toString).sorted().toList(),
                assignment.roleIds().stream().map(UUID::toString).sorted().toList(),
                assignment.attributes().stream().sorted(GROUP_ORDER).toList(),
                p.getIpRules());
    }
}
