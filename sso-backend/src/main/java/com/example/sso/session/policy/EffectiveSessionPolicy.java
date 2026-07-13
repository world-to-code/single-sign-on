package com.example.sso.session.policy;

/**
 * The session policy in effect for a user on a single request, resolved in ONE pass: the specificity
 * {@code winner} supplies the preference fields (re-auth interval, factors, client binding, cookie), while the
 * idle and absolute lifetimes are FLOOR-composed — the smallest of each across every policy that governs the
 * user, so a narrowly-targeted lax policy cannot extend a broad org-wide policy's hard-expiry lifetime. (Idle
 * and absolute minutes are {@code @Min(1)}, so a smaller value is unambiguously stricter and a plain minimum is
 * the floor — unlike the concurrent-session cap, where 0 = unlimited.)
 *
 * <p>SECURITY: read the record's own {@link #idleTimeoutMinutes()} / {@link #absoluteTimeoutMinutes()} for the
 * lifetimes. Do NOT read {@code winner().getIdleTimeoutMinutes()} / {@code winner().getAbsoluteTimeoutMinutes()}
 * — those are the winner's UN-floored values and using them would defeat the floor. The {@code winner} is here
 * only for the preference fields, which are not floor-type.
 */
public record EffectiveSessionPolicy(SessionPolicyDetails winner, int idleTimeoutMinutes, int absoluteTimeoutMinutes) {
}
