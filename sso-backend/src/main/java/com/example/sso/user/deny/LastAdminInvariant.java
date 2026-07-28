package com.example.sso.user.deny;

import java.util.Collection;
import java.util.UUID;

/**
 * The port the deny write path calls to refuse a deny that would leave a tier with no administrator. Declared
 * here (the admin module implements it, exactly like {@link DenyAuthority}) so authoring a deny that strips the
 * last admin's capability is rejected AT THE WRITE — every deny-create path is covered, not just one endpoint.
 * Lifting a deny only restores authority, so only creation is guarded.
 */
public interface LastAdminInvariant {

    /**
     * Rejects (throws) when the just-written deny of {@code pattern} left ANY of the given tiers (a null element =
     * the platform tier) without an enabled effective administrator. Runs inside the deny's transaction, so the
     * rejection rolls the deny back.
     *
     * <p>The caller passes the tiers the deny actually REACHES, not the one it is stamped with: a platform-wide
     * veto is stamped null yet takes effect inside every tenant. It passes the pattern too, because only a deny
     * that can subtract the administrator capability itself can break the invariant — the implementation decides
     * which capability that is, and skips the recount entirely for any other pattern.
     */
    void ensureDenyRetainsAdmins(String pattern, Collection<UUID> orgIds);

    /**
     * Rejects (throws) when RETRACTING these roles left the tier without an enabled effective administrator.
     *
     * <p>Exists because the admin-layer writes are not the only way a tier loses its last administrator. An
     * ABAC mapping rule confers a role on whoever carries an attribute; deleting that attribute retracts the
     * membership through the async re-evaluation, which reaches {@code RoleService.removeMember} — the domain
     * service, below every guard the console path goes through.
     *
     * <p>The caller passes the roles that stopped applying rather than asking for a bare recount, for the
     * reason {@link #ensureDenyRetainsAdmins} passes its pattern: only a retraction that can subtract the
     * administrator capability itself can break the invariant, and the implementation is what knows which
     * roles those are. A recount on every retraction would instead refuse ordinary ones in a tier that had no
     * administrator to begin with — blaming a pre-existing state on the write that happened to follow it.
     *
     * <p>Must run inside the retraction's transaction, so a rejection rolls the retraction back: the person
     * keeps a role they no longer qualify for, which is visible and fixable, rather than the tier losing its
     * last administrator, which is neither.
     */
    void ensureRetractionRetainsAdmin(Collection<UUID> retractedRoleIds, UUID orgId);
}
