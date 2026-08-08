package com.example.sso.admin;

/**
 * Why an administrative action was refused.
 *
 * <p>Module-public because the DENIAL is recorded outside this module: the audit listener that sees an
 * authorization refusal lives in {@code security} and has only the failed SpEL expression to go on. This is
 * the one type that crosses; the decision and the policy stay internal.
 *
 * <p>The self-protection guards all answer "no" for two quite different reasons, and the difference is what
 * an operator needs: refusing because the target is YOU is a UI mistake, refusing because the target is an
 * administrator is the invariant that keeps one admin from dismantling another. A bare false says neither.
 */
public enum AdminRefusal {

    /** Permitted — no refusal. */
    NONE,

    /** The actor aimed at their own account; the guard exists so nobody locks themselves out. */
    SELF_TARGET,

    /** The target holds ROLE_ADMIN, directly or through a group. Admins are not each other's to remove. */
    TARGET_IS_ADMIN
}
