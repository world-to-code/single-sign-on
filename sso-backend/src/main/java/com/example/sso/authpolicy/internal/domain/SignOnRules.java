package com.example.sso.authpolicy.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * How an {@link AuthPolicy} behaves during sign-on and step-up, as an immutable value object: whether a
 * governed user may enroll a missing factor during login, and the per-app step-up re-authentication window.
 * These attributes form one cohesive concept, so the behavior lives here rather than scattered across the
 * entity. Login participation itself now lives in the {@code policy_binding} matrix, not on the policy.
 */
@Embeddable
public record SignOnRules(
        @Column(name = "allow_enrollment_at_login", nullable = false) boolean allowEnrollmentAtLogin,
        @Column(name = "step_up_freshness_minutes", nullable = false) int stepUpFreshnessMinutes) {

    /** The default posture: a policy that lets users enroll a missing factor, with a 15-minute step-up window. */
    public static SignOnRules defaults() {
        return new SignOnRules(true, 15);
    }

    /** Whether users governed by this policy may enroll a missing factor during login. */
    public SignOnRules allowingEnrollmentAtLogin(boolean allowEnrollmentAtLogin) {
        return new SignOnRules(allowEnrollmentAtLogin, stepUpFreshnessMinutes);
    }

    /** The per-app step-up re-authentication window, in minutes. */
    public SignOnRules withStepUpFreshnessMinutes(int minutes) {
        return new SignOnRules(allowEnrollmentAtLogin, minutes);
    }
}
