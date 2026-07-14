package com.example.sso.session.policy;

/**
 * The session policy in effect for a user on a single request, resolved in ONE pass, per field:
 * <ul>
 *   <li>the specificity {@code winner} (most-specific binding) supplies the preference fields — client binding,
 *       rotate-on-reauth, cookie;</li>
 *   <li>the idle and absolute lifetimes are FLOOR-composed — the smallest of each across every governing policy,
 *       so a narrow lax policy cannot extend a broad org-wide hard-expiry lifetime;</li>
 *   <li>the re-authentication cadence and factors come from the BROADEST-scope governing policy (org-wide &gt;
 *       group/role &gt; user) — the org's re-auth requirement is authoritative and a narrower policy cannot
 *       override it (weaker OR stronger).</li>
 * </ul>
 *
 * <p>SECURITY: read the record's own accessors for these fields — {@link #idleTimeoutMinutes()},
 * {@link #absoluteTimeoutMinutes()}, {@link #reauthIntervalMinutes()}, {@link #reauthFactors()}. Do NOT read the
 * same-named getters off {@link #winner()} — those are the winner's raw values and using them would defeat the
 * floor (lifetimes) or the org-authoritative resolution (re-auth). The {@code winner} is here only for the
 * preference fields, which are neither floored nor scope-resolved.
 */
public record EffectiveSessionPolicy(SessionPolicyDetails winner, int idleTimeoutMinutes, int absoluteTimeoutMinutes,
                                     int reauthIntervalMinutes, String reauthFactors) {
}
