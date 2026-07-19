package com.example.sso.federation.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Links are ALWAYS addressed with their organization first — the same upstream subject may map to a different
 * account in another tenant, so an alias+subject lookup without the org would resolve across tenants. RLS is
 * the backstop; this signature is the first line.
 */
public interface FederatedIdentityLinkRepository extends JpaRepository<FederatedIdentityLink, UUID> {

    Optional<FederatedIdentityLink> findByOrgIdAndIssuerAndSubject(UUID orgId, String issuer, String subject);

    /** Whether this account already holds an identity at this issuer — the guard that stops a SECOND subject
     *  from claiming it by email (a recycled address must not inherit the previous holder's account). */
    boolean existsByOrgIdAndIssuerAndUserId(UUID orgId, String issuer, UUID userId);

    /** Drops an org's links for an upstream, e.g. when its provider is deleted or repointed elsewhere. */
    void deleteByOrgIdAndIssuer(UUID orgId, String issuer);
}
