package com.example.sso.session.policy;

/**
 * The most-restrictive session lifetimes across every policy that governs a user — a floor, not the single
 * specificity winner. Both timeouts are the SMALLEST value among all matching policies, so a narrowly-targeted
 * lax policy cannot extend a broad org-wide policy's lifetime. (Unlike the concurrent-session cap, idle and
 * absolute timeouts are always positive — {@code @Min(1)} — so a smaller value is unambiguously stricter and a
 * plain minimum is the floor.)
 */
public record SessionLifetimeFloor(int idleTimeoutMinutes, int absoluteTimeoutMinutes) {
}
