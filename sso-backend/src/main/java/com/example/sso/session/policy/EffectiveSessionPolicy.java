package com.example.sso.session.policy;

/**
 * The session policy in effect for a user on a single request, resolved per field — a projection of exactly the
 * values the enforcing filters need, so no caller can reach the raw winner policy and read a value the resolution
 * deliberately did NOT pick:
 * <ul>
 *   <li>{@code idleTimeoutMinutes} / {@code absoluteTimeoutMinutes} / {@code reauthIntervalMinutes} —
 *       FLOOR-composed, the smallest of each across every governing policy, so a narrow lax policy cannot
 *       extend a broad org-wide hard-expiry lifetime or push its re-authentication further out;</li>
 *   <li>{@code bindClient} / {@code rotateOnReauth} — held when ANY governing policy asks for them, for the
 *       same reason: one policy's laxity must not switch off a hardening another one requires;</li>
 *   <li>{@code reauthFactors} — from the specificity WINNER (the most-specific binding: user-direct &gt;
 *       group/role &gt; all-subjects, own-org over global, then priority). The one field not composed, and
 *       deliberately: it is an ALLOW-list checked with {@code anyMatch}, so the union would loosen it while
 *       the intersection can be empty, leaving a user no acceptable way to re-authenticate at all.</li>
 * </ul>
 */
public record EffectiveSessionPolicy(int idleTimeoutMinutes, int absoluteTimeoutMinutes, int reauthIntervalMinutes,
                                     String reauthFactors, boolean bindClient, boolean rotateOnReauth) {
}
