package com.example.sso.session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

/**
 * Create/update request for a named session policy. Timeouts in minutes; cookieSameSite is
 * Lax|Strict|None; maxConcurrentSessions = 0 means unlimited. Leave assignments empty for a global
 * policy. The cookieSameSite attribute only takes effect on the Default policy (it is global); the
 * cookie's Secure flag is not a policy field — it is enforced by deployment config in production.
 */
public record SessionPolicyRequest(
        @NotBlank String name,
        int priority,
        boolean enabled,
        @Min(1) @Max(10080) int absoluteTimeoutMinutes,
        @Min(1) @Max(1440) int idleTimeoutMinutes,
        @Min(1) @Max(1440) int reauthIntervalMinutes,
        @NotBlank String reauthFactors,
        boolean bindClient,
        @Min(0) @Max(100) int maxConcurrentSessions,
        boolean rotateOnReauth,
        @Pattern(regexp = "Lax|Strict|None") String cookieSameSite,
        List<String> assignedUserIds,
        List<String> assignedRoleIds) {
}
