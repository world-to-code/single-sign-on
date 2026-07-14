package com.example.sso.session.policy;

/**
 * The session policy in effect for a user on a single request, resolved per field — a projection of exactly the
 * values the enforcing filters need, so no caller can reach the raw winner policy and read a value the resolution
 * deliberately did NOT pick:
 * <ul>
 *   <li>{@code idleTimeoutMinutes} / {@code absoluteTimeoutMinutes} — FLOOR-composed, the smallest of each across
 *       every governing policy, so a narrow lax policy cannot extend a broad org-wide hard-expiry lifetime;</li>
 *   <li>{@code reauthIntervalMinutes} / {@code reauthFactors} / {@code bindClient} / {@code rotateOnReauth} — from
 *       the specificity WINNER (the most-specific binding: user-direct &gt; group/role &gt; all-subjects, own-org
 *       over global, then priority), so the most-specific policy assigned to the user governs.</li>
 * </ul>
 */
public record EffectiveSessionPolicy(int idleTimeoutMinutes, int absoluteTimeoutMinutes, int reauthIntervalMinutes,
                                     String reauthFactors, boolean bindClient, boolean rotateOnReauth) {
}
