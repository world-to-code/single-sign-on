package com.example.sso.authpolicy.internal.api;

import com.example.sso.authpolicy.AuthFactor;
import com.example.sso.authpolicy.AuthPolicySpec;
import com.example.sso.authpolicy.AuthPolicyUpdate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Create/update request for an authentication policy. steps = ordered allowed-factor choices.
 * appliesToLogin (default true) = a login policy (global if unassigned); false = app-only step-up policy.
 * allowEnrollmentAtLogin (default true) = users under this policy may set up a missing factor at login.
 */
public record PolicyRequest(@NotBlank String name, int priority, boolean enabled, Boolean appliesToLogin,
                            Boolean allowEnrollmentAtLogin,
                            @NotEmpty List<List<String>> steps,
                            List<String> assignedUserIds, List<String> assignedRoleIds,
                            @Min(1) @Max(1440) Integer stepUpFreshnessMinutes) {

    /** Step-up freshness (minutes) applied when the request omits it. */
    private static final int DEFAULT_STEP_UP_FRESHNESS_MINUTES = 15;

    /** The create command (parses factor names/ids and defaults the optional flags). */
    public AuthPolicySpec toSpec() {
        return new AuthPolicySpec(name, priority, enabled, loginApplies(), enrollmentAllowed(),
                factorSteps(), uuids(assignedUserIds), uuids(assignedRoleIds), freshness());
    }

    /** The update command (no name). */
    public AuthPolicyUpdate toUpdate() {
        return new AuthPolicyUpdate(priority, enabled, loginApplies(), enrollmentAllowed(),
                factorSteps(), uuids(assignedUserIds), uuids(assignedRoleIds), freshness());
    }

    private boolean loginApplies() {
        return appliesToLogin == null || appliesToLogin;
    }

    private boolean enrollmentAllowed() {
        return allowEnrollmentAtLogin == null || allowEnrollmentAtLogin;
    }

    private List<Set<AuthFactor>> factorSteps() {
        return steps.stream()
                .map(step -> step.stream().map(AuthFactor::valueOf).collect(Collectors.toSet()))
                .toList();
    }

    private int freshness() {
        return stepUpFreshnessMinutes == null ? DEFAULT_STEP_UP_FRESHNESS_MINUTES : stepUpFreshnessMinutes;
    }

    private Set<UUID> uuids(List<String> values) {
        return values == null ? Set.of() : values.stream().map(UUID::fromString).collect(Collectors.toSet());
    }
}
