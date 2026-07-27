package com.example.sso.federation.internal.domain;

import com.example.sso.federation.FederationProtocol;
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

    /** One tier's providers speaking ONE protocol — for consumers that answer a protocol-specific question and
     *  would otherwise be silently widened by a registration in the other protocol. */
    List<IdentityProvider> findByOrgIdAndProtocolOrderByAlias(UUID orgId, FederationProtocol protocol);

    List<IdentityProvider> findByOrgIdIsNullOrderByAlias();

    Optional<IdentityProvider> findByOrgIdAndAlias(UUID orgId, String alias);

    Optional<IdentityProvider> findByOrgIdIsNullAndAlias(String alias);
}
