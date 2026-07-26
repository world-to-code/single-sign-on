package com.example.sso.admin.internal.shared.application;

import com.example.sso.user.deny.LastAdminInvariant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Admin's implementation of the {@code user} module's {@link LastAdminInvariant} port: the last-admin invariant
 * is an admin-tier concern ({@link LastAdminGuard}), but the deny WRITE lives in the user module, so it reaches
 * the guard through this port — keeping the guard in admin (where its sibling triggers are) with no user→admin
 * cycle, exactly like {@code DenyAuthorityAdapter}.
 */
@Component
@RequiredArgsConstructor
class LastAdminInvariantAdapter implements LastAdminInvariant {

    private final LastAdminGuard lastAdminGuard;

    @Override
    public void ensureTierRetainsAdmin(UUID orgId) {
        lastAdminGuard.ensureTierRetainsAdmin(orgId);
    }
}
