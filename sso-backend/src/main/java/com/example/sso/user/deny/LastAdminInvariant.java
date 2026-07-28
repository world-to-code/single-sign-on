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
     * The snapshot a retraction takes BEFORE it acts: could losing {@code roleIds} cost {@code orgId}'s tier
     * the administrator capability, AND did the tier still have an administrator at that moment.
     *
     * <p>This exists because the admin-layer writes are not the only way a tier loses its last administrator.
     * An ABAC mapping rule confers a role on whoever carries an attribute; deleting that attribute retracts
     * the membership through the re-evaluation, which reaches {@code RoleService.removeMember} — the domain
     * service, below every guard the console path goes through.
     *
     * <p>Both halves are narrowings, and both are needed. Only a retraction that can subtract the
     * administrator capability is capable of breaking the invariant, and the implementation is what knows
     * which roles those are. And a recount answers "does this tier have an administrator NOW", which in a tier
     * that had none to begin with — a freshly onboarded tenant whose invited admin is still disabled is the
     * normal case — refuses every retraction that follows, forever, for a state the retraction did not cause.
     * Reading the pre-state is the only way to tell those apart once the mutation has happened.
     */
    boolean retractionWouldNeedGuarding(Collection<UUID> roleIds, UUID orgId);

    /**
     * Rejects (throws) when the tier no longer has an enabled effective administrator. Call only when
     * {@link #retractionWouldNeedGuarding} said so — otherwise the refusal names a state that was already true.
     *
     * <p>Must run inside the retraction's transaction, so a rejection rolls the retraction back: the person
     * keeps a role they no longer qualify for, which is visible and fixable, rather than the tier losing its
     * last administrator, which is neither.
     */
    void ensureRetractionRetainsAdmin(UUID orgId);
}
