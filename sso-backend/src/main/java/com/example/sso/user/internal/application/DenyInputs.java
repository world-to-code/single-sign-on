package com.example.sso.user.internal.application;

import java.util.Set;

/**
 * The per-level permission sets a user's effective-authority resolution needs once negative permissions (deny)
 * are in play — a pure data carrier assembled by {@link EffectiveAuthorityResolver} and consumed by
 * {@link DenyResolver}. Allows and denies are RAW names (may contain {@code <resource>:*} wildcards); the
 * resolver expands them and applies the specificity ladder.
 *
 * @param userAllow direct permissions granted to the user — USER level (most specific)
 * @param roleAllow permissions from the user's roles + group-delegated roles + inherited — ROLE/GROUP level
 * @param roleNames the {@code ROLE_*} authority names — added verbatim, never subject to deny
 * @param denies    the per-tier deny patterns ({@link DenyRows}) applicable to the user — carried as one grouped
 *                  value, never re-flattened, so the specificity tiers cannot be positionally mis-ordered
 */
record DenyInputs(Set<String> userAllow, Set<String> roleAllow, Set<String> roleNames, DenyRows denies) {
}
