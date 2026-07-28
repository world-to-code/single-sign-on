package com.example.sso.user.deny;

import java.util.UUID;

/**
 * One deny on a subject together with the tier it is stamped with — {@code null} being a platform-wide veto
 * that applies inside every tenant.
 *
 * <p>The tier is what {@link DenyRow} deliberately omits: the console reads denies from inside the tier it is
 * already acting in, so the answer is implied. A caller that must see denies across tiers cannot rely on that,
 * and needs the stamp to attribute what it found — most concretely, to record the finding where the person who
 * authored the deny can actually read it back.
 */
public record ScopedDenyRow(UUID id, String pattern, UUID orgId) {
}
