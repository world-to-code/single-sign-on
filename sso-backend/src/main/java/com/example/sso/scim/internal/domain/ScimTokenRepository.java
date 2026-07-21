package com.example.sso.scim.internal.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScimTokenRepository extends JpaRepository<ScimToken, UUID> {

    Optional<ScimToken> findByTokenHash(String tokenHash);

    boolean existsByTokenHash(String tokenHash);

    /**
     * The tokens that can write this organization's attributes: its own, AND the platform-global ones.
     *
     * <p>Global tokens matter and are easy to miss. A token with no org enters the platform context
     * (ScimBearerTokenFilter) and can provision into ANY tenant, so leaving it out of this answer would report
     * a tenant as fully accountable while an unlisted writer exists — the guard would pass on a record it has
     * no right to trust. Read to decide who is answerable for what a SCIM client writes.
     */
    List<ScimToken> findByOrgIdOrOrgIdIsNull(UUID orgId);
}
