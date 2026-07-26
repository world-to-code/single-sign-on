package com.example.sso.user.deny;

import java.util.UUID;

/**
 * The port the deny write path calls to refuse a deny that would leave a tier with no administrator. Declared
 * here (the admin module implements it, exactly like {@link DenyAuthority}) so authoring a deny that strips the
 * last admin's capability is rejected AT THE WRITE — every deny-create path is covered, not just one endpoint.
 * Lifting a deny only restores authority, so only creation is guarded.
 */
public interface LastAdminInvariant {

    /**
     * Rejects (throws) when tier {@code orgId} (null = the platform tier) has no enabled effective administrator
     * left after the just-written deny. Runs inside the deny's transaction, so the rejection rolls the deny back.
     */
    void ensureTierRetainsAdmin(UUID orgId);
}
