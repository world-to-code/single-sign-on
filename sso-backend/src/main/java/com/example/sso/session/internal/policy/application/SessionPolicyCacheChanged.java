package com.example.sso.session.internal.policy.application;

/**
 * Internal signal that the {@code session_policy} table changed and the in-memory policy cache must be
 * rebuilt. Published inside the mutating transaction and consumed AFTER_COMMIT, so the rebuild reads the
 * committed rows — crucially in the PLATFORM context, since the cache must hold EVERY tenant's policies
 * (a tenant-scoped mutation transaction can only see its own tier under RLS).
 */
public record SessionPolicyCacheChanged() {
}
