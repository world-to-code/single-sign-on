package com.example.sso.authpolicy;

import java.util.List;

/** Admin view of an authentication policy. steps = ordered list of allowed-factor choices. */
public record PolicyView(String id, String name, int priority, boolean enabled, boolean appliesToLogin,
                         boolean allowEnrollmentAtLogin,
                         List<List<String>> steps, List<String> assignedUserIds, List<String> assignedRoleIds,
                         int stepUpFreshnessMinutes) {
}
