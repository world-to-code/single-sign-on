package com.example.sso.user.internal.application;

import java.util.Set;

/**
 * The per-level permission sets a user's effective-authority resolution needs once negative permissions (deny)
 * are in play — a pure data carrier assembled by {@link EffectiveAuthorityResolver} and consumed by
 * {@link DenyResolver}. Allows and denies are RAW names (may contain {@code <resource>:*} wildcards); the
 * resolver expands them and applies the specificity ladder.
 *
 * @param userAllow    direct permissions granted to the user — USER level (most specific)
 * @param roleAllow    permissions from the user's roles + group-delegated roles + inherited — ROLE/GROUP level
 * @param apexAllow    permissions of the user's APEX roles — a ROLE-subject deny cannot cut these (carve-out)
 * @param roleNames    the {@code ROLE_*} authority names — added verbatim, never subject to deny
 * @param userDeny     USER-level denies
 * @param roleDeny     ROLE-subject denies (carved out by {@code apexAllow})
 * @param groupDeny    GROUP-subject denies (NOT carved out)
 * @param orgDeny      the acting org's own denies — least specific tenant tier
 * @param platformDeny the platform veto ({@code org_id} NULL) — absolute, subtracted after the level decision
 */
record DenyInputs(
        Set<String> userAllow,
        Set<String> roleAllow,
        Set<String> apexAllow,
        Set<String> roleNames,
        Set<String> userDeny,
        Set<String> roleDeny,
        Set<String> groupDeny,
        Set<String> orgDeny,
        Set<String> platformDeny) {
}
