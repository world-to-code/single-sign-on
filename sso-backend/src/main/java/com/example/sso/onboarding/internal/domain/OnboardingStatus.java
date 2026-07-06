package com.example.sso.onboarding.internal.domain;

/** Lifecycle of a tenant-onboarding job, polled by the UI while the workspace is being set up. */
public enum OnboardingStatus {
    /** Accepted; provisioning has not started yet. */
    PENDING,
    /** The async worker is creating the org, admin, roles, membership and the invitation. */
    PROVISIONING,
    /** Provisioned and the admin's invitation email has been sent. */
    INVITED,
    /** Provisioned successfully, but sending the invitation email failed — the admin exists and must be
     *  re-invited (distinct from FAILED, which means nothing was created and the slug is free to retry). */
    INVITE_FAILED,
    /** Provisioning failed and rolled back — no org/admin was created; safe to retry with the same slug. */
    FAILED
}
