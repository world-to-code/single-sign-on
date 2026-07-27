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
}
