package com.example.sso.authpolicy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Create/update request for an authentication policy. steps = ordered allowed-factor choices.
 * appliesToLogin (default true) = a login policy (global if unassigned); false = app-only step-up policy.
 * allowEnrollmentAtLogin (default true) = users under this policy may set up a missing factor at login.
 */
public record PolicyRequest(@NotBlank String name, int priority, boolean enabled, Boolean appliesToLogin,
                            Boolean allowEnrollmentAtLogin,
                            @NotEmpty List<List<String>> steps,
                            List<String> assignedUserIds, List<String> assignedRoleIds) {
}
