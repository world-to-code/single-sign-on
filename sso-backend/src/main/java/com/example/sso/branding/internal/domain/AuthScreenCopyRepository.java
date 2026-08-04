package com.example.sso.branding.internal.domain;

import com.example.sso.branding.AuthScreen;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Explicit-tier access to {@link AuthScreenCopy}: {@code findByOrgId} for a tenant's own rows, {@code
 * findByOrgIdIsNull} for the platform-wide defaults. Both filter explicitly (never ambient RLS) so resolution
 * is correct under any bound context.
 *
 * <p>Reads are whole-tier rather than per screen: resolving one screen still needs the tier's other rows for
 * the editor, and five rows is one query either way.
 */
public interface AuthScreenCopyRepository extends JpaRepository<AuthScreenCopy, UUID> {

    List<AuthScreenCopy> findByOrgId(UUID orgId);

    List<AuthScreenCopy> findByOrgIdIsNull();

    Optional<AuthScreenCopy> findByOrgIdAndScreen(UUID orgId, AuthScreen screen);

    Optional<AuthScreenCopy> findByOrgIdIsNullAndScreen(AuthScreen screen);
}
