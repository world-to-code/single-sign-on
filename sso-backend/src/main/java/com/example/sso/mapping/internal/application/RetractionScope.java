package com.example.sso.mapping.internal.application;

import java.util.Collection;
import java.util.UUID;

/**
 * One transaction's answer to "does this retraction have to keep the tier administrable", captured BEFORE it
 * retracts anything and spent afterwards.
 *
 * <p>This type exists to make the ORDER un-forgettable. The recount can only ever say "there is no
 * administrator now"; it cannot say whether THIS write is why — so the pre-state has to be read first, and the
 * only way to hold a scope is to have read it. {@link #assertTierKeepsAnAdmin} is not reachable without one.
 *
 * <p>What it replaces: a {@code boolean guarding} threaded by hand through four entry points, enforced by a
 * comment on each, where a reorder silently disabled the guard with no compile error — and where an unstubbed
 * Mockito boolean defaulted it to false, so a test that mocked the port had the guard off by default.
 */
final class RetractionScope {

    private final RetractionAdminGuard guard;
    private final UUID tier;
    private final boolean guarding;

    RetractionScope(RetractionAdminGuard guard, UUID tier, boolean guarding) {
        this.guard = guard;
        this.tier = tier;
        this.guarding = guarding;
    }

    /**
     * Rejects when the retraction just performed left the tier without an administrator.
     *
     * <p>Asked ONCE per transaction rather than per user: the recount is a query, and a cohort retraction would
     * otherwise pay it per member for one logical change. Rejecting rolls the whole re-evaluation back, which
     * is the deliberate trade the deny path already makes — somebody keeps a role they no longer qualify for,
     * which is visible and fixable, instead of the tier having none, which only a platform super can undo.
     */
    void assertTierKeepsAnAdmin(Collection<UUID> retractedTargets) {
        if (!guarding || retractedTargets.isEmpty()) {
            return;
        }
        guard.recount(tier, retractedTargets.size());
    }
}
