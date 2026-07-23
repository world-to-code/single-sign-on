package com.example.sso.user.internal.application;

import java.util.Set;

/**
 * The raw deny patterns applicable to a user, grouped by tier — read by {@link PermissionDenyReader} and fed
 * into {@link DenyInputs}. {@code platform} is the {@code org_id}-NULL veto, absolute across every tenant.
 */
record DenyRows(Set<String> user, Set<String> role, Set<String> group, Set<String> org, Set<String> platform) {
}
