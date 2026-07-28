package com.example.sso.user.deny;

import java.util.List;
import java.util.UUID;

/**
 * Authors and lifts negative permissions. Authoring and lifting are both authorized through {@link DenyAuthority}
 * (grant-symmetric, subject-scoped, dominance-gated), stamp/read the author provenance for the lift guard, and
 * TERMINATE the affected subjects' live sessions so an access-tightening deny (or its removal) takes effect
 * without waiting for re-login. A deny is stored per tier ({@link DenySubjectKind}); creating one is idempotent.
 */
public interface DenyService {

    /** Authors the deny (or returns the existing one's id if identical) and revokes the affected sessions. */
    UUID create(DenySpec spec);

    /** Lifts (removes) the deny by id and revokes the affected sessions. A no-op if it no longer exists. */
    void lift(UUID denyId, DenySubjectKind kind);

    /** The USER-level denies on this user (id + pattern) — the ones the console lists against the user and can
     *  lift. Role/group/org denies that also affect the user are NOT here; they are managed on their subject. */
    List<DenyRow> userDenies(UUID userId);

    /** The denies authored directly on this ROLE or GROUP (id + pattern) — for the console to list and lift. */
    List<DenyRow> principalDenies(DenySubjectKind kind, UUID subjectId);

    /**
     * The same denies, from EVERY tier, each carrying the tier it is stamped with.
     *
     * <p>For a caller whose own org context cannot see them all. {@code principalDenies} above answers under
     * the caller's RLS scope, which is right for the console — a tenant admin lists the denies that apply to
     * them — and silently wrong for anything that must not miss one: an unset context (a platform machine
     * client) sees only the org-null rows, and reads that as "no deny rides on this subject". Scoping is
     * therefore this module's decision, made once here, rather than a property of wherever the caller happens
     * to be standing.
     *
     * <p>Read-only and unauthorized by design: it reports what exists, it does not decide anything. A caller
     * that needs a VERDICT wants {@link #mayLiftEveryDenyOn}, which resolves the acting administrator.
     */
    List<ScopedDenyRow> principalDeniesAcrossTiers(DenySubjectKind kind, UUID subjectId);

    /** The org's OWN denies (id + pattern) — org-wide withholdings the console lists and lifts. Excludes the
     *  platform veto (org-null), which is managed at the platform tier, not on a tenant. */
    List<DenyRow> orgDenies(UUID orgId);

    /**
     * Whether the acting administrator could lift EVERY deny authored on this role or group — asked by callers
     * that are about to drop somebody's membership of it rather than lift anything.
     *
     * <p>Dropping a membership removes the denies that ride on it just as surely as lifting them does, so a
     * route that can do that without this check is a way around {@link DenyAuthority#mayLift}. Answered here
     * because the answer needs the author provenance ({@code createdBy}, the stamped apex role) that the store
     * holds and {@link DenyRow} deliberately does not carry.
     *
     * <p>True when the subject carries no denies at all: there is nothing to lift, so nothing to authorize.
     */
    boolean mayLiftEveryDenyOn(DenySubjectKind kind, UUID subjectId);
}
