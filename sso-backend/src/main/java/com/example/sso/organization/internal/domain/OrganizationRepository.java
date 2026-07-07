package com.example.sso.organization.internal.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {

    /** Resolves an organization by its (globally-unique) slug — the tenant is the organization. */
    Optional<Organization> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
