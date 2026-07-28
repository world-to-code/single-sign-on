package com.example.sso.mapping;

import java.util.Collection;
import java.util.UUID;

/**
 * The port the mapping module calls so an ABAC retraction cannot take a tier's last administrator.
 *
 * <p>Declared here, and implemented by the admin module, because the CALLER owns the concern: the admin-layer
 * writes are not the only way a tier loses its last administrator. A mapping rule confers a role on whoever
 * carries an attribute; deleting that attribute retracts the membership through {@code RoleService.removeMember}
 * — the domain service, below every guard the console path goes through.
 *
 * <p>It lived on the {@code user.deny} port for a while, which was where a last-admin port already happened to
 * exist. Nothing in {@code user} called these two methods, so the deny slice was exporting a contract between
 * mapping and admin, and every consumer of a deny had to see it.
 *
 * <p>Two calls, and the ORDER is the point — see {@code RetractionAdminGuard}, which is the only thing that
 * should call them, precisely so the order cannot be got wrong.
 */
public interface LastAdminRetractionGuard {

    /**
     * The snapshot taken BEFORE a retraction: could losing {@code roleIds} cost {@code orgId}'s tier the
     * administrator capability, AND did the tier still have an administrator at that moment.
     *
     * <p>Both halves are narrowings and both are needed. Only a retraction that can subtract the administrator
     * capability is capable of breaking the invariant, and the implementation is what knows which roles those
     * are. And a recount answers "does this tier have an administrator NOW", which in a tier that had none to
     * begin with — a freshly onboarded tenant whose invited admin is still disabled is the normal case —
     * refuses every retraction that follows, forever, for a state the retraction did not cause. Reading the
     * pre-state is the only way to tell those apart once the mutation has happened.
     */
    boolean retractionWouldNeedGuarding(Collection<UUID> roleIds, UUID orgId);

    /**
     * Rejects (throws) when the tier no longer has an enabled effective administrator. Call only when
     * {@link #retractionWouldNeedGuarding} said so — otherwise the refusal names a state already true.
     *
     * <p>Must run inside the retraction's transaction, so a rejection rolls the retraction back: the person
     * keeps a role they no longer qualify for, which is visible and fixable, rather than the tier losing its
     * last administrator, which is neither.
     */
    void ensureRetractionRetainsAdmin(UUID orgId);
}
