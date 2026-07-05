package com.example.sso.authpolicy.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * How an {@link AuthPolicy} behaves during sign-on and step-up, as an immutable value object: whether it
 * participates in login resolution, whether a governed user may enroll a missing factor during login, and
 * the per-app step-up re-authentication window. These three attributes form one cohesive concept, so the
 * behavior lives here rather than scattered across the entity.
 */
@Embeddable
public record SignOnRules(
        @Column(name = "applies_to_login", nullable = false) boolean appliesToLogin,
        @Column(name = "allow_enrollment_at_login", nullable = false) boolean allowEnrollmentAtLogin,
        @Column(name = "step_up_freshness_minutes", nullable = false) int stepUpFreshnessMinutes) {

    /** The default posture: a login policy that lets users enroll a missing factor, with a 15-minute window. */
    public static SignOnRules defaults() {
        return new SignOnRules(true, true, 15);
    }

    /** Whether this policy governs login (true) or is reserved for per-app step-up only (false). */
    public SignOnRules forLogin(boolean appliesToLogin) {
        return new SignOnRules(appliesToLogin, allowEnrollmentAtLogin, stepUpFreshnessMinutes);
    }

    /** Whether users governed by this policy may enroll a missing factor during login. */
    public SignOnRules allowingEnrollmentAtLogin(boolean allowEnrollmentAtLogin) {
        return new SignOnRules(appliesToLogin, allowEnrollmentAtLogin, stepUpFreshnessMinutes);
    }

    /** The per-app step-up re-authentication window, in minutes. */
    public SignOnRules withStepUpFreshnessMinutes(int minutes) {
        return new SignOnRules(appliesToLogin, allowEnrollmentAtLogin, minutes);
    }
}
