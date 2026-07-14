package com.example.sso.session.policy;

/**
 * The session policy in effect for a user on a single request, resolved in ONE pass per field — a projection of
 * exactly the values the enforcing filters need, so no caller can reach the raw winner policy and read a value
 * the resolution deliberately did NOT pick:
 * <ul>
 *   <li>{@code idleTimeoutMinutes} / {@code absoluteTimeoutMinutes} — FLOOR-composed, the smallest of each across
 *       every governing policy, so a narrow lax policy cannot extend a broad org-wide hard-expiry lifetime;</li>
 *   <li>{@code reauthIntervalMinutes} / {@code reauthFactors} — from the BROADEST-scope governing policy (org-wide
 *       &gt; group/role &gt; user), so the org's re-auth requirement is authoritative and a narrower policy cannot
 *       override it;</li>
 *   <li>{@code bindClient} / {@code rotateOnReauth} — the preference fields, taken from the specificity winner
 *       (the most-specific binding).</li>
 * </ul>
 */
public record EffectiveSessionPolicy(int idleTimeoutMinutes, int absoluteTimeoutMinutes, int reauthIntervalMinutes,
                                     String reauthFactors, boolean bindClient, boolean rotateOnReauth) {
}
