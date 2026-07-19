package com.example.sso.federation.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Explicit-tier access to {@link IdentityProvider}: {@code findByOrgId*} for a tenant's own providers,
 * {@code findByOrgIdIsNull*} for the platform tier. Both are used (never ambient RLS) so resolution is correct
 * under any bound context.
 */
public interface IdentityProviderRepository extends JpaRepository<IdentityProvider, UUID> {

    List<IdentityProvider> findByOrgIdOrderByAlias(UUID orgId);

    List<IdentityProvider> findByOrgIdIsNullOrderByAlias();

    Optional<IdentityProvider> findByOrgIdAndAlias(UUID orgId, String alias);

    Optional<IdentityProvider> findByOrgIdIsNullAndAlias(String alias);

    /** Whether ANOTHER of this org's providers still points at {@code issuerUri} — its identities must survive. */
    boolean existsByOrgIdAndIssuerUriAndAliasNot(UUID orgId, String issuerUri, String alias);
}
