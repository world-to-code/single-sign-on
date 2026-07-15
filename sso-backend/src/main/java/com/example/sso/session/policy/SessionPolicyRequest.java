package com.example.sso.session.policy;

import com.example.sso.metadata.AttributePredicate;
import com.example.sso.session.networkzone.IpRuleSpec;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Create/update request for a named session policy. Timeouts in minutes; cookieSameSite is Lax|Strict|None;
 * maxConcurrentSessions = 0 means unlimited. Leave assignments empty for a global policy. The cookieSameSite attribute
 * only takes effect on the Default policy (it is global); the cookie's Secure flag is not a policy field — it is
 * enforced by deployment config in production.
 */
public record SessionPolicyRequest(
        @NotBlank String name,
        @Min(0) @Max(1000) int priority,
        boolean enabled,
        @Min(1) @Max(10080) int absoluteTimeoutMinutes,
        @Min(1) @Max(1440) int idleTimeoutMinutes,
        @Min(1) @Max(1440) int reauthIntervalMinutes,
        @NotBlank String reauthFactors,
        @Min(1) @Max(1440) int sensitiveReauthWindowMinutes,
        @NotBlank String stepUpFactors,
        boolean bindClient,
        @Min(0) @Max(100) int maxConcurrentSessions,
        boolean rotateOnReauth,
        @Pattern(regexp = "Lax|Strict|None") String cookieSameSite,
        List<String> assignedUserIds,
        List<String> assignedRoleIds,
        List<@Valid AttributeTargetRequest> assignedAttributes,
        List<@Valid IpRuleSpec> ipRules) {

    /**
     * The create command, resolving the assignment id strings to UUIDs.
     */
    public SessionPolicySpec toSpec() {
        return new SessionPolicySpec(name, priority, enabled, absoluteTimeoutMinutes, idleTimeoutMinutes,
                reauthIntervalMinutes, reauthFactors, sensitiveReauthWindowMinutes, stepUpFactors,
                bindClient, maxConcurrentSessions, rotateOnReauth,
                cookieSameSite,
                uuids(assignedUserIds), uuids(assignedRoleIds), ipRules(), predicates());
    }

    /**
     * The update command (no name), resolving the assignment id strings to UUIDs.
     */
    public SessionPolicyUpdate toUpdate() {
        return new SessionPolicyUpdate(priority, enabled, absoluteTimeoutMinutes, idleTimeoutMinutes,
                reauthIntervalMinutes, reauthFactors, sensitiveReauthWindowMinutes, stepUpFactors,
                bindClient, maxConcurrentSessions, rotateOnReauth,
                cookieSameSite,
                uuids(assignedUserIds), uuids(assignedRoleIds), ipRules(), predicates());
    }

    private Set<UUID> uuids(List<String> values) {
        return values == null ? Set.of() : values.stream()
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    private Set<AttributePredicate> predicates() {
        return assignedAttributes == null ? Set.of() : assignedAttributes.stream()
                .map(AttributeTargetRequest::toPredicate)
                .collect(Collectors.toSet());
    }
}
